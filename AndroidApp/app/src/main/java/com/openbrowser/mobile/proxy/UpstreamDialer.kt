package com.openbrowser.mobile.proxy

import android.util.Base64
import com.openbrowser.mobile.data.ProxyConfig
import com.openbrowser.mobile.data.ProxyType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class UpstreamException(message: String) : IOException(message)

/**
 * 按代理配置建立到目标主机的 TCP 连接：
 * - direct：直连；
 * - http/https：向上游发 CONNECT 隧道（支持 Basic 认证）；
 * - socks5：RFC1928 + RFC1929 用户名密码认证。
 */
object UpstreamDialer {

    fun dial(proxy: ProxyConfig, host: String, port: Int, timeoutMs: Int = 15000): Socket {
        require(proxy.type == ProxyType.DIRECT || proxy.enabled) { "代理地址或端口无效" }
        return when (proxy.type) {
            ProxyType.DIRECT -> direct(host, port, timeoutMs)
            ProxyType.HTTP, ProxyType.HTTPS -> httpTunnel(proxy, host, port, timeoutMs)
            ProxyType.SOCKS5 -> socks5(proxy, host, port, timeoutMs)
        }
    }

    private fun direct(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    internal fun connectProxy(proxy: ProxyConfig, timeoutMs: Int = 15000): Socket {
        val socket = direct(proxy.host, proxy.port, timeoutMs)
        var tls: SSLSocket? = null
        try {
            socket.tcpNoDelay = true
            if (proxy.type != ProxyType.HTTPS) return socket
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, proxy.host, proxy.port, true) as SSLSocket
            tls.soTimeout = timeoutMs
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            return tls
        } catch (error: Exception) {
            runCatching { tls?.close() }
            runCatching { socket.close() }
            throw error
        }
    }

    private fun httpTunnel(proxy: ProxyConfig, host: String, port: Int, timeoutMs: Int): Socket {
        val socket = connectProxy(proxy, timeoutMs)
        try {
            val builder = StringBuilder()
            val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            builder.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            builder.append("Host: ").append(authority).append("\r\n")
            builder.append("Proxy-Connection: keep-alive\r\n")
            if (proxy.hasAuth) {
                val token = Base64.encodeToString(
                    "${proxy.username}:${proxy.password}".toByteArray(),
                    Base64.NO_WRAP,
                )
                builder.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
            }
            builder.append("\r\n")
            socket.getOutputStream().apply {
                write(builder.toString().toByteArray())
                flush()
            }

            val input = socket.getInputStream()
            val status = readLine(input) ?: throw UpstreamException("代理无响应")
            if (!status.contains(" 200")) {
                throw UpstreamException("代理拒绝连接 (${status.trim()})")
            }
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw if (e is IOException) e else UpstreamException(e.message ?: "代理握手失败")
        }
    }

    private fun socks5(proxy: ProxyConfig, host: String, port: Int, timeoutMs: Int): Socket {
        val socket = connectProxy(proxy, timeoutMs)
        try {
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            val methods = if (proxy.hasAuth) intArrayOf(0x00, 0x02) else intArrayOf(0x00)
            val greeting = ByteArray(2 + methods.size)
            greeting[0] = 0x05
            greeting[1] = methods.size.toByte()
            methods.forEachIndexed { index, method -> greeting[2 + index] = method.toByte() }
            output.write(greeting)
            output.flush()

            val version = input.read()
            val method = input.read()
            if (version != 0x05) throw UpstreamException("SOCKS5 协议版本不符")
            if (method !in methods) throw UpstreamException("SOCKS5 不接受认证方式")
            if (method == 0x02) {
                val user = proxy.username.toByteArray()
                val pass = proxy.password.toByteArray()
                if (user.size > 255 || pass.size > 255) throw UpstreamException("SOCKS5 用户名或密码过长")
                val auth = ByteArrayOutputStream()
                auth.write(0x01)
                auth.write(user.size)
                auth.write(user)
                auth.write(pass.size)
                auth.write(pass)
                output.write(auth.toByteArray())
                output.flush()
                val authVersion = input.read()
                val status = input.read()
                if (authVersion != 0x01 || status != 0x00) throw UpstreamException("SOCKS5 认证失败")
            }

            val hostBytes = host.toByteArray()
            if (hostBytes.size > 255) throw UpstreamException("域名过长")
            val request = ByteArrayOutputStream()
            request.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
            request.write(hostBytes)
            request.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
            output.write(request.toByteArray())
            output.flush()

            val reply = readFully(input, 4)
            if (reply[0].toInt() != 0x05) throw UpstreamException("SOCKS5 响应异常")
            if (reply[1].toInt() != 0x00) throw UpstreamException("SOCKS5 连接失败 code=${reply[1]}")
            when (reply[3].toInt()) {
                0x01 -> readFully(input, 4)
                0x04 -> readFully(input, 16)
                0x03 -> {
                    val length = input.read()
                    if (length > 0) readFully(input, length)
                }
                else -> throw UpstreamException("SOCKS5 未知地址类型")
            }
            readFully(input, 2)
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw if (e is IOException) e else UpstreamException(e.message ?: "SOCKS5 握手失败")
        }
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw UpstreamException("连接被对端关闭")
            offset += read
        }
        return buffer
    }

    fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < 8192) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString("ISO-8859-1")
    }
}
