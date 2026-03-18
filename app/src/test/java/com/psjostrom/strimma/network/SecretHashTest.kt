package com.psjostrom.strimma.network

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SecretHashTest {

    private fun hashSecret(secret: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `hash produces 40-char hex string`() {
        val hash = hashSecret("test-secret")
        assertEquals(40, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `hash is deterministic`() {
        val h1 = hashSecret("my-secret")
        val h2 = hashSecret("my-secret")
        assertEquals(h1, h2)
    }

    @Test
    fun `different inputs produce different hashes`() {
        val h1 = hashSecret("secret-a")
        val h2 = hashSecret("secret-b")
        assertNotEquals(h1, h2)
    }

    @Test
    fun `known SHA-1 vector`() {
        // SHA-1 of empty string is da39a3ee5e6b4b0d3255bfef95601890afd80709
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hashSecret(""))
    }
}
