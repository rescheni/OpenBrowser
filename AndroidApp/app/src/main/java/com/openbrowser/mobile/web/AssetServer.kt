package com.openbrowser.mobile.web

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 极简本地页面服务，只监听 127.0.0.1 的随机端口。
 *
 * GeckoView 不允许顶层导航到 `data:` URI，内置的检测页 / 起始页没法像 WebView 那样用
 * `loadDataWithBaseURL` 打开；这里用一个回环 HTTP 服务把同样的 HTML 交给 Gecko 渲染。
 * 服务不对外网开放，也不读写任何用户文件。
 */
class AssetServer {

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 起始页 HTML，由浏览器在打开新标签前写入。 */
    @Volatile
    var startHtml: String = ""

    /** 环境检测页 HTML。 */
    @Volatile
    var checkHtml: String = ""

    val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket?.localPort ?: 0}"

    val startUrl: String
        get() = "$baseUrl/start"

    val checkUrl: String
        get() = "$baseUrl/check"

    fun start() {
        if (serverSocket != null) return
        val socket = runCatching {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        }.getOrNull() ?: return
        serverSocket = socket
        acceptThread = Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread({ serve(client) }, "openbrowser-asset-client").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "openbrowser-assets").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun serve(client: Socket) {
        runCatching {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine().orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
                val body = when (path) {
                    "/start" -> startHtml
                    "/check" -> checkHtml
                    else -> null
                }
                val out = BufferedOutputStream(socket.getOutputStream())
                if (body == null) {
                    out.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    )
                } else {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(bytes)
                }
                out.flush()
            }
        }
    }
}
