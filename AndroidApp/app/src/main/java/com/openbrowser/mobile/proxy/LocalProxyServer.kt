package com.openbrowser.mobile.proxy

import android.util.Base64
import android.util.Log
import com.openbrowser.mobile.data.ProxyConfig
import com.openbrowser.mobile.data.ProxyType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机 HTTP 代理转发器。
 *
 * WebView 的 ProxyController 不支持用户名密码，也不支持 SOCKS5，
 * 所以带认证或 SOCKS5 的环境会先连到这里的 127.0.0.1 端口，由它代做上游握手。
 */
class LocalProxyServer(private val upstream: ProxyConfig) {

    private val running = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        0, 64, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        { runnable -> Thread(runnable, "ob-proxy").apply { isDaemon = true } },
    )
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        serverSocket = socket
        running.set(true)
        executor.execute { acceptLoop(socket) }
        Log.i(TAG, "local proxy listening on 127.0.0.1:${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            connections.add(client)
            try {
                if (!running.get()) {
                    connections.remove(client)
                    client.close()
                    break
                }
                executor.execute { handleClient(client) }
            } catch (_: RejectedExecutionException) {
                connections.remove(client)
                client.close()
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstreamSocket: Socket? = null
        try {
            client.soTimeout = 30000
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val head = readHead(input) ?: return
            val text = String(head, Charsets.ISO_8859_1)
            val firstLine = text.substringBefore("\r\n")
            val parts = firstLine.split(" ")
            if (parts.size < 3) {
                writeAll(client.getOutputStream(), "HTTP/1.1 400 Bad Request\r\n\r\n")
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            val version = parts[2]

            if (method == "CONNECT") {
                val separator = target.lastIndexOf(':')
                if (separator <= 0) {
                    writeAll(client.getOutputStream(), "HTTP/1.1 400 Bad Request\r\n\r\n")
                    return
                }
                val host = target.substring(0, separator).trim('[', ']')
                val port = target.substring(separator + 1).toIntOrNull() ?: 443
                upstreamSocket = UpstreamDialer.dial(upstream, host, port)
                writeAll(client.getOutputStream(), "HTTP/1.1 200 Connection Established\r\nProxy-Agent: OpenBrowser-Mobile\r\n\r\n")
                tunnel(client, upstreamSocket)
                return
            }

            // 明文 HTTP：绝对 URI 形式
            val uri = runCatching { URI(target) }.getOrNull()
            if (uri?.host == null) {
                writeAll(client.getOutputStream(), "HTTP/1.1 400 Bad Request\r\n\r\n")
                return
            }
            val host = uri.host.trim('[', ']')
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

            val viaHttpProxy = upstream.type == ProxyType.HTTP || upstream.type == ProxyType.HTTPS
            val forwardedHead = if (viaHttpProxy) {
                // 直接交给上游代理，保留绝对 URI 形式
                upstreamSocket = connectUpstreamProxy()
                rewriteHead(text, absoluteTarget = true)
            } else {
                upstreamSocket = UpstreamDialer.dial(upstream, host, port)
                rewriteHead(text.replaceFirst("$method $target $version", "$method $path $version"), absoluteTarget = false)
            }
            upstreamSocket.getOutputStream().apply {
                write(forwardedHead.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            tunnel(client, upstreamSocket)
        } catch (e: Exception) {
            Log.w(TAG, "proxy error: ${e.message}")
            runCatching { writeAll(client.getOutputStream(), "HTTP/1.1 502 Bad Gateway\r\n\r\n") }
        } finally {
            runCatching { upstreamSocket?.close() }
            runCatching { client.close() }
            connections.remove(client)
            upstreamSocket?.let { connections.remove(it) }
        }
    }

    private fun connectUpstreamProxy(): Socket {
        return UpstreamDialer.connectProxy(upstream)
    }

    /** 去掉客户端自带的 Proxy-* 头，并按需补上上游认证头。 */
    private fun rewriteHead(head: String, absoluteTarget: Boolean): String {
        val lines = head.split("\r\n")
        val builder = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (index == 0) {
                builder.append(line).append("\r\n")
                return@forEachIndexed
            }
            if (line.isEmpty()) return@forEachIndexed
            val lower = line.lowercase()
            if (lower.startsWith("proxy-authorization:") || lower.startsWith("proxy-connection:")) return@forEachIndexed
            builder.append(line).append("\r\n")
        }
        if (absoluteTarget && upstream.hasAuth) {
            val token = Base64.encodeToString(
                "${upstream.username}:${upstream.password}".toByteArray(),
                Base64.NO_WRAP,
            )
            builder.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
        }
        builder.append("\r\n")
        return builder.toString()
    }

    private fun readHead(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val pattern = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (buffer.size() < 64 * 1024) {
            val value = input.read()
            if (value < 0) return null
            buffer.write(value)
            val b = value.toByte()
            matched = if (b == pattern[matched]) matched + 1 else if (b == pattern[0]) 1 else 0
            if (matched == 4) return buffer.toByteArray()
        }
        return null
    }

    private fun tunnel(client: Socket, upstreamSocket: Socket) {
        connections.add(upstreamSocket)
        if (!running.get()) {
            upstreamSocket.close()
            return
        }
        // 读请求头有时限；已建立的隧道允许长连接和空闲 WebSocket。
        client.soTimeout = 0
        upstreamSocket.soTimeout = 0
        val reverse = Thread({
            runCatching { copyStream(upstreamSocket.getInputStream(), client.getOutputStream()) }
            runCatching { client.shutdownOutput() }
        }, "ob-proxy-down").apply { isDaemon = true }
        reverse.start()
        runCatching { copyStream(client.getInputStream(), upstreamSocket.getOutputStream()) }
        runCatching { upstreamSocket.shutdownOutput() }
        reverse.join(60000)
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun writeAll(output: OutputStream, text: String) {
        output.write(text.toByteArray())
        output.flush()
    }

    companion object {
        private const val TAG = "LocalProxyServer"
    }
}
