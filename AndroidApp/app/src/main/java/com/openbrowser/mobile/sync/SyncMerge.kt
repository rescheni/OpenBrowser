package com.openbrowser.mobile.sync

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 与桌面 sync-merge.js 相同的三方合并规则，配置与 Cookie 分别比较。 */
object SyncMerge {
    private val fields = listOf("name", "group", "note", "startUrl", "userAgent", "platform", "locale", "timezone", "screenWidth", "screenHeight", "devicePixelRatio", "hardwareConcurrency", "deviceMemory", "touchPoints", "mobile", "webglVendor", "webglRenderer", "canvasNoise", "audioNoise", "webRtcMode", "webRtcIp")
    data class Plan(val body: JSONObject, val imports: JSONArray, val conflicts: JSONArray, val bases: JSONObject, val included: Set<String>)

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
    fun hash(value: Any?): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun configDigest(profile: JSONObject): String {
        val config = JSONObject()
        fields.forEach { config.put(it, profile.opt(it) ?: if (it in listOf("group", "note", "startUrl", "webRtcIp")) "" else JSONObject.NULL) }
        val p = profile.getJSONObject("proxy")
        config.put("proxy", if (p.getString("type") == "direct") JSONObject().put("type", "direct") else JSONObject()
            .put("type", p.getString("type")).put("host", p.getString("host").lowercase(Locale.ROOT)).put("port", p.getInt("port"))
            .put("username", p.optString("username")).put("password", p.optString("password")))
        return hash(config)
    }
    fun digests(profile: JSONObject): JSONObject {
        val cookies = profile.optJSONArray("cookies") ?: JSONArray()
        val values = (0 until cookies.length()).map { cookieValue(cookies.getJSONObject(it)) }.distinctBy { canonical(it) }.sortedBy { canonical(it) }
        val sites = profile.optJSONArray("siteData") ?: JSONArray()
        val credentials = profile.optJSONArray("credentials") ?: JSONArray()
        return JSONObject().put("config", configDigest(profile)).put("cookies", hash(JSONArray(values)))
            .put("siteData", hash(JSONArray((0 until sites.length()).map { sites.getJSONObject(it) }.sortedBy { it.getString("origin") })))
            .put("credentials", hash(JSONArray((0 until credentials.length()).map { credentials.getJSONObject(it) }.sortedBy { canonical(JSONArray().put(it.getString("origin")).put(it.getString("username"))) })))
    }
    fun cookieValue(record: JSONObject): JSONObject {
        PortableBundle.validateCookie(record)
        val uri = URI(record.getString("url")); val parts = record.getString("setCookie").split(';').map { it.trim() }
        val pair = parts.first(); val attrs = parts.drop(1).associate { it.substringBefore('=').lowercase(Locale.ROOT) to it.substringAfter('=', "") }
        val domain = attrs["domain"]?.removePrefix(".")?.lowercase(Locale.ROOT)
        val expires = attrs["expires"]?.let { text -> runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(text)?.time?.div(1000)
        }.getOrNull() } ?: 0L
        val result = JSONObject().put("name", pair.substringBefore('=')).put("value", pair.substringAfter('='))
            .put("host", domain ?: uri.host.lowercase(Locale.ROOT)).put("domain", domain != null)
            .put("path", attrs["path"] ?: uri.path.substringBeforeLast('/', "").ifEmpty { "/" })
            .put("secure", attrs.containsKey("secure")).put("httpOnly", attrs.containsKey("httponly"))
            .put("sameSite", attrs["samesite"]?.lowercase(Locale.ROOT) ?: "lax").put("expires", expires)
        attrs["max-age"]?.let { result.put("maxAge", it) }
        return result
    }
    fun plan(local: JSONObject, remote: JSONObject?, state: JSONObject, config: JSONObject, excluded: Set<String>): Plan {
        PortableBundle.validate(local)
        val space = config.getString("space")
        require(space.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "空间标识无效" }
        if (remote != null) {
            PortableBundle.validate(remote)
            require(remote.optJSONObject("sync")?.let { it.optInt("version") == 1 && it.optString("space") == space } == true) { "远端不属于此自动同步空间，请使用新文件地址或正确空间标识" }
        }
        fun map(body: JSONObject?): Map<String, JSONObject> {
            val array = body?.getJSONArray("profiles") ?: JSONArray()
            return (0 until array.length()).associate { array.getJSONObject(it).getString("id") to array.getJSONObject(it) }
        }
        val left = map(local); val right = map(remote); val output = right.toMutableMap()
        val imports = JSONArray(); val conflicts = JSONArray(); val included = left.keys.toMutableSet()
        val bases = JSONObject(state.optJSONObject("bases")?.toString() ?: "{}")
        val withCookies = config.optBoolean("includeCookies")
        val sections = listOf("config") + listOf("includeCookies" to "cookies", "includeSiteData" to "siteData", "includeCredentials" to "credentials").filter { config.optBoolean(it.first) }.map { it.second }
        fun baseline(p: JSONObject, old: JSONObject? = null): JSONObject {
            val d = digests(p)
            for (section in listOf("cookies", "siteData", "credentials")) if (section !in sections) { d.remove(section); old?.optString(section)?.takeIf { it.isNotEmpty() }?.let { d.put(section, it) } }
            return d
        }
        (left.keys + right.keys).forEach { id ->
            val l = left[id]; val r = right[id]; val base = bases.optJSONObject(id)
            if (l == null) {
                if (r != null && id !in excluded && base == null && config.optBoolean("acceptNew")) {
                    imports.put(r); included.add(id)
                    bases.put(id, baseline(r))
                }
                return@forEach
            }
            if (r == null) { output[id] = l; bases.put(id, baseline(l)); return@forEach }
            val ld = digests(l); val rd = digests(r); val selected = mutableMapOf<String, String>()
            sections.forEach { section ->
                val lh = ld.getString(section); val rh = rd.getString(section)
                val side = when {
                    lh == rh || rh == base?.optString(section) -> "local"
                    lh == base?.optString(section) -> "remote"
                    else -> state.optJSONObject("decisions")?.optJSONObject(id)?.optJSONObject(section)?.let {
                        if (it.optString("local") == lh && it.optString("remote") == rh && it.optString("side") in listOf("local", "remote")) it.getString("side") else null
                    }
                }
                if (side == null) conflicts.put(JSONObject().put("id", id).put("name", l.optString("name")).put("section", section).put("local", lh).put("remote", rh))
                else selected[section] = side
            }
            if (sections.any { selected[it] == null }) return@forEach
            val chosen = JSONObject((if (selected["config"] == "remote") r else l).toString())
            chosen.put("engine", l.getString("engine"))
            chosen.put("cookies", if (!withCookies || selected["cookies"] == "remote") r.getJSONArray("cookies") else l.getJSONArray("cookies"))
            for (section in listOf("siteData", "credentials")) {
                val value = if (section !in sections || selected[section] == "remote") r.opt(section) else l.opt(section)
                if (value == null) chosen.remove(section) else chosen.put(section, value)
            }
            output[id] = chosen
            bases.put(id, baseline(chosen, base))
            if (selected.values.any { it == "remote" }) imports.put(chosen)
        }
        val body = JSONObject().put("format", "openbrowser-portable").put("version", maxOf(local.getInt("version"), remote?.optInt("version") ?: 1)).put("createdAt", System.currentTimeMillis()).put("source", "sync")
            .put("sync", JSONObject().put("version", 1).put("space", space).put("revision", java.util.UUID.randomUUID().toString())).put("profiles", JSONArray(output.values.toList()))
        return Plan(PortableBundle.validate(body), imports, conflicts, bases, included)
    }
    fun resolve(conflicts: JSONArray, side: String): JSONObject {
        require(side in listOf("local", "remote"))
        val decisions = JSONObject()
        for (i in 0 until conflicts.length()) {
            val c = conflicts.getJSONObject(i); val id = c.getString("id")
            val sections = decisions.optJSONObject(id) ?: JSONObject().also { decisions.put(id, it) }
            sections.put(c.getString("section"), JSONObject().put("side", side).put("local", c.getString("local")).put("remote", c.getString("remote")))
        }
        return decisions
    }
}
