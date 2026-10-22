package com.openbrowser.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ProxyType(val id: String) {
    DIRECT("direct"),
    HTTP("http"),
    HTTPS("https"),
    SOCKS5("socks5"),
    ;

    companion object {
        fun from(id: String?): ProxyType = entries.firstOrNull { it.id == id } ?: DIRECT
    }
}

/**
 * 渲染内核。安卓端能嵌入的内核只有两种，都不需要本地编译 Chromium：
 * - [WEBVIEW]：系统 Android System WebView，本身就是 Chromium 内核；
 * - [GECKO]：Mozilla GeckoView，Firefox 内核，以 Maven 预编译依赖的形式随 APK 打包。
 */
enum class BrowserEngine(val id: String) {
    WEBVIEW("webview"),
    GECKO("gecko"),
    ;

    companion object {
        fun from(id: String?): BrowserEngine = entries.firstOrNull { it.id == id } ?: WEBVIEW
    }
}

/** 单个环境的代理配置，字段含义与桌面端 OpenBrowser 保持一致。 */
data class ProxyConfig(
    var type: ProxyType = ProxyType.DIRECT,
    var host: String = "",
    var port: Int = 0,
    var username: String = "",
    var password: String = "",
) {
    val enabled: Boolean
        get() = type != ProxyType.DIRECT && host.isNotBlank() && port in 1..65535

    val hasAuth: Boolean
        get() = username.isNotBlank()

    fun display(): String = when {
        !enabled -> "直连"
        else -> "${type.id}://${if (hasAuth) "$username@" else ""}$host:$port"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.id)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
    }

    companion object {
        fun fromJson(obj: JSONObject?): ProxyConfig {
            if (obj == null) return ProxyConfig()
            return ProxyConfig(
                type = ProxyType.from(obj.optString("type")),
                host = obj.optString("host"),
                port = obj.optInt("port"),
                username = obj.optString("username"),
                password = obj.optString("password"),
            )
        }
    }
}

/**
 * 一个隔离的浏览器环境。
 *
 * 与桌面端一致：每套环境拥有独立 UA / 语言 / 时区 / 屏幕 / WebGL / Canvas 指纹以及独立代理，
 * 在安卓端通过 WebView 的数据目录后缀做到 Cookie、localStorage、IndexedDB、缓存完全隔离。
 */
