package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.network.NightscoutClient
import java.net.InetAddress
import java.time.format.DateTimeFormatter

val ISO_FORMATTER: DateTimeFormatter get() = NightscoutClient.ISO_FORMATTER

fun isLoopback(remoteHost: String): Boolean {
    return try {
        InetAddress.getByName(remoteHost).isLoopbackAddress
    } catch (_: Exception) {
        false
    }
}

fun checkApiSecret(headerValue: String, serverSecret: String): Boolean =
    headerValue == NightscoutClient.sha1Hex(serverSecret)
