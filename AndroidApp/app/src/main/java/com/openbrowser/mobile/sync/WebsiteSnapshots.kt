package com.openbrowser.mobile.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WebsiteSnapshots(private val context: Context, private val id: String) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
    private val store = SyncStore(context, "website-$id")
    fun read(): JSONObject = store.read()
    fun export(): JSONObject {
        val d = read(); val map = linkedMapOf<String, JSONObject>()
        for (a in listOf(d.optJSONArray("saved"), d.optJSONObject("pending")?.optJSONArray("sites"))) if (a != null) for (i in 0 until a.length()) a.getJSONObject(i).let { map[it.getString("origin")] = it }
        return JSONObject().put("siteData", JSONArray(map.values.sortedBy { it.getString("origin") })).put("credentials", d.optJSONArray("credentials") ?: JSONArray())
    }
    fun queue(profile: JSONObject, config: JSONObject) {
        if (config.optBoolean("includeSiteData")) WebsiteData.sites(profile.optJSONArray("siteData") ?: JSONArray())
        if (config.optBoolean("includeCredentials")) WebsiteData.credentials(profile.optJSONArray("credentials") ?: JSONArray())
        store.update { d ->
            if (config.optBoolean("includeSiteData")) {
                val current = linkedMapOf<String, JSONObject>()
                for (a in listOf(d.optJSONArray("saved"), d.optJSONObject("pending")?.optJSONArray("sites"))) if (a != null) for (i in 0 until a.length()) a.getJSONObject(i).let { current[it.getString("origin")] = it }
                fun digest(a: JSONArray) = SyncMerge.hash(JSONArray((0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.getString("origin") }))
                val incoming = profile.optJSONArray("siteData") ?: JSONArray()
                if (config.optBoolean("forceSiteData") || digest(JSONArray(current.values.toList())) != digest(incoming)) d.put("pending", JSONObject().put("revision", java.util.UUID.randomUUID().toString()).put("sites", incoming))
            }
            if (config.optBoolean("includeCredentials")) d.put("credentials", profile.optJSONArray("credentials") ?: JSONArray())
        }
    }
    fun save(site: JSONObject) {
        WebsiteData.sites(JSONArray().put(site))
        store.update { d ->
            val pending = d.optJSONObject("pending")?.optJSONArray("sites") ?: JSONArray()
            if ((0 until pending.length()).any { pending.getJSONObject(it).getString("origin") == site.getString("origin") }) return@update
            val saved = d.optJSONArray("saved") ?: JSONArray(); val next = JSONArray()
            for (i in 0 until saved.length()) if (saved.getJSONObject(i).getString("origin") != site.getString("origin")) next.put(saved.get(i))
            next.put(site); WebsiteData.sites(next); d.put("saved", next)
        }
        SyncScheduler.kick(context)
    }
    fun applied(revision: String, site: JSONObject) = store.update { d ->
        val pending = d.optJSONObject("pending") ?: return@update
        if (pending.optString("revision") != revision) return@update
        val saved = d.optJSONArray("saved") ?: JSONArray(); val next = JSONArray()
        for (i in 0 until saved.length()) if (saved.getJSONObject(i).getString("origin") != site.getString("origin")) next.put(saved.get(i))
        next.put(site); d.put("saved", next)
        val sites = pending.getJSONArray("sites"); val remaining = JSONArray()
        for (i in 0 until sites.length()) if (sites.getJSONObject(i).getString("origin") != site.getString("origin")) remaining.put(sites.get(i))
        if (remaining.length() == 0) d.remove("pending") else pending.put("sites", remaining)
    }
    fun editCredential(origin: String, username: String, password: String?, delete: Boolean = false) {
        WebsiteData.origin(origin)
        store.update { d ->
            val old = d.optJSONArray("credentials") ?: JSONArray(); val next = JSONArray(); var found = false
            for (i in 0 until old.length()) { val c = old.getJSONObject(i); if (c.getString("origin") == origin && c.getString("username") == username) { found = true; if (!delete) next.put(JSONObject(c.toString()).put("password", password ?: c.getString("password"))) } else next.put(c) }
            if (!found && !delete) next.put(JSONObject().put("origin", origin).put("username", username).put("password", password ?: error("请填写密码")))
            d.put("credentials", WebsiteData.credentials(next))
        }
        SyncScheduler.kick(context)
    }
    fun clearSites() = store.update { it.remove("saved"); it.remove("pending") }
    fun clear() = store.clear()
}