data class Profile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var group: String = "",
    var note: String = "",
    var startUrl: String = "",
    /** 该环境使用的渲染内核，见 [BrowserEngine]。 */
    var engine: String = BrowserEngine.WEBVIEW.id,
    var userAgent: String = DevicePresets.defaultPreset.userAgent,
    var platform: String = DevicePresets.defaultPreset.platform,
    var locale: String = DevicePresets.defaultPreset.locale,
    var timezone: String = "Asia/Shanghai",
    var screenWidth: Int = DevicePresets.defaultPreset.screenWidth,
    var screenHeight: Int = DevicePresets.defaultPreset.screenHeight,
    var devicePixelRatio: Double = DevicePresets.defaultPreset.devicePixelRatio,
    /** 展示用的机型名，例如「小米 14」「iPhone 15 Pro」，随机生成或选择预设时写入。 */
    var deviceModel: String = "",
    var hardwareConcurrency: Int = 8,
    var deviceMemory: Int = 4,
    var touchPoints: Int = 5,
    var mobile: Boolean = true,
    var webglVendor: String = DevicePresets.defaultPreset.webglVendor,
    var webglRenderer: String = DevicePresets.defaultPreset.webglRenderer,
    var canvasNoise: Boolean = true,
    var audioNoise: Boolean = true,
    /** blocked = 屏蔽 WebRTC 内网/公网地址，real = 透传真实地址，spoof = 替换为指定 IP。 */
    var webRtcMode: String = "blocked",
    var webRtcIp: String = "",
    var proxy: ProxyConfig = ProxyConfig(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastUsedAt: Long = 0L,
) {
    /** WebView 数据目录后缀，必须由 [a-zA-Z0-9_] 组成且同进程唯一。 */
    val dataSuffix: String
        get() = "p_" + id.replace("-", "").take(20).ifBlank { "default" }

    /** 稳定的指纹种子，保证同一环境每次出图一致。 */
    val seed: Int
        get() = id.hashCode() and 0x7fffffff

    /** 语言列表：主语言 + 语言前缀 + 英语回退，和桌面端行为一致。 */
    val languages: List<String>
        get() {
            val list = LinkedHashSet<String>()
            if (locale.isNotBlank()) {
                list += locale
                list += locale.substringBefore('-')
            }
            list += "en"
            list += "en-US"
            return list.toList()
        }

    fun cloneNew(): Profile {
        val copy = copy(
            id = UUID.randomUUID().toString(),
            name = if (name.isBlank()) "环境副本" else "$name 副本",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            lastUsedAt = 0L,
        )
        return copy
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("group", group)
        put("note", note)
        put("startUrl", startUrl)
        put("engine", engine)
        put("userAgent", userAgent)
        put("platform", platform)
        put("locale", locale)
        put("timezone", timezone)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("devicePixelRatio", devicePixelRatio)
        put("hardwareConcurrency", hardwareConcurrency)
        put("deviceMemory", deviceMemory)
        put("touchPoints", touchPoints)
        put("mobile", mobile)
        put("webglVendor", webglVendor)
        put("webglRenderer", webglRenderer)
        put("canvasNoise", canvasNoise)
        put("audioNoise", audioNoise)
        put("webRtcMode", webRtcMode)
        put("webRtcIp", webRtcIp)
        put("deviceModel", deviceModel)
        put("proxy", proxy.toJson())
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("lastUsedAt", lastUsedAt)
    }

    /**
     * 生成注入脚本使用的配置 JSON。
     */
    fun fingerprintJson(): JSONObject = JSONObject().apply {
        put("userAgent", userAgent)
        put("platform", platform)
        put("locale", locale)
        put("languages", JSONArray(languages))
        put("timezone", timezone)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("devicePixelRatio", devicePixelRatio)
        put("hardwareConcurrency", hardwareConcurrency)
        put("deviceMemory", deviceMemory)
        put("touchPoints", touchPoints)
        put("mobile", mobile)
        put("webglVendor", webglVendor)
        put("webglRenderer", webglRenderer)
        put("canvasNoise", canvasNoise)
        put("audioNoise", audioNoise)
        put("webRtcMode", webRtcMode)
        put("webRtcIp", webRtcIp)
        put("seed", seed)
    }

    companion object {
        fun fromJson(obj: JSONObject): Profile = Profile(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = obj.optString("name"),
            group = obj.optString("group"),
            note = obj.optString("note"),
            startUrl = obj.optString("startUrl", ""),
            engine = BrowserEngine.from(obj.optString("engine")).id,
            userAgent = obj.optString("userAgent", DevicePresets.defaultPreset.userAgent),
            platform = obj.optString("platform", DevicePresets.defaultPreset.platform),
            locale = obj.optString("locale", "zh-CN"),
            timezone = obj.optString("timezone", "Asia/Shanghai"),
            screenWidth = obj.optInt("screenWidth", 412),
            screenHeight = obj.optInt("screenHeight", 915),
            devicePixelRatio = obj.optDouble("devicePixelRatio", 2.625),
            deviceModel = obj.optString("deviceModel"),
            hardwareConcurrency = obj.optInt("hardwareConcurrency", 8),
            deviceMemory = obj.optInt("deviceMemory", 4),
            touchPoints = obj.optInt("touchPoints", 5),
            mobile = obj.optBoolean("mobile", true),
            webglVendor = obj.optString("webglVendor", "Qualcomm"),
            webglRenderer = obj.optString("webglRenderer", "Adreno (TM) 640"),
            canvasNoise = obj.optBoolean("canvasNoise", true),
            audioNoise = obj.optBoolean("audioNoise", true),
            webRtcMode = obj.optString("webRtcMode", "blocked"),
            webRtcIp = obj.optString("webRtcIp"),
            proxy = ProxyConfig.fromJson(obj.optJSONObject("proxy")),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            lastUsedAt = obj.optLong("lastUsedAt", 0L),
        )
    }
}
