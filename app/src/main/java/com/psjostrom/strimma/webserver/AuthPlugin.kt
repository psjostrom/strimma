package com.psjostrom.strimma.webserver

import java.net.InetAddress
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)

fun isLoopback(remoteHost: String): Boolean {
    return try {
        InetAddress.getByName(remoteHost).isLoopbackAddress
    } catch (_: Exception) {
        false
    }
}

fun checkApiSecret(headerValue: String, serverSecret: String): Boolean {
    val expected = MessageDigest.getInstance("SHA-1")
        .digest(serverSecret.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return headerValue == expected
}
