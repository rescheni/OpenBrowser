package com.openbrowser.mobile.sync

import android.content.Context
import com.openbrowser.mobile.data.Profile
import com.openbrowser.mobile.data.ProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object PortableBundle {
    fun create(context: Context, profiles: List<Profile>, includeCookies: Boolean, config: JSONObject? = null): JSONObject {
        val snapshots = CookieSnapshots(context)
        val options = config ?: JSONObject()
        val items = JSONArray()
        profiles.forEach { profile ->
            items.put(profile.toJson().apply {
                if (options.optBoolean("includeSiteData") || options.optBoolean("includeCredentials")) {
                    val website = WebsiteSnapshots(context, profile.id).export()
                    if (options.optBoolean("includeSiteData")) put("siteData", website.getJSONArray("siteData"))
                    if (options.optBoolean("includeCredentials")) put("credentials", website.getJSONArray("credentials"))
                }
                put("cookies", if (includeCookies && profile.engine == "webview") snapshots.export(profile.id) else JSONArray())
            })
        }
        return validate(JSONObject().put("format", "openbrowser-portable").put("version", if (options.optBoolean("includeSiteData") || options.optBoolean("includeCredentials")) 2 else 1).put("source", "android").put("createdAt", System.currentTimeMillis()).put("profiles", items))
    }

    fun validate(root: JSONObject): JSONObject {
        require(root.optString("format") == "openbrowser-portable" && root.optInt("version") in listOf(1, 2)) { "不支持的跨端交换文件" }
        val profiles = root.getJSONArray("profiles")
        require(profiles.length() <= 1000) { "环境数量超过限制" }
        val ids = mutableSetOf<String>()
        val suffixes = mutableSetOf<String>()
        for (i in 0 until profiles.length()) {
            val item = profiles.getJSONObject(i)
            val id = item.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && ids.add(id)) { "环境 ID 无效或重复" }
            require(item.getString("name").length <= 100 && item.getString("userAgent").length in 1..1000 && !item.getString("userAgent").contains(Regex("[\\r\\n]"))) { "环境字段无效" }
            require(item.getString("engine") in listOf("webview", "gecko")) { "环境内核无效" }
            for (field in listOf("platform", "locale", "timezone")) require(item.getString(field).length in 1..200) { "语言或平台参数无效" }
            for ((field, range) in mapOf("screenWidth" to 1..16384, "screenHeight" to 1..16384, "hardwareConcurrency" to 1..256, "deviceMemory" to 1..1024, "touchPoints" to 0..64)) {
                val value = item.getDouble(field)
                require(value.isFinite() && value == value.toInt().toDouble() && value.toInt() in range) { "指纹参数不支持: $field" }
            }
            require(item.getDouble("devicePixelRatio").let { it.isFinite() && it in 0.1..16.0 }) { "DPR 无效" }
            require(item.getString("webRtcMode") in listOf("blocked", "real", "spoof")) { "WebRTC 设置无效" }
            val proxy = item.getJSONObject("proxy")
            require(proxy.getString("type") in listOf("direct", "http", "https", "socks5")) { "代理类型无效" }
            if (proxy.getString("type") != "direct") {
                require(proxy.getString("host").isNotBlank() && !proxy.getString("host").contains(Regex("[\\s/@?#]")) && proxy.getInt("port") in 1..65535) { "代理地址或端口无效" }
            }
            require(suffixes.add(Profile.fromJson(item).dataSuffix)) { "环境隔离目录冲突" }
            val cookies = item.getJSONArray("cookies")
            require(cookies.length() <= 5000) { "Cookie 数量超过限制" }
            require(item.getString("engine") != "gecko" || cookies.length() == 0) { "Gecko 暂不支持 Cookie 交换" }
            for (j in 0 until cookies.length()) validateCookie(cookies.getJSONObject(j))
            if (item.has("siteData") || item.has("credentials")) require(root.getInt("version") == 2) { "网站存储和密码库需要新版同步协议" }
            item.optJSONArray("siteData")?.let { WebsiteData.sites(it); require(item.getString("engine") != "gecko" || it.length() == 0) { "Gecko 暂不支持网站存储交换" } }
            item.optJSONArray("credentials")?.let { WebsiteData.credentials(it) }
            require(!item.has("siteData") || item.optJSONArray("siteData") != null) { "网站存储格式无效" }
            require(!item.has("credentials") || item.optJSONArray("credentials") != null) { "密码库格式无效" }
        }
        return root
    }

    fun validateCookie(record: JSONObject) {
        val uri = URI(record.getString("url"))
        val raw = record.getString("setCookie")
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && raw.length in 1..16384 && !raw.contains(Regex("[\\r\\n\\x00]"))) { "Cookie 记录无效" }
        val parts = raw.split(';').map { it.trim() }
        require(parts.first().matches(Regex("[^=;\\s]+=.*"))) { "Cookie 名称无效" }
        var secure = false
        var sameSite = ""
        parts.drop(1).forEach { attr ->
            val name = attr.substringBefore('=').lowercase()
            val value = attr.substringAfter('=', "").trim()
            when (name) {
                "domain" -> {
                    val domain = value.removePrefix(".").lowercase()
                    val host = uri.host.lowercase()
                    require(domain.isNotEmpty() && (host == domain || host.endsWith(".$domain"))) { "Cookie 域名与来源不匹配" }
                }
                "partitioned" -> error("暂不支持分区 Cookie 迁移")
                "secure" -> secure = true
                "samesite" -> sameSite = value.lowercase()
            }
        }
        require((!secure || uri.scheme == "https") && (sameSite != "none" || secure)) { "Cookie 安全属性无效" }
    }

    fun apply(context: Context, root: JSONObject, replace: Boolean, includeCookies: Boolean, websiteOptions: JSONObject? = null): String {
        validate(root)
        val array = root.getJSONArray("profiles")
        val ids = (0 until array.length()).map { array.getJSONObject(it).getString("id") }
        val lease = ProfileLeases.acquire(context, ids) ?: error("请先关闭要接收更新的浏览器环境")
        return lease.use { applyUnderLease(context, root, replace, includeCookies, websiteOptions = websiteOptions) }
    }

    internal fun applyUnderLease(context: Context, root: JSONObject, replace: Boolean, includeCookies: Boolean, expected: Map<String, String>? = null, managed: Boolean = false, websiteOptions: JSONObject? = null): String {
        validate(root)
        val items = root.getJSONArray("profiles")
        val incoming = (0 until items.length()).map { Profile.fromJson(items.getJSONObject(it)) }
        val accepted = ProfileStore(context).mergePortable(incoming, replace, expected)
        val snapshots = CookieSnapshots(context)
        var cookieCount = 0
        if (includeCookies) for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val cookies = item.getJSONArray("cookies")
            if (item.getString("id") in accepted && (cookies.length() > 0 || managed)) {
                if (managed) snapshots.queueKnown(item.getString("id"), cookies) else snapshots.queue(item.getString("id"), cookies)
                cookieCount += cookies.length()
            }
        }
        if (websiteOptions != null) for (i in 0 until items.length()) {
            val p = items.getJSONObject(i)
            val opts = JSONObject().put("forceSiteData", true).put("includeSiteData", websiteOptions.optBoolean("includeSiteData") && p.has("siteData"))
                .put("includeCredentials", websiteOptions.optBoolean("includeCredentials") && p.has("credentials"))
            if (p.getString("id") in accepted && (opts.optBoolean("includeSiteData") || opts.optBoolean("includeCredentials"))) WebsiteSnapshots(context, p.getString("id")).queue(p, opts)
        }
        return "导入 ${accepted.size} 个环境，保留本地 ${incoming.size - accepted.size} 个；待应用 Cookie $cookieCount 条。重新打开环境后生效。"
    }
}
