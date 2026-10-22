package com.openbrowser.mobile.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class PortableCryptoTest {
    private fun fixture(): List<String> = checkNotNull(javaClass.getResourceAsStream("/portable-sync-vector.txt"))
        .bufferedReader(Charsets.UTF_8).use { it.readLines() }

    @Test
    fun decryptsDesktopVectorWithUnicodePassword() {
        val lines = fixture()
        assertEquals(lines[2], String(PortableCrypto.open(Base64.getDecoder().decode(lines[1]), lines[0]), Charsets.UTF_8))
    }

    @Test
    fun roundTripUsesFreshNonceAndAuthenticatesContents() {
        val plain = "跨端 Cookie 与指纹".toByteArray(Charsets.UTF_8)
        val password = "portable-test-password"
        val a = PortableCrypto.seal(plain, password)
        val b = PortableCrypto.seal(plain, password)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain, PortableCrypto.open(a, password))
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { PortableCrypto.open(a, password) }
        assertThrows(Exception::class.java) { PortableCrypto.open(b, "different-password") }
    }

    @Test
    fun rejectsShortPasswordsInvalidHeadersAndOversizedFiles() {
        assertThrows(IllegalArgumentException::class.java) { PortableCrypto.seal(byteArrayOf(1), "short") }
        assertThrows(IllegalArgumentException::class.java) { PortableCrypto.open(ByteArray(48), "portable-test-password") }
        assertThrows(IllegalArgumentException::class.java) { PortableCrypto.open(ByteArray(PortableCrypto.MAX_BYTES + 1), "portable-test-password") }
    }
}
