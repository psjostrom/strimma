package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.Treatment
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

@Serializable
data class NightscoutTreatment(
    val _id: String? = null,
    val eventType: String? = null,
    val created_at: String? = null,
    val insulin: Double? = null,
    val carbs: Double? = null,
    val absolute: Double? = null,
    val duration: Int? = null,
    val enteredBy: String? = null
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

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

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
                dateString = isoFormatter.format(Instant.ofEpochMilli(reading.ts)),
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

    suspend fun fetchTreatments(
        baseUrl: String,
        secret: String,
        since: Long
    ): List<Treatment> {
        if (baseUrl.isBlank() || secret.isBlank()) return emptyList()

        val hashedSecret = hashSecret(secret)
        val sinceIso = isoFormatter.format(Instant.ofEpochMilli(since))
        // count=100 is sufficient for bolus/carb events in 6h. Aggressive looping systems
        // may generate more temp basals, but those aren't rendered on the graph.
        val fullUrl = "${baseUrl.trimEnd('/')}/api/v1/treatments.json?find[created_at][\$gte]=$sinceIso&count=100"

        return try {
            val response = client.get(fullUrl) {
                header("api-secret", hashedSecret)
            }
            if (response.status.value == 404) {
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
                val createdAtStr = ns.created_at ?: return@mapNotNull null
                val eventType = ns.eventType ?: return@mapNotNull null
                val createdAtMs = parseIsoTimestamp(createdAtStr) ?: return@mapNotNull null
                val id = ns._id ?: generateTreatmentId(createdAtStr, eventType, ns.insulin, ns.carbs)
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
        } catch (e: Exception) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Treatments fetch error: ${e.message?.take(80)}"
            )
            emptyList()
        }
    }

    private fun parseIsoTimestamp(iso: String): Long? {
        return try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.from(isoFormatter.parse(iso)).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun generateTreatmentId(createdAt: String, eventType: String, insulin: Double?, carbs: Double?): String {
        val raw = "$createdAt|$eventType|$insulin|$carbs"
        return MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun hashSecret(secret: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
