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

/**
 * One page of treatments. [rawSize] counts every record the server returned, including
 * records that [treatments] leaves out because they have no `created_at` or `eventType`.
 * Paging compares [rawSize] with the page size, so a malformed record does not end
 * paging early.
 */
class TreatmentPage(val treatments: List<Treatment>, val rawSize: Int, val oldestCreatedAt: Long?)

@Singleton
open class NightscoutClient @Inject constructor() {

    companion object {
        private const val HTTP_NOT_FOUND = 404
        const val MAX_ERROR_LENGTH = 80
        // AndroidAPS pages treatments in 500s, below the 1000-record cap of Nightscout's v3 API.
        private const val TREATMENT_PAGE_SIZE = 500
        private const val ENTRIES_PAGE_SIZE = 2016
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

    private var client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * Test seam: replaces the production CIO client with a [MockEngine]-backed one.
     * The default client is constructed by the primary constructor and immediately
     * closed here so its engine threads/selectors don't leak per test run.
     */
    internal constructor(testClient: HttpClient) : this() {
        client.close()
        client = testClient
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

        return withNetworkBoundary(
            label = "Connection test",
            onError = { ConnectionTestResult(false, error = it.message?.take(MAX_ERROR_LENGTH) ?: "Connection failed") }
        ) {
            val response = client.get(statusUrl) { header("api-secret", hashedSecret) }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val name = try {
                    Json.parseToJsonElement(body)
                        .jsonObject["settings"]
                        ?.jsonObject?.get("customTitle")
                        ?.jsonPrimitive?.contentOrNull
                } catch (_: SerializationException) { null }
                  catch (_: IllegalArgumentException) { null }
                ConnectionTestResult(true, serverName = name)
            } else {
                ConnectionTestResult(false, error = "HTTP ${response.status.value}")
            }
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

        return withNetworkBoundary(label = "Push", onError = { false }) {
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
        }
    }

    open suspend fun fetchEntries(
        baseUrl: String,
        apiSecret: String,
        since: Long,
        count: Int = ENTRIES_PAGE_SIZE,
        before: Long? = null
    ): List<NightscoutEntryResponse>? {
        if (baseUrl.isBlank() || apiSecret.isBlank()) return emptyList()

        val hashedSecret = hashSecret(apiSecret)
        val fullUrl = buildFetchUrl(baseUrl, since, count, before)

        return withNetworkBoundary<List<NightscoutEntryResponse>?>(label = "Fetch", onError = { null }) {
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
        }
    }

    /**
     * Fetches every entry newer than [since], one page at a time. Nightscout returns
     * entries newest first, so the first page holds the newest entries and each next
     * page asks for entries older than the oldest entry seen so far. Pages
     * reach [onPage] newest first. Returns false when a fetch fails, or when a page holds
     * nothing older than the previous page, which means the server ignored `before`.
     */
    suspend fun forEachEntryPage(
        baseUrl: String,
        apiSecret: String,
        since: Long,
        onPage: suspend (List<NightscoutEntryResponse>) -> Unit,
    ): Boolean {
        var before: Long? = null
        while (true) {
            val page = fetchEntries(baseUrl, apiSecret, since, ENTRIES_PAGE_SIZE, before) ?: return false
            if (page.isNotEmpty()) onPage(page)
            val oldest = page.mapNotNull { it.date }.minOrNull()
            if (page.size < ENTRIES_PAGE_SIZE || oldest == null) return true
            if (before != null && oldest >= before) return false
            before = oldest
        }
    }

    /**
     * Fetches every treatment created at or after [since], one page at a time, and hands
     * each page to [onPage]. Nightscout's v1 API has no upper limit on `count`, so a single
     * request for months of a looping user's treatments returns tens of megabytes and runs
     * into the request timeout. Nightscout returns treatments newest first, so each next page
     * asks for treatments created at or before the oldest one seen so far. The bound is
     * inclusive because several treatments can share a `created_at`, and `$lt` would skip
     * the ones beyond the page edge. Callers upsert by id, so the repeated boundary rows are
     * harmless. Throws when a fetch fails, or when a full page holds nothing older than the
     * previous page, which means the server ignored the bound.
     */
    suspend fun forEachTreatmentPage(
        baseUrl: String,
        secret: String,
        since: Long,
        onPage: suspend (List<Treatment>) -> Unit,
    ) {
        var before: Long? = null
        while (true) {
            val page = fetchTreatmentPage(baseUrl, secret, since, TREATMENT_PAGE_SIZE, before)
            if (page.treatments.isNotEmpty()) onPage(page.treatments)
            val oldest = page.oldestCreatedAt
            if (page.rawSize < TREATMENT_PAGE_SIZE || oldest == null) return
            if (before != null && oldest >= before) {
                throw IOException("Treatment paging made no progress at created_at $oldest")
            }
            before = oldest
        }
    }

    open suspend fun fetchTreatmentPage(
        baseUrl: String,
        secret: String,
        since: Long,
        count: Int,
        before: Long?
    ): TreatmentPage {
        if (baseUrl.isBlank() || secret.isBlank()) return TreatmentPage(emptyList(), 0, null)

        val hashedSecret = hashSecret(secret)
        val sinceIso = isoFormatter.format(Instant.ofEpochMilli(since))
        val beforeParam = before?.let { "&find[created_at][\$lte]=${isoFormatter.format(Instant.ofEpochMilli(it))}" } ?: ""
        val fullUrl = "${normalizeUrl(baseUrl)}/api/v1/treatments.json" +
            "?find[created_at][\$gte]=$sinceIso$beforeParam&count=$count"

        return withNetworkBoundary<TreatmentPage>(label = "Treatments fetch", onError = { throw it }) {
            val response = client.get(fullUrl) { header("api-secret", hashedSecret) }

            if (response.status.value == HTTP_NOT_FOUND) {
                com.psjostrom.strimma.receiver.DebugLog.log(
                    message = "Treatments: 404 — server doesn't support treatments"
                )
                return@withNetworkBoundary TreatmentPage(emptyList(), 0, null)
            }
            if (!response.status.isSuccess()) {
                // IOException message is short ("HTTP 500") so callers can pattern-match on it;
                // the helper logs class name + message, so URL detail goes in a separate log line.
                com.psjostrom.strimma.receiver.DebugLog.log(
                    message = "Treatments HTTP ${response.status.value}: $fullUrl"
                )
                throw IOException("HTTP ${response.status.value}")
            }

            val nsTreatments = response.body<List<NightscoutTreatment>>()
            val now = System.currentTimeMillis()
            val oldest = nsTreatments.mapNotNull { ns -> ns.createdAt?.let(::parseIsoTimestamp) }.minOrNull()
            val treatments = nsTreatments.mapNotNull { ns ->
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
            TreatmentPage(treatments, nsTreatments.size, oldest)
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
