package com.openbrowser.mobile.data

/**
 * 设备预设：只覆盖设备层面的参数（UA / 平台 / 屏幕 / DPR / 触摸 / WebGL），
 * 语言与时区保留用户当前设置，避免切换设备时把区域配置一并冲掉。
 */
data class DevicePreset(
    val id: String,
    val title: String,
    val summary: String,
    val userAgent: String,
    val platform: String,
    val locale: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val devicePixelRatio: Double,
    val touchPoints: Int,
    val mobile: Boolean,
    val webglVendor: String,
    val webglRenderer: String,
) {
    fun applyTo(profile: Profile): Profile = profile.copy(
        userAgent = userAgent,
        platform = platform,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        devicePixelRatio = devicePixelRatio,
        touchPoints = touchPoints,
        mobile = mobile,
        webglVendor = webglVendor,
        webglRenderer = webglRenderer,
        deviceModel = title,
        updatedAt = System.currentTimeMillis(),
    )
}

object DevicePresets {

    val all: List<DevicePreset> = listOf(
        DevicePreset(
            id = "android-pixel-8",
            title = "Android · Pixel 8",
            summary = "Chrome 126 / 412×915 / DPR 2.625",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36",
            platform = "Linux armv8l",
            locale = "zh-CN",
            screenWidth = 412,
            screenHeight = 915,
            devicePixelRatio = 2.625,
            touchPoints = 5,
            mobile = true,
            webglVendor = "Qualcomm",
            webglRenderer = "Adreno (TM) 740",
        ),
        DevicePreset(
            id = "android-galaxy-s24",
            title = "Android · Galaxy S24",
            summary = "Chrome 126 / 360×780 / DPR 3",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S9210) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36",
            platform = "Linux armv8l",
            locale = "zh-CN",
            screenWidth = 360,
            screenHeight = 780,
            devicePixelRatio = 3.0,
            touchPoints = 5,
            mobile = true,
            webglVendor = "Qualcomm",
            webglRenderer = "Adreno (TM) 750",
        ),
        DevicePreset(
            id = "android-xiaomi-14",
            title = "Android · 小米 14",
            summary = "Chrome 126 / 393×852 / DPR 2.75",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CC) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36",
            platform = "Linux armv8l",
            locale = "zh-CN",
            screenWidth = 393,
            screenHeight = 852,
            devicePixelRatio = 2.75,
            touchPoints = 5,
            mobile = true,
            webglVendor = "Qualcomm",
            webglRenderer = "Adreno (TM) 750",
        ),
        DevicePreset(
            id = "ios-iphone-15-pro",
            title = "iOS · iPhone 15 Pro",
            summary = "Safari 17.5 / 393×852 / DPR 3",
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            platform = "iPhone",
            locale = "zh-CN",
            screenWidth = 393,
            screenHeight = 852,
            devicePixelRatio = 3.0,
            touchPoints = 5,
            mobile = true,
            webglVendor = "Apple Inc.",
            webglRenderer = "Apple GPU",
        ),
        DevicePreset(
            id = "ios-ipad-air",
            title = "iOS · iPad Air (M2)",
            summary = "Safari 17.5 / 820×1180 / DPR 2",
            userAgent = "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            platform = "iPad",
            locale = "zh-CN",
            screenWidth = 820,
            screenHeight = 1180,
            devicePixelRatio = 2.0,
            touchPoints = 5,
            mobile = true,
            webglVendor = "Apple Inc.",
            webglRenderer = "Apple GPU",
        ),
        DevicePreset(
            id = "windows-chrome",
            title = "Windows · Chrome 126",
            summary = "Win32 / 1920×1080 / DPR 1",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36",
            platform = "Win32",
            locale = "zh-CN",
            screenWidth = 1920,
            screenHeight = 1080,
            devicePixelRatio = 1.0,
            touchPoints = 0,
            mobile = false,
            webglVendor = "Google Inc. (Intel)",
            webglRenderer = "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)",
        ),
        DevicePreset(
            id = "macos-chrome",
            title = "macOS · Chrome 126",
            summary = "MacIntel / 1440×900 / DPR 2",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36",
            platform = "MacIntel",
            locale = "zh-CN",
            screenWidth = 1440,
            screenHeight = 900,
            devicePixelRatio = 2.0,
            touchPoints = 0,
            mobile = false,
            webglVendor = "Google Inc. (Apple)",
            webglRenderer = "ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)",
        ),
    )

    val defaultPreset: DevicePreset get() = all.first()

    fun byId(id: String?): DevicePreset? = all.firstOrNull { it.id == id }

    fun match(profile: Profile): DevicePreset? =
        all.firstOrNull { it.userAgent == profile.userAgent && it.platform == profile.platform }
}
