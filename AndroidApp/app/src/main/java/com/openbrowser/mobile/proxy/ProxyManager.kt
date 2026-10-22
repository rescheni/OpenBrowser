package com.openbrowser.mobile.proxy

import android.util.Log
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.openbrowser.mobile.data.ProxyConfig
import com.openbrowser.mobile.data.ProxyType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executor

/**
 * 把环境代理应用到 WebView。
 *
 * - 无认证的 HTTP 代理：直接交给 ProxyController；
 * - 带认证 / SOCKS5 / HTTPS 代理：走本地 [LocalProxyServer] 转发；
 * - 直连：清除代理覆盖。
 */
object ProxyManager {

    private const val TAG = "ProxyManager"

    private val directExecutor = Executor { it.run() }

    private var localServer: LocalProxyServer? = null

    @Volatile
    var lastMessage: String = ""
        private set

    /** 只有系统通知覆盖设置生效后才能发出网页请求。失败时调用方必须停止加载。 */
    fun apply(proxy: ProxyConfig, onComplete: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        val complete: (Boolean) -> Unit = { success ->
            if (completed.compareAndSet(false, true)) onComplete(success)
        }
        try {
            stopLocalServer()
            require(proxy.type == ProxyType.DIRECT || proxy.enabled) { "代理地址或端口无效" }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (proxy.type == ProxyType.DIRECT) {
                    lastMessage = "直连"
                    complete(true)
                } else {
                    lastMessage = "当前 WebView 版本不支持代理设置，请升级 Android System WebView"
                    complete(false)
                }
                return
            }
            if (proxy.type == ProxyType.DIRECT) {
                lastMessage = "直连"
                ProxyController.getInstance().clearProxyOverride(directExecutor, Runnable { complete(true) })
                return
            }
            val needLocalForwarder =
                proxy.hasAuth || proxy.type == ProxyType.SOCKS5 || proxy.type == ProxyType.HTTPS
            val host: String
            val port: Int
            if (needLocalForwarder) {
                val server = LocalProxyServer(proxy)
                localServer = server
                port = server.start()
                host = "127.0.0.1"
            } else {
                host = proxy.host
                port = proxy.port
            }
            lastMessage = proxy.display()
            val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val config = androidx.webkit.ProxyConfig.Builder()
                .addProxyRule(authority)
                .build()
            ProxyController.getInstance().setProxyOverride(config, directExecutor, Runnable { complete(true) })
        } catch (error: Exception) {
            stopLocalServer()
            lastMessage = "代理设置失败：${error.message}"
            Log.w(TAG, lastMessage, error)
            complete(false)
        }
    }

    fun stop() {
        stopLocalServer()
        clearOverride()
    }

    private fun clearOverride() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        runCatching { ProxyController.getInstance().clearProxyOverride(directExecutor, Runnable {}) }
    }

    private fun stopLocalServer() {
        localServer?.stop()
        localServer = null
    }
}
