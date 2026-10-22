package com.openbrowser.mobile.sync

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.openbrowser.mobile.data.ProfileStore
import com.openbrowser.mobile.data.TrashProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

data class SyncOutcome(val status: String, val message: String)

class SyncCoordinator(private val context: Context) {
    private val store = SyncStore(context)

    fun save(input: JSONObject) {
        store.update { root ->
            val old = root.getJSONObject("config")
            val next = JSONObject(input.toString())
            require(next.optString("provider") in listOf("webdav", "object", "s3", "lan", "local")) { "提供商无效" }
            require(next.optString("space").matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "空间标识无效" }
            val same = listOf("space", "provider", "getUrl", "localUri").all { next.optString(it) == old.optString(it) }
            for (key in listOf("password", "remotePassword", "secretKey", "sessionToken")) if (next.optString(key).isEmpty() && same) next.put(key, old.optString(key))
            if (next.optBoolean("enabled")) {
                require(next.optString("password").length >= 12) { "同步密码至少 12 字符" }
                require((if (next.optString("provider") == "local") next.optString("localUri") else next.optString("getUrl")).isNotBlank()) { "请选择同步文件或填写地址" }
                if (next.optString("provider") == "s3") require(next.optString("region").isNotBlank() && next.optString("accessKey").isNotBlank() && next.optString("secretKey").isNotBlank()) { "请填写 S3 Region 和访问密钥" }
            }
            next.put("interval", next.optInt("interval", 15).coerceIn(15, 1440))
            next.put("generation", java.util.UUID.randomUUID().toString())
            if (!same) root.put("state", JSONObject())
            else if (listOf("includeCookies", "includeSiteData", "includeCredentials").any { old.optBoolean(it) != next.optBoolean(it) }) {
                val state = root.optJSONObject("state") ?: JSONObject()
                state.optJSONObject("bases")?.let { bases -> bases.keys().forEach { id -> for ((option, section) in listOf("includeCookies" to "cookies", "includeSiteData" to "siteData", "includeCredentials" to "credentials")) if (old.optBoolean(option) != next.optBoolean(option)) bases.optJSONObject(id)?.remove(section) } }
                state.put("conflicts", JSONArray()).put("decisions", JSONObject()); root.put("state", state)
            }
            root.put("config", next)
        }
        SyncScheduler.configure(context)
    }

    fun resolve(side: String) {
        store.update { root ->
            val state = root.optJSONObject("state") ?: JSONObject()
            state.put("decisions", SyncMerge.resolve(state.optJSONArray("conflicts") ?: JSONArray(), side)); root.put("state", state)
        }
    }

    fun forget() {
        val handle = RandomAccessFile(File(context.filesDir, "sync-cycle.lock"), "rw")
        val lock = runCatching { handle.channel.tryLock() }.getOrNull()
        if (lock == null) { handle.close(); error("请先暂停同步，等待当前任务结束后再清除连接") }
        try {
            val uri = runCatching { store.read().getJSONObject("config").optString("localUri") }.getOrDefault("")
            store.clear()
            SyncScheduler.configure(context)
            if (uri.isNotEmpty()) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        } finally { lock.release(); handle.close() }
    }

    fun previousBackup(): ByteArray {
        val handle = RandomAccessFile(File(context.filesDir, "sync-cycle.lock"), "rw")
        val lock = runCatching { handle.channel.tryLock() }.getOrNull()
        if (lock == null) { handle.close(); error("请等待当前同步结束后再导出备份") }
        try {
            val config = store.read().getJSONObject("config")
            check(config.optString("provider") == "local") { "仅本地文件提供上一份密文备份；远端版本请使用存储服务的版本历史" }
            val previous = AtomicFile(File(context.filesDir, "sync-local.previous"))
            val data = previous.openRead().use { PortableTransport.readLimited(it) }
            check(decode(data, config.getString("password")).optJSONObject("sync")?.optString("space") == config.getString("space")) { "备份与当前同步空间不匹配" }
            return data
        } finally { lock.release(); handle.close() }
    }

