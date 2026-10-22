package com.openbrowser.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条浏览记录。 */
data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/**
 * 按环境保存浏览历史。
 *
 * 历史记录属于环境数据的一部分，因此以 profileId 分区存放；同一 URL 只保留一条，
 * 再次访问时更新时间并移到最前，避免列表被同一页面刷屏。
 */
class HistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(profileId: String): List<HistoryEntry> = read(profileId)

    fun record(profileId: String, url: String, title: String, at: Long = System.currentTimeMillis()) {
        if (!isRecordable(url)) return
        val entries = read(profileId).filterNot { it.url == url }.toMutableList()
        entries.add(0, HistoryEntry(url, title.ifBlank { url }, at))
        write(profileId, entries.take(MAX_ENTRIES))
    }

    fun remove(profileId: String, url: String) {
        write(profileId, read(profileId).filterNot { it.url == url })
    }

    fun clear(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun isRecordable(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private fun read(profileId: String): List<HistoryEntry> {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                if (url.isBlank()) return@mapNotNull null
                HistoryEntry(
                    url = url,
                    title = item.optString("title", url),
                    visitedAt = item.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(profileId: String, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("url", entry.url)
                    put("title", entry.title)
                    put("at", entry.visitedAt)
                },
            )
        }
        prefs.edit().putString(key(profileId), array.toString()).apply()
    }

    private fun key(profileId: String): String = "history_$profileId"

    private companion object {
        const val PREFS_NAME = "openbrowser_browser_data"
        const val MAX_ENTRIES = 400
    }
}
