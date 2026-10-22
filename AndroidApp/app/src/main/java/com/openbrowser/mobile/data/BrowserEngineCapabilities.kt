package com.openbrowser.mobile.data

/** 内核能力集中定义，避免菜单和页面逻辑散落判断具体内核。 */
data class BrowserEngineCapabilities(
    val supportsProxy: Boolean,
    val supportsFingerprintInjection: Boolean,
    val supportsDownloads: Boolean,
    val supportsFindInPage: Boolean,
    val googleLoginHint: String,
) {
    companion object {
        fun forEngine(engine: BrowserEngine): BrowserEngineCapabilities = when (engine) {
            BrowserEngine.WEBVIEW -> BrowserEngineCapabilities(
                supportsProxy = true,
                supportsFingerprintInjection = true,
                supportsDownloads = true,
                supportsFindInPage = true,
                googleLoginHint = "系统 WebView 可能被 Google 登录策略拒绝",
            )

            BrowserEngine.GECKO -> BrowserEngineCapabilities(
                supportsProxy = false,
                supportsFingerprintInjection = false,
                supportsDownloads = true,
                supportsFindInPage = true,
                googleLoginHint = "Firefox 内核通常兼容性更好，仍以站点策略为准",
            )
        }

        fun forProfile(profile: Profile): BrowserEngineCapabilities =
            forEngine(BrowserEngine.from(profile.engine))
    }
}
