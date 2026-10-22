package com.openbrowser.mobile.proxy

import com.openbrowser.mobile.data.ProxyConfig
import com.openbrowser.mobile.data.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 本地假代理验证真实握手，不依赖公网或 Android 设备。 */
class UpstreamDialerTest {
    @Test
    fun invalidProxyCannotFallBackToDirect() {
        assertThrows(IllegalArgumentException::class.java) {
            UpstreamDialer.dial(ProxyConfig(ProxyType.HTTP, "", 0), "127.0.0.1", 80, 1000)
        }
    }

    @Test
    fun httpProxyReceivesConnectWithBracketedIpv6() {
        val requestLine = AtomicReference<String>()
        withProxy({ socket ->
            val reader = socket.getInputStream().bufferedReader()
            requestLine.set(reader.readLine())
            while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
            socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        }) { port ->
            UpstreamDialer.dial(ProxyConfig(ProxyType.HTTP, "127.0.0.1", port), "::1", 443, 2000).close()
        }
        assertEquals("CONNECT [::1]:443 HTTP/1.1", requestLine.get())
    }

    @Test
    fun rejectedConnectClosesSocket() {
        withProxy({ socket ->
            val reader = socket.getInputStream().bufferedReader()
            while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
            socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray())
            assertEquals(-1, socket.getInputStream().read())
        }) { port ->
            assertThrows(UpstreamException::class.java) {
                UpstreamDialer.dial(ProxyConfig(ProxyType.HTTP, "127.0.0.1", port), "example.com", 443, 2000)
            }
        }
    }

    @Test
    fun socksCannotSelectAnUnofferedAuthenticationMethod() {
        withProxy({ socket ->
            val input = socket.getInputStream()
            assertEquals(5, input.read())
            assertEquals(1, input.read())
            assertEquals(0, input.read())
            socket.getOutputStream().write(byteArrayOf(5, 1))
        }) { port ->
            assertThrows(UpstreamException::class.java) {
                UpstreamDialer.dial(ProxyConfig(ProxyType.SOCKS5, "127.0.0.1", port), "example.com", 443, 2000)
            }
        }
    }

    @Test
    fun httpsProxyStartsTlsBeforeSendingConnect() {
        val firstByte = AtomicReference<Int>()
        withProxy({ socket ->
            firstByte.set(socket.getInputStream().read())
            // 关闭未完成的 TLS 握手，客户端必须失败，不能改用明文重试。
        }) { port ->
            assertThrows(IOException::class.java) {
                UpstreamDialer.dial(ProxyConfig(ProxyType.HTTPS, "127.0.0.1", port), "example.com", 443, 2000)
            }
        }
        assertEquals(22, firstByte.get()) // TLS handshake record
    }

    private fun withProxy(serve: (Socket) -> Unit, test: (Int) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val failure = AtomicReference<Throwable>()
            val worker = thread(isDaemon = true, name = "fake-proxy") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        serve(socket)
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            try {
                test(server.localPort)
            } finally {
                worker.join(6000)
            }
            assertTrue("Fake proxy did not terminate", !worker.isAlive)
            failure.get()?.let { throw AssertionError("Fake proxy failed", it) }
        }
    }
}
