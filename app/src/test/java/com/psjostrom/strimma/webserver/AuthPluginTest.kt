package com.psjostrom.strimma.webserver

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class AuthPluginTest {

    private fun hashSecret(secret: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `loopback IPv4 skips auth`() {
        assertTrue(isLoopback("127.0.0.1"))
    }

    @Test
    fun `loopback IPv6 skips auth`() {
        assertTrue(isLoopback("::1"))
        assertTrue(isLoopback("0:0:0:0:0:0:0:1"))
    }

    @Test
    fun `non-loopback requires auth`() {
        assertFalse(isLoopback("192.168.1.10"))
        assertFalse(isLoopback("10.0.0.1"))
    }

    @Test
    fun `valid secret matches`() {
        val secret = "my-test-secret"
        val hashed = hashSecret(secret)
        assertTrue(checkApiSecret(hashed, secret))
    }

    @Test
    fun `wrong secret rejected`() {
        val hashed = hashSecret("wrong-secret")
        assertFalse(checkApiSecret(hashed, "my-secret"))
    }
}