    fun run(cancelled: () -> Boolean = { false }): SyncOutcome {
        val lockFile = RandomAccessFile(File(context.filesDir, "sync-cycle.lock"), "rw")
        val cycle = runCatching { lockFile.channel.tryLock() }.getOrNull()
        if (cycle == null) { lockFile.close(); return SyncOutcome("busy", "另一项同步正在执行") }
        try {
            val document = store.read(); val config = document.getJSONObject("config")
            if (!config.optBoolean("enabled")) return SyncOutcome("disabled", "自动同步已暂停")
            if (config.optBoolean("wifiOnly") && config.optString("provider") != "local") {
                val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
                check(connectivity != null && !connectivity.isActiveNetworkMetered) { "当前为计费网络，已暂停自动同步" }
            }
            val generation = config.optString("generation")
            fun active() { check(!cancelled() && !Thread.currentThread().isInterrupted && store.read().getJSONObject("config").optString("generation") == generation) { "同步设置已变化，任务停止" } }
            val all = ProfileStore(context).allStrict()
            val selected = config.optJSONArray("selected") ?: JSONArray()
            val ids = (0 until selected.length()).map { selected.getString(it) }.toSet()
            val local = PortableBundle.create(context, all.filter { it.id in ids }, config.optBoolean("includeCookies"), config)
            val transport = SyncProvider(context, config)
            val version = transport.read(); active()
            val remote = if (version.status == 404) null else decode(version.data, config.getString("password"))
            val excluded = (all.filter { it.id !in ids }.map { it.id } + TrashProfileStore(context).all().map { it.profile.id }).toSet()
            val state = document.optJSONObject("state") ?: JSONObject()
            val plan = SyncMerge.plan(local, remote, state, config, excluded)
            if (plan.conflicts.length() > 0) {
                active()
                store.update { root ->
                    val current = root.optJSONObject("state") ?: JSONObject()
                    current.put("conflicts", plan.conflicts); root.put("state", current)
                    record(root, "conflict", "${plan.conflicts.length()} 项冲突，未覆盖任何一端")
                }
                return SyncOutcome("conflict", "发现 ${plan.conflicts.length()} 项冲突，请在同步设置中解决")
            }
            val importIds = (0 until plan.imports.length()).map { plan.imports.getJSONObject(it).getString("id") }
            val lease = ProfileLeases.acquire(context, importIds) ?: error("请先关闭要接收更新的浏览器环境")
            lease.use {
                val currentAll = ProfileStore(context).allStrict()
                val current = PortableBundle.create(context, currentAll.filter { it.id in ids }, config.optBoolean("includeCookies"), config)
                check(signature(current) == signature(local)) { "本机数据已变化，请重试" }
                active()
                if (remote == null || signature(remote) != signature(plan.body)) {
                    val plain = plan.body.toString().toByteArray(Charsets.UTF_8)
                    val encrypted = try { PortableCrypto.seal(plain, config.getString("password")) } finally { plain.fill(0) }
                    active(); transport.write(encrypted, version)
                }
                active()
                if (plan.imports.length() > 0) {
                    val incoming = JSONObject(plan.body.toString()).put("profiles", plan.imports)
                    val before = all.associate { it.id to SyncMerge.configDigest(it.toJson()) }
                    val expected = importIds.associateWith { before[it] ?: "" }
                    PortableBundle.applyUnderLease(context, incoming, true, config.optBoolean("includeCookies"), expected, true)
                    if (config.optBoolean("includeSiteData") || config.optBoolean("includeCredentials")) for (i in 0 until plan.imports.length()) {
                        val p = plan.imports.getJSONObject(i); WebsiteSnapshots(context, p.getString("id")).queue(p, config)
                    }
                }
                active()
                store.update { root ->
                    check(root.getJSONObject("config").optString("generation") == generation) { "同步设置已变化" }
                    root.put("state", JSONObject().put("bases", plan.bases).put("conflicts", JSONArray()).put("lastSuccess", System.currentTimeMillis()))
                    root.getJSONObject("config").put("selected", JSONArray((ids + plan.included).toList()))
                    record(root, "success", "同步完成，接收 ${plan.imports.length()} 个环境")
                }
            }
            return SyncOutcome("success", "同步完成，接收 ${plan.imports.length()} 个环境")
        } catch (e: Exception) {
            val raw = e.message.orEmpty()
            val message = if (!raw.contains(Regex("https?:|Signature=|Credential=")) && raw.contains(Regex("冲突|变化|空间|密码|ETag|HTTP \\d+|关闭|代理|参数|配置|密钥|文件|权限|网络|Cookie|容量|快照|HTTPS"))) raw.take(200) else "同步失败，请检查网络、权限、同步密码和运行中的环境"
            runCatching { store.update { record(it, "error", message) } }
            return SyncOutcome("error", message)
        } finally { cycle.release(); lockFile.close() }
    }

