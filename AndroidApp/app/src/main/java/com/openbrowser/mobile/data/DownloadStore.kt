package com.openbrowser.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 本环境发起的一条下载记录。 */
data class DownloadRecord(
    val id: Long,
    val name: String,
)

/**
 * 记录每个环境发起过的下载任务 id。
 *
 * 文件本身由系统 DownloadManager 管理，这里只保存归属关系，用于在「下载」列表里
 * 按环境展示，删除记录不会删除已下载的文件。
 */
class DownloadStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(profileId: String): List<DownloadRecord> = read(profileId)

    fun add(profileId: String, id: Long, name: String) {
        val entries = read(profileId).filterNot { it.id == id }.toMutableList()
        entries.add(0, DownloadRecord(id, name))
        write(profileId, entries.take(MAX_ENTRIES))
    }

    fun remove(profileId: String, id: Long) {
        write(profileId, read(profileId).filterNot { it.id == id })
    }

    fun clear(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun read(profileId: String): List<DownloadRecord> {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id", -1L)
                if (id <= 0L) return@mapNotNull null
                DownloadRecord(id, item.optString("name"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(profileId: String, entries: List<DownloadRecord>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.name)
                },
            )
        }
        prefs.edit().putString(key(profileId), array.toString()).apply()
    }

    private fun key(profileId: String): String = "downloads_$profileId"

    private companion object {
        const val PREFS_NAME = "openbrowser_browser_data"
        const val MAX_ENTRIES = 100
    }
}
