package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.call.*
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

@Serializable
data class NightscoutEntryResponse(
    val sgv: Int? = null,
    val date: Long? = null,
    val type: String? = null,
    val direction: String? = null
)

@Singleton
class NightscoutClient @Inject constructor() {

    companion object {
        fun buildFetchUrl(baseUrl: String, since: Long, count: Int): String {
            return "${baseUrl.trimEnd('/')}/api/v1/entries.json?find[date][\$gt]=$since&count=$count"
        }
    }

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
            val fullUrl = "${baseUrl.trimEnd('/')}/api/v1/entries"
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

    suspend fun fetchEntries(
        baseUrl: String,
        apiSecret: String,
        since: Long,
        count: Int = 2016
    ): List<NightscoutEntryResponse>? {
        if (baseUrl.isBlank() || apiSecret.isBlank()) return emptyList()

        val hashedSecret = hashSecret(apiSecret)
        val fullUrl = buildFetchUrl(baseUrl, since, count)

        return try {
            val response = client.get(fullUrl) {
                header("api-secret", hashedSecret)
            }
            if (!response.status.isSuccess()) {
                com.psjostrom.strimma.receiver.DebugLog.log(
                    message = "Fetch HTTP ${response.status.value}: $fullUrl"
                )
                null
            } else {
                response.body<List<NightscoutEntryResponse>>()
            }
        } catch (e: Exception) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Fetch error: ${e.message?.take(80)}"
            )
            null
        }
    }

    private fun hashSecret(secret: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
