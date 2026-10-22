package com.openbrowser.mobile.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 自动同步凭据、合并基线、冲突摘要和记录统一加密；从不在 SharedPreferences 保存密码。 */
class SyncStore(context: Context, private val namespace: String = "settings") {
    init { require(namespace.matches(Regex("[A-Za-z0-9_-]{1,160}"))) }
    private val aad = if (namespace == "settings") AAD else ("openbrowser-sync-settings-v1:" + namespace).toByteArray(Charsets.UTF_8)
    private val directory = File(context.applicationContext.filesDir, "sync_state").apply { mkdirs() }
    private val file = AtomicFile(File(directory, "$namespace.enc"))
    fun read(): JSONObject = locked { load() }
    fun update(block: (JSONObject) -> Unit) = locked {
        val root = load(); block(root)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= PortableCrypto.MAX_BYTES - 28) { "网站存储或同步设置超过容量限制" }
        val encrypted = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun clear() = locked { file.delete() }
    private fun load(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject().put("config", JSONObject().put("enabled", false).put("space", "default").put("provider", "webdav").put("interval", 15))
        val encrypted = file.openRead().use { PortableTransport.readLimited(it) }
        require(encrypted.size >= 28) { "同步设置已损坏，未覆盖原文件" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12))); updateAAD(aad) }
        val plain = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun <T> locked(block: () -> T): T = synchronized(LOCK) { RandomAccessFile(File(directory, "$namespace.lock"), "rw").use { it.channel.lock().use { block() } } }
    companion object {
        private val LOCK = Any()
        private const val ALIAS = "openbrowser-sync-settings-v1"
        private val AAD = "openbrowser-sync-settings-v1".toByteArray(Charsets.UTF_8)
    }
}
