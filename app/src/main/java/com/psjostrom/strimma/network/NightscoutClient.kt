package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.Treatment
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class NightscoutEntry(
    val sgv: Int,
    val date: Long,
    val dateString: String,
    val direction: String,
    val delta: Double? = null,
    val type: String = "sgv",
    val device: String = "Strimma"
)

@Serializable
data class NightscoutEntryResponse(
    val sgv: Int? = null,
    val date: Long? = null,
    val type: String? = null,
    val direction: String? = null,
    val delta: Double? = null
)

@Serializable
data class NightscoutTreatment(
    @SerialName("_id") val id: String? = null,
    val eventType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val insulin: Double? = null,
    val carbs: Double? = null,
    val absolute: Double? = null,
    val duration: Int? = null,
    val enteredBy: String? = null
)

@Singleton
open class NightscoutClient @Inject constructor() {

    companion object {
        private const val HTTP_NOT_FOUND = 404
        const val MAX_ERROR_LENGTH = 80
        private const val DEFAULT_TREATMENT_COUNT = 100
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L

        val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)

        fun sha1Hex(input: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        fun buildFetchUrl(baseUrl: String, since: Long, count: Int, before: Long? = null): String {
            val normalized = normalizeUrl(baseUrl)
            val base = "$normalized/api/v1/entries.json?find[date][\$gt]=$since&count=$count"
            return if (before != null) "$base&find[date][\$lt]=$before" else base
        }
    }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val isoFormatter get() = ISO_FORMATTER

    data class ConnectionTestResult(
        val success: Boolean,
        val serverName: String? = null,
        val error: String? = null
    )

    suspend fun testConnection(baseUrl: String, apiSecret: String): ConnectionTestResult {
        if (baseUrl.isBlank()) return ConnectionTestResult(false, error = "URL is empty")
        if (apiSecret.isBlank()) return ConnectionTestResult(false, error = "API secret is empty")

        val hashedSecret = hashSecret(apiSecret)
        val statusUrl = "${normalizeUrl(baseUrl)}/api/v1/status.json"

        return try {
            val response = client.get(statusUrl) { header("api-secret", hashedSecret) }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val name = try {
                    Json.parseToJsonElement(body)
                        .jsonObject["settings"]
                        ?.jsonObject?.get("customTitle")
                        ?.jsonPrimitive?.contentOrNull
                } catch (_: kotlinx.serialization.SerializationException) { null }
                  catch (_: IllegalArgumentException) { null }
                ConnectionTestResult(true, serverName = name)
            } else {
                ConnectionTestResult(false, error = "HTTP ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Connection test error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            ConnectionTestResult(false, error = e.message?.take(MAX_ERROR_LENGTH) ?: "Connection failed")
        } catch (e: SerializationException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Connection test parse error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            ConnectionTestResult(false, error = e.message?.take(MAX_ERROR_LENGTH) ?: "Connection failed")
        }
    }

    open suspend fun pushReadings(
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
                dateString = isoFormatter.format(Instant.ofEpochMilli(reading.ts)),
                direction = reading.direction,
                delta = reading.delta
            )
        }

        return try {
            val fullUrl = "${normalizeUrl(baseUrl)}/api/v1/entries"
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Push error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            false
        } catch (e: SerializationException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Push serialize error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            false
        }
    }

    open suspend fun fetchEntries(
        baseUrl: String,
        apiSecret: String,
        since: Long,
        count: Int = 2016,
        before: Long? = null
    ): List<NightscoutEntryResponse>? {
        if (baseUrl.isBlank() || apiSecret.isBlank()) return emptyList()

        val hashedSecret = hashSecret(apiSecret)
        val fullUrl = buildFetchUrl(baseUrl, since, count, before)

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Fetch error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            null
        } catch (e: SerializationException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Fetch parse error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            null
        }
    }

    open suspend fun fetchTreatments(
        baseUrl: String,
        secret: String,
        since: Long,
        count: Int = DEFAULT_TREATMENT_COUNT
    ): List<Treatment> {
        if (baseUrl.isBlank() || secret.isBlank()) return emptyList()

        val hashedSecret = hashSecret(secret)
        val sinceIso = isoFormatter.format(Instant.ofEpochMilli(since))
        val fullUrl = "${normalizeUrl(baseUrl)}/api/v1/treatments.json?find[created_at][\$gte]=$sinceIso&count=$count"

        return try {
            val response = client.get(fullUrl) {
                header("api-secret", hashedSecret)
            }
            if (response.status.value == HTTP_NOT_FOUND) {
                com.psjostrom.strimma.receiver.DebugLog.log(
                    message = "Treatments: 404 — server doesn't support treatments"
                )
                return emptyList()
            }
            if (!response.status.isSuccess()) {
                com.psjostrom.strimma.receiver.DebugLog.log(
                    message = "Treatments HTTP ${response.status.value}: $fullUrl"
                )
                return emptyList()
            }
            val nsTreatments = response.body<List<NightscoutTreatment>>()
            val now = System.currentTimeMillis()
            nsTreatments.mapNotNull { ns ->
                val createdAtStr = ns.createdAt ?: return@mapNotNull null
                val eventType = ns.eventType ?: return@mapNotNull null
                val createdAtMs = parseIsoTimestamp(createdAtStr) ?: return@mapNotNull null
                val id = ns.id ?: generateTreatmentId(createdAtStr, eventType, ns.insulin, ns.carbs)
                Treatment(
                    id = id,
                    createdAt = createdAtMs,
                    eventType = eventType,
                    insulin = ns.insulin,
                    carbs = ns.carbs,
                    basalRate = ns.absolute,
                    duration = ns.duration,
                    enteredBy = ns.enteredBy,
                    fetchedAt = now
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Treatments fetch error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            emptyList()
        } catch (e: SerializationException) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Treatments parse error: ${e.message?.take(MAX_ERROR_LENGTH)}"
            )
            emptyList()
        }
    }

    private fun parseIsoTimestamp(iso: String): Long? {
        return try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.from(isoFormatter.parse(iso)).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun generateTreatmentId(createdAt: String, eventType: String, insulin: Double?, carbs: Double?): String =
        sha1Hex("$createdAt|$eventType|$insulin|$carbs")

    private fun hashSecret(secret: String): String = sha1Hex(secret)
}
