package com.openbrowser.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条书签。 */
data class BookmarkEntry(
    val url: String,
    val title: String,
    val createdAt: Long,
)

/** 按环境保存书签，和浏览历史一样属于环境私有数据。 */
class BookmarkStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(profileId: String): List<BookmarkEntry> = read(profileId)

    fun contains(profileId: String, url: String): Boolean =
        read(profileId).any { it.url == url }

    /**
     * 收藏 / 取消收藏，返回操作后是否处于已收藏状态。
     */
    fun toggle(profileId: String, url: String, title: String): Boolean {
        if (url.isBlank()) return false
        val entries = read(profileId)
        if (entries.any { it.url == url }) {
            write(profileId, entries.filterNot { it.url == url })
            return false
        }
        val updated = mutableListOf(
            BookmarkEntry(url, title.ifBlank { url }, System.currentTimeMillis()),
        )
        updated.addAll(entries)
        write(profileId, updated.take(MAX_ENTRIES))
        return true
    }

    fun remove(profileId: String, url: String) {
        write(profileId, read(profileId).filterNot { it.url == url })
    }

    fun clear(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun read(profileId: String): List<BookmarkEntry> {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                if (url.isBlank()) return@mapNotNull null
                BookmarkEntry(
                    url = url,
                    title = item.optString("title", url),
                    createdAt = item.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(profileId: String, entries: List<BookmarkEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("url", entry.url)
                    put("title", entry.title)
                    put("at", entry.createdAt)
                },
            )
        }
        prefs.edit().putString(key(profileId), array.toString()).apply()
    }

    private fun key(profileId: String): String = "bookmarks_$profileId"

    private companion object {
        const val PREFS_NAME = "openbrowser_browser_data"
        const val MAX_ENTRIES = 300
    }
}
