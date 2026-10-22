package com.openbrowser.mobile.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * 环境配置的本地存储：`files/profiles.json`，写入时先写临时文件再原子替换。
 * 只保存配置，不保存 Cookie / 缓存，后者由 WebView 的数据目录负责。
 */
class ProfileStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "profiles.json")
    private val atomicFile = AtomicFile(file)

    fun all(): List<Profile> = withStorageLock {
        load().sortedWith(compareByDescending<Profile> { it.lastUsedAt }.thenByDescending { it.updatedAt })
    }
    fun allStrict(): List<Profile> = withStorageLock { load(strict = true) }

    fun groups(): List<String> = withStorageLock {
        load().map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    fun get(id: String): Profile? = withStorageLock {
        load().firstOrNull { it.id == id }
    }

    fun count(): Int = withStorageLock { load().size }


    fun upsert(profile: Profile): Profile = withStorageLock {
        val list = load()
        profile.updatedAt = System.currentTimeMillis()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        save(list)
        com.openbrowser.mobile.sync.SyncScheduler.kick(appContext)
        profile
    }

    fun duplicate(id: String): Profile? = withStorageLock {
        val list = load()
        val source = list.firstOrNull { it.id == id } ?: return null
        val copy = source.cloneNew()
        list.add(copy)
        save(list)
        copy
    }

    fun delete(id: String) = withStorageLock {
        val list = load()
        list.removeAll { it.id == id }
        save(list)
        Unit
    }

    fun markUsed(id: String) = withStorageLock {
        val list = load()
        val target = list.firstOrNull { it.id == id } ?: return
        target.lastUsedAt = System.currentTimeMillis()
        save(list)
    }

    fun clearAll() = withStorageLock {
        atomicFile.delete()
        Unit
    }

    /** 兼容桌面端导出的环境清单（只读取能识别的字段）。 */
    fun importFrom(json: String): Int = withStorageLock {
        val value = runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull() ?: return 0
        val array = when (value) {
            is JSONObject -> value.optJSONArray("profiles") ?: return 0
            is JSONArray -> value
            else -> return 0
        }
        val list = load()
        val trashSuffixes = TrashProfileStore(appContext).all().map { it.profile.dataSuffix }.toSet()
        var added = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val profile = Profile.fromJson(migrateProfile(obj))
            if (profile.name.isBlank()) profile.name = "导入环境 ${list.size + 1}"
            if (!profile.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                list.any { it.id == profile.id || it.dataSuffix == profile.dataSuffix } ||
                profile.dataSuffix in trashSuffixes
            ) {
                profile.id = UUID.randomUUID().toString()
            }
            list.add(profile)
            added++
        }
        if (added > 0) save(list)
        added
    }

    fun exportJson(): String = withStorageLock {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("app", "OpenBrowser Mobile")
        val array = JSONArray()
        load().forEach { array.put(it.toJson()) }
        root.put("profiles", array)
        root.toString(2)
    }

    /** 跨端合并保留身份，重复导入不会复制环境；不传播删除。 */
    fun mergePortable(incoming: List<Profile>, replace: Boolean, expected: Map<String, String>? = null): Set<String> = withStorageLock {
        val list = load(strict = true)
        val trashSuffixes = TrashProfileStore(appContext).all().map { it.profile.dataSuffix }.toSet()
        val accepted = mutableSetOf<String>()
        incoming.forEach { profile ->
            val index = list.indexOfFirst { it.id == profile.id }
            if (expected != null) {
                val actual = if (index < 0) "" else com.openbrowser.mobile.sync.SyncMerge.configDigest(list[index].toJson())
                check(expected[profile.id] == actual) { "本机配置已变化，请重试" }
            }
            if (index >= 0 && !replace) return@forEach
            require(profile.dataSuffix !in trashSuffixes && list.none { it.id != profile.id && it.dataSuffix == profile.dataSuffix }) { "环境与现有数据或回收站冲突，请先处理冲突环境" }
            if (index >= 0) list[index] = profile else list.add(profile)
            accepted.add(profile.id)
        }
        if (accepted.isNotEmpty()) save(list)
        accepted
    }

    private fun load(strict: Boolean = false): MutableList<Profile> {
        if (!file.exists() && !File(file.path + ".bak").exists()) return mutableListOf()
        return runCatching {
            val root = JSONObject(atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val array = if (strict) root.getJSONArray("profiles") else root.optJSONArray("profiles") ?: JSONArray()
            val result = mutableListOf<Profile>()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { result.add(Profile.fromJson(migrateProfile(it))) }
            }
            result
        }.getOrElse { if (strict) throw java.io.IOException("本地配置读取失败，已停止同步以保留原文件", it) else mutableListOf() }
    }

    private fun save(list: List<Profile>) {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        root.put("profiles", array)
        val output = atomicFile.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    // JVM 锁协调同进程的多个 Store 实例；文件锁协调主进程与浏览器进程。
    // 锁文件保持独立，避免替换 profiles.json 后锁仍停留在旧 inode。
    private inline fun <T> withStorageLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        RandomAccessFile(File(appContext.filesDir, "profiles.lock"), "rw").use { lockFile ->
            lockFile.channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    /** 统一补齐旧版导入配置，避免每次新增字段都改动所有读取入口。 */
    private fun migrateProfile(source: JSONObject): JSONObject = JSONObject(source.toString()).apply {
        if (!has("engine")) put("engine", BrowserEngine.WEBVIEW.id)
        if (!has("deviceModel")) put("deviceModel", "")
        if (!has("webRtcMode")) put("webRtcMode", "blocked")
    }

    private companion object {
        const val SCHEMA_VERSION = 2
        val PROCESS_LOCK = Any()
    }
}