    private fun decode(bytes: ByteArray, password: String): JSONObject {
        val plain = try { PortableCrypto.open(bytes, password) } catch (_: Exception) { error("同步密码错误或文件损坏") }
        return try { PortableBundle.validate(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
    }
    private fun signature(body: JSONObject): String {
        val profiles = body.getJSONArray("profiles")
        return (0 until profiles.length()).map { profiles.getJSONObject(it) }.sortedBy { it.getString("id") }
            .joinToString("|") { it.getString("id") + SyncMerge.canonical(SyncMerge.digests(it)) }
    }
    private fun record(root: JSONObject, status: String, message: String) {
        val history = root.optJSONArray("history") ?: JSONArray(); val next = JSONArray()
        next.put(JSONObject().put("at", System.currentTimeMillis()).put("status", status).put("message", message))
        for (i in 0 until minOf(history.length(), 49)) next.put(history.get(i))
        root.put("history", next)
    }
}

/** 本地 SAF 文件 + HTTP/S3 条件写入。文件系统不能替代跨设备网络 CAS。 */
private class SyncProvider(private val context: Context, private val config: JSONObject) {
    private val endpoint = SyncEndpoint.from(config)
    private val local get() = config.optString("provider") == "local"
    fun read(): PortableTransport.Response {
        if (!local) return PortableTransport.readVersion(endpoint)
        return accessLocal { input, _ -> version(input) }
    }
    fun write(data: ByteArray, before: PortableTransport.Response) {
        if (!local) { PortableTransport.writeVersion(endpoint, data, before); return }
        accessLocal { input, output ->
            val current = version(input)
            check(current.status == before.status && current.etag == before.etag) { "同步文件已变化，请重试" }
            if (current.status == 200) {
                val backup = AtomicFile(File(context.filesDir, "sync-local.previous")); val out = backup.startWrite()
                try { out.write(current.data); backup.finishWrite(out) } catch (e: Exception) { backup.failWrite(out); throw e }
            }
            output.channel.position(0); output.channel.truncate(0); output.write(data); output.channel.force(true)
        }
    }
    private fun version(input: FileInputStream): PortableTransport.Response {
        input.channel.position(0)
        val data = PortableTransport.readLimited(input)
        val tag = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        return PortableTransport.Response(if (data.isEmpty()) 404 else 200, if (data.isEmpty()) "" else "\"$tag\"", data)
    }
    private fun <T> accessLocal(block: (FileInputStream, FileOutputStream) -> T): T {
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(config.getString("localUri")), "rw") ?: error("本地文件权限不可用")
        descriptor.use {
            val input = FileInputStream(it.fileDescriptor); val output = FileOutputStream(it.fileDescriptor)
            try { output.channel.lock().use { block(input, output) } }
            finally { runCatching { input.close() }; runCatching { output.close() } }
        }
    }
}
