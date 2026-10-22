package com.openbrowser.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TrashedProfile(
    val profile: Profile,
    val deletedAt: Long,
)

/** 保存已删除的环境配置，默认保留 30 天；网站数据目录不会在移入回收站时删除。 */
class TrashProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<TrashedProfile> = read().sortedByDescending { it.deletedAt }

    fun add(profile: Profile) {
        val items = read().filterNot { it.profile.id == profile.id }.toMutableList()
        items.add(0, TrashedProfile(profile, System.currentTimeMillis()))
        write(items.take(MAX_ITEMS))
    }

    fun remove(id: String) = write(read().filterNot { it.profile.id == id })

    fun get(id: String): TrashedProfile? = read().firstOrNull { it.profile.id == id }

    fun clear() = prefs.edit().remove(KEY).apply()

    fun purgeExpired(now: Long = System.currentTimeMillis()): List<TrashedProfile> {
        val expired = read().filter { now - it.deletedAt >= RETENTION_MS }
        // 删除实际数据成功后，调用方再移除记录；活动环境清理失败时仍可恢复。
        return expired
    }

    private fun read(): List<TrashedProfile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val profile = Profile.fromJson(item.optJSONObject("profile") ?: return@mapNotNull null)
                TrashedProfile(profile, item.optLong("deletedAt", 0L))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<TrashedProfile>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("profile", item.profile.toJson())
                put("deletedAt", item.deletedAt)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "openbrowser_trash"
        const val KEY = "profiles"
        const val MAX_ITEMS = 200
        const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
