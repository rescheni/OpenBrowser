package com.openbrowser.mobile.proxy

import com.openbrowser.mobile.data.ProxyConfig
import com.openbrowser.mobile.data.ProxyType
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class ProxyTestResult(val success: Boolean, val message: String)

/**
 * 出口检测：通过当前代理访问纯 HTTP 的 IP 回显服务，返回出口 IP。
 * 与桌面端一致，检测失败时会给出可直接排查的错误信息。
 */
object ProxyTester {

    private val endpoints = listOf(
        "api.ipify.org" to "/?format=text",
        "icanhazip.com" to "/",
    )

    fun test(proxy: ProxyConfig): ProxyTestResult {
        if (proxy.type != ProxyType.DIRECT && !proxy.enabled) {
            return ProxyTestResult(false, "检测失败：代理地址或端口无效")
        }
        var lastError = "未知错误"
        for ((host, path) in endpoints) {
            val result = runCatching { probe(proxy, host, path) }
            result.onSuccess { ip ->
                if (ip.isNotBlank()) {
                    val label = if (proxy.enabled) proxy.display() else "直连"
                    return ProxyTestResult(true, "出口 IP：$ip（$label）")
                }
            }.onFailure { lastError = it.message ?: it.javaClass.simpleName }
        }
        return ProxyTestResult(false, "检测失败：$lastError")
    }

    private fun probe(proxy: ProxyConfig, host: String, path: String): String {
        val socket = if (proxy.enabled) {
            UpstreamDialer.dial(proxy, host, 80, 12000)
        } else {
            Socket().apply {
                connect(InetSocketAddress(host, 80), 12000)
                soTimeout = 12000
            }
        }
        socket.use {
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: OpenBrowser-Mobile/1.0\r\n" +
                "Accept: text/plain\r\n" +
                "Connection: close\r\n\r\n"
            it.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val body = readResponse(it)
            if (body == null) throw IllegalStateException("响应为空")
            return body.trim().lines().firstOrNull()?.trim().orEmpty()
        }
    }

    private fun readResponse(socket: Socket): String? {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var total = 0
        while (total < 32 * 1024) {
            val read = try {
                input.read(chunk)
            } catch (e: Exception) {
                break
            }
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        val text = buffer.toString("ISO-8859-1")
        if (!text.startsWith("HTTP/")) throw IllegalStateException("非 HTTP 响应")
        val statusLine = text.substringBefore("\r\n")
        if (!statusLine.contains(" 200")) throw IllegalStateException(statusLine.take(80))
        val body = text.substringAfter("\r\n\r\n", "")
        return body.trim().ifBlank { null }
    }
}
