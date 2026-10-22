package com.openbrowser.mobile.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 仅保存用户主动生成/导入的快照。设备上的 Cookie 快照以 Android Keystore 密钥加密。 */
class CookieSnapshots(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "cookie_snapshots").apply { mkdirs() }

    fun save(id: String, cookies: JSONArray) = write(id, "saved", cookies)
    fun queue(id: String, cookies: JSONArray) = write(id, "pending", cookies)
    fun queueKnown(id: String, cookies: JSONArray) = locked {
        fun key(record: JSONObject): String {
            val value = SyncMerge.cookieValue(record)
            return listOf("name", "host", "domain", "path").joinToString("|") { value.get(it).toString() }
        }
        val newKeys = (0 until cookies.length()).map { key(cookies.getJSONObject(it)) }.toSet()
        val known = mutableMapOf<String, JSONObject>()
        listOfNotNull(read(id, "saved"), read(id, "pending")).forEach { snapshot ->
            listOf("cookies", "deletions").forEach { field ->
                val array = snapshot.optJSONArray(field) ?: JSONArray()
                for (i in 0 until array.length()) { val record = array.getJSONObject(i); known[key(record)] = record }
            }
        }
        val deletions = JSONArray()
        known.filterKeys { it !in newKeys }.values.forEach { record ->
            val parts = record.getString("setCookie").split(';').map { it.trim() }
            val attributes = parts.drop(1).filter { it.substringBefore('=').lowercase() !in listOf("expires", "max-age") }
            deletions.put(JSONObject().put("url", record.getString("url")).put("setCookie", (listOf(parts.first().substringBefore('=') + "=") + attributes + "Max-Age=0").joinToString("; ")))
        }
        writeUnlocked(id, "pending", cookies, deletions)
    }
    fun pending(id: String): JSONObject? = locked { read(id, "pending") }
    fun export(id: String): JSONArray = locked {
        (read(id, "pending") ?: read(id, "saved"))?.getJSONArray("cookies") ?: JSONArray()
    }

    fun applied(id: String, snapshot: JSONObject) = locked {
        if (read(id, "pending")?.optString("revision") == snapshot.optString("revision")) file(id, "pending").delete()
    }

    fun clear(id: String) = locked { file(id, "saved").delete(); file(id, "pending").delete() }

    fun clearAll() = locked {
        directory.listFiles()?.filter { it.name != "snapshots.lock" }?.forEach { AtomicFile(it).delete() }
    }

    private fun write(id: String, kind: String, cookies: JSONArray) = locked {
        writeUnlocked(id, kind, cookies, JSONArray())
    }
    private fun writeUnlocked(id: String, kind: String, cookies: JSONArray, deletions: JSONArray) {
        val root = JSONObject().put("revision", java.util.UUID.randomUUID().toString()).put("capturedAt", System.currentTimeMillis()).put("cookies", cookies).put("deletions", deletions)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("$id:$kind".toByteArray(Charsets.UTF_8))
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        if (plain.size > PortableCrypto.MAX_BYTES - 28) { plain.fill(0); error("Cookie 快照超过容量限制，请减少快照范围后重试") }
        val data = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val target = file(id, kind)
        val output = target.startWrite()
        try { output.write(data); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }

    private fun read(id: String, kind: String): JSONObject? {
        val target = file(id, kind)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        val data = target.openRead().use { PortableTransport.readLimited(it) }
        require(data.size >= 28) { "Cookie 快照已损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD("$id:$kind".toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(data.copyOfRange(12, data.size))
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }

    private fun file(id: String, kind: String): AtomicFile {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "环境 ID 无效" }
        return AtomicFile(File(directory, "$id.$kind"))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        RandomAccessFile(File(directory, "snapshots.lock"), "rw").use { handle -> handle.channel.lock().use { block() } }
    }

    companion object {
        private val LOCK = Any()
        private const val ALIAS = "openbrowser-cookie-snapshots-v1"
    }
}
