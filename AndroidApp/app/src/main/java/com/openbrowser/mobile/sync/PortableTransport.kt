package com.openbrowser.mobile.sync

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

data class SyncEndpoint(val getUrl: String, val putUrl: String = getUrl, val username: String = "", val password: String = "",
    val provider: String = "webdav", val region: String = "", val accessKey: String = "", val secretKey: String = "", val sessionToken: String = "") {
    companion object {
        fun from(config: org.json.JSONObject): SyncEndpoint {
            val get = config.optString("getUrl"); val provider = config.optString("provider", "webdav")
            return SyncEndpoint(get, config.optString("putUrl").ifBlank { get }, if (provider == "webdav") config.optString("username") else "", if (provider == "webdav") config.optString("remotePassword") else "",
                provider, config.optString("region"), config.optString("accessKey"), config.optString("secretKey"), config.optString("sessionToken"))
        }
    }
}

/** WebDAV 文件、对象存储预签名 URL、局域网交换使用相同的 GET/PUT。不跟随重定向。 */
object PortableTransport {
    data class Response(val status: Int, val etag: String, val data: ByteArray)

    fun readVersion(config: SyncEndpoint): Response {
        val result = request("GET", config.getUrl, config)
        check(result.status == 200 || result.status == 404) { "读取同步空间失败 HTTP ${result.status}" }
        if (result.status == 200) check(result.etag.isNotEmpty() && !result.etag.startsWith("W/")) { "自动同步需要强 ETag" }
        return result
    }
    fun writeVersion(config: SyncEndpoint, data: ByteArray, version: Response) {
        if (config.username.isNotEmpty()) {
            val get = URI(config.getUrl); val put = URI(config.putUrl)
            require(get.scheme == put.scheme && get.host == put.host && get.port == put.port) { "WebDAV 上传下载地址必须同源" }
        }
        val r = request("PUT", config.putUrl, config, data, version.etag, version.status == 404)
        check(r.status != 409 && r.status != 412) { "同步文件已变化，请重试" }
        check(r.status in listOf(200, 201, 204)) { "写入同步空间失败 HTTP ${r.status}" }
    }

    fun readLimited(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + 30_000_000_000L
        while (true) {
            check(System.nanoTime() < deadline) { "读取超时" }
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size() + count <= PortableCrypto.MAX_BYTES) { "交换文件超过 8 MB" }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    fun download(config: SyncEndpoint): ByteArray {
        val response = request("GET", config.getUrl, config)
        check(response.status == 200) { "下载失败 HTTP ${response.status}" }
        return response.data
    }

    fun upload(config: SyncEndpoint, data: ByteArray, inspectPrevious: ((ByteArray) -> Unit)? = null) {
        if (config.username.isNotEmpty()) {
            val get = runCatching { URI(config.getUrl) }.getOrElse { error("远端地址无效") }
            val put = runCatching { URI(config.putUrl) }.getOrElse { error("远端地址无效") }
            require(get.scheme == put.scheme && get.host == put.host && get.port == put.port) { "带账号的 WebDAV 上传和下载地址必须同源" }
        }
        val before = request("GET", config.getUrl, config)
        check(before.status == 200 || before.status == 404) { "读取远端版本失败 HTTP ${before.status}" }
        check(before.status == 404 || (before.etag.isNotEmpty() && !before.etag.startsWith("W/"))) { "服务器缺少强 ETag，请使用新文件名" }
        if (before.status == 200) inspectPrevious?.invoke(before.data)
        val response = request("PUT", config.putUrl, config, data, before.etag, before.status == 404)
        check(response.status != 412 && response.status != 409) { "远端已被其他设备修改，请重新拉取后再试" }
        check(response.status in listOf(200, 201, 204)) { "上传失败 HTTP ${response.status}" }
    }

    private fun request(method: String, address: String, config: SyncEndpoint, data: ByteArray? = null, etag: String = "", createOnly: Boolean = false): Response {
        val uri = runCatching { URI(address) }.getOrElse { error("远端地址无效") }
        val host = uri.host.orEmpty()
        val local = host == "localhost" || host.matches(Regex("127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+"))
        require(uri.userInfo == null && uri.fragment == null && host.isNotEmpty() && (uri.scheme == "https" || (uri.scheme == "http" && local))) { "远端地址必须使用 HTTPS，局域网 IP 可用 HTTP" }
        require(config.username.isEmpty() || uri.scheme == "https" || host == "localhost" || host.startsWith("127.")) { "带账号的 WebDAV 需要 HTTPS" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (config.username.isNotEmpty()) connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8)))
            if (etag.isNotEmpty()) connection.setRequestProperty("If-Match", etag)
            if (createOnly) connection.setRequestProperty("If-None-Match", "*")
            if (config.provider == "s3") {
                val conditional = if (createOnly) mapOf("If-None-Match" to "*") else if (etag.isNotEmpty()) mapOf("If-Match" to etag) else emptyMap()
                S3Signer.sign(method, address, config, data ?: ByteArray(0), conditional).forEach { (key, value) -> connection.setRequestProperty(key, value) }
            }
            if (data != null) {
                require(data.size <= PortableCrypto.MAX_BYTES)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            val body = if (status == 200 && method == "GET") connection.inputStream.use { readLimited(it) } else ByteArray(0)
            return Response(status, connection.getHeaderField("ETag").orEmpty(), body)
        } finally { connection.disconnect() }
    }
}
