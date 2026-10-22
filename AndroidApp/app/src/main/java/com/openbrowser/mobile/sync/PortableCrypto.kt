package com.openbrowser.mobile.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** OBS1 | salt(16) | nonce(12) | AES-GCM ciphertext | tag(16). 与桌面端一致。 */
object PortableCrypto {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val magic = "OBS1".toByteArray(Charsets.US_ASCII)

    fun seal(plain: ByteArray, password: String): ByteArray {
        require(plain.size <= MAX_BYTES - 48) { "交换文件超过 8 MB" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return magic + salt + iv + crypt(Cipher.ENCRYPT_MODE, plain, password, salt, iv)
    }

    fun open(data: ByteArray, password: String): ByteArray {
        require(data.size in 48..MAX_BYTES && data.copyOfRange(0, 4).contentEquals(magic)) { "无效或过大的交换文件" }
        return crypt(Cipher.DECRYPT_MODE, data.copyOfRange(32, data.size), password, data.copyOfRange(4, 20), data.copyOfRange(20, 32))
    }

    private fun crypt(mode: Int, data: ByteArray, password: String, salt: ByteArray, iv: ByteArray): ByteArray {
        require(password.length >= 12) { "同步密码至少 12 个字符" }
        val spec = PBEKeySpec(password.toCharArray(), salt, 210000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(magic)
            return cipher.doFinal(data)
        } finally { key.fill(0) }
    }
}
