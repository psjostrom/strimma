package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class NightscoutEntry(
    val sgv: Int,
    val date: Long,
    val dateString: String,
    val direction: String,
    val type: String = "sgv",
    val device: String = "Strimma"
)

@Singleton
class SpringaClient @Inject constructor() {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun pushReadings(
        baseUrl: String,
        apiSecret: String,
        readings: List<GlucoseReading>
    ): Boolean {
        if (baseUrl.isBlank() || apiSecret.isBlank() || readings.isEmpty()) return false

        val hashedSecret = hashSecret(apiSecret)
        val entries = readings.map { reading ->
            NightscoutEntry(
                sgv = reading.sgv,
                date = reading.ts,
                dateString = dateFormat.format(Date(reading.ts)),
                direction = reading.direction
            )
        }

        return try {
            val fullUrl = "${baseUrl.trimEnd('/')}/api/v1/strimma/entries"
            val response = client.post(fullUrl) {
                contentType(ContentType.Application.Json)
                header("api-secret", hashedSecret)
                setBody(entries)
            }
            if (!response.status.isSuccess()) {
                com.psjostrom.strimma.receiver.DebugLog.log(
                    message = "Push HTTP ${response.status.value}: $fullUrl"
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Push error: ${e.message?.take(80)}"
            )
            false
        }
    }

    private fun hashSecret(secret: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
