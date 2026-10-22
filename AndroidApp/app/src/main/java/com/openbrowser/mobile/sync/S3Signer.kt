package com.openbrowser.mobile.sync

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AWS SigV4，支持 MinIO 等 S3 兼容端点的完整对象 URL。 */
object S3Signer {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun mac(key: ByteArray, text: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray(Charsets.UTF_8))
    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    fun sign(method: String, address: String, config: SyncEndpoint, data: ByteArray = ByteArray(0), extra: Map<String, String> = emptyMap(), at: Instant = Instant.now()): Map<String, String> {
        val uri = URI(address)
        require(uri.rawQuery == null && uri.fragment == null && uri.userInfo == null && !uri.host.isNullOrBlank()) { "S3 签名需要无查询参数的完整对象 URL" }
        require(config.region.matches(Regex("[A-Za-z0-9-]{1,64}")) && config.accessKey.isNotEmpty() && config.secretKey.isNotEmpty()) { "请填写 S3 Region 和访问密钥" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC).format(at)
        val day = stamp.take(8)
        val host = uri.host.lowercase(Locale.ROOT) + if (uri.port >= 0 && !((uri.scheme == "https" && uri.port == 443) || (uri.scheme == "http" && uri.port == 80))) ":${uri.port}" else ""
        val headers = extra.mapKeys { it.key.lowercase(Locale.ROOT) }.mapValues { it.value.trim().replace(Regex("\\s+"), " ") }.toMutableMap()
        headers["host"] = host; headers["x-amz-date"] = stamp; headers["x-amz-content-sha256"] = sha(data)
        if (config.sessionToken.isNotEmpty()) headers["x-amz-security-token"] = config.sessionToken
        val names = headers.keys.sorted(); val signed = names.joinToString(";")
        val path = uri.rawPath.orEmpty().split('/').joinToString("/") { encode(URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")) }.ifEmpty { "/" }
        val canonical = listOf(method, path, "", names.joinToString("") { "$it:${headers[it]}\n" }, signed, sha(data)).joinToString("\n")
        val scope = "$day/${config.region}/s3/aws4_request"
        val text = listOf("AWS4-HMAC-SHA256", stamp, scope, sha(canonical.toByteArray(Charsets.UTF_8))).joinToString("\n")
        var key = ("AWS4" + config.secretKey).toByteArray(Charsets.UTF_8)
        for (part in listOf(day, config.region, "s3", "aws4_request")) { val next = mac(key, part); key.fill(0); key = next }
        val signature = try { mac(key, text).joinToString("") { "%02x".format(it.toInt() and 255) } } finally { key.fill(0) }
        headers["Authorization"] = "AWS4-HMAC-SHA256 Credential=${config.accessKey}/$scope,SignedHeaders=$signed,Signature=$signature"
        return headers
    }
}
