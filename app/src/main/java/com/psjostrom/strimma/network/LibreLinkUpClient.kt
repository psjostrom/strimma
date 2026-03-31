package com.psjostrom.strimma.network

import com.psjostrom.strimma.receiver.DebugLog
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LluLoginRequest(val email: String, val password: String)

@Serializable
data class LluAuthTicket(val token: String = "", val expires: Long = 0)

@Serializable
data class LluUser(val id: String = "")

@Serializable
data class LluLoginData(
    val authTicket: LluAuthTicket = LluAuthTicket(),
    val user: LluUser = LluUser(),
    val redirect: Boolean = false,
    val region: String = ""
)

@Serializable
data class LluLoginResponse(val status: Int = 0, val data: LluLoginData = LluLoginData())

@Serializable
data class LluConnection(
    val patientId: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val glucoseMeasurement: LluGlucoseItem? = null
)

@Serializable
data class LluConnectionsResponse(val status: Int = 0, val data: List<LluConnection> = emptyList())

@Serializable
data class LluGlucoseItem(
    @SerialName("ValueInMgPerDl") val valueInMgPerDl: Int = 0,
    @SerialName("TrendArrow") val trendArrow: Int = 0,
    @SerialName("Timestamp") val timestamp: String = "",
    @SerialName("FactoryTimestamp") val factoryTimestamp: String = "",
    val isHigh: Boolean = false,
    val isLow: Boolean = false,
    @SerialName("MeasurementColor") val measurementColor: Int = 0
)

@Serializable
data class LluGraphData(
    val connection: LluConnection = LluConnection(),
    val graphData: List<LluGlucoseItem> = emptyList(),
    val activeSensors: List<LluActiveSensor> = emptyList()
)

@Serializable
data class LluActiveSensor(val sensor: LluSensor = LluSensor())

@Serializable
data class LluSensor(val sn: String = "")

@Serializable
data class LluGraphResponse(val status: Int = 0, val data: LluGraphData = LluGraphData())

@Serializable
data class LluRegionDef(val lslApi: String = "")

@Serializable
data class LluRegionalMap(
    val ap: LluRegionDef = LluRegionDef(),
    val au: LluRegionDef = LluRegionDef(),
    val ca: LluRegionDef = LluRegionDef(),
    val de: LluRegionDef = LluRegionDef(),
    val eu: LluRegionDef = LluRegionDef(),
    val eu2: LluRegionDef = LluRegionDef(),
    val fr: LluRegionDef = LluRegionDef(),
    val jp: LluRegionDef = LluRegionDef(),
    val us: LluRegionDef = LluRegionDef(),
    val llu: LluRegionDef = LluRegionDef()
)

@Serializable
data class LluCountryData(val regionalMap: LluRegionalMap = LluRegionalMap())

@Serializable
data class LluCountryResponse(val status: Int = 0, val data: LluCountryData = LluCountryData())

data class LluSession(val token: String, val accountId: String, val baseUrl: String)

@Singleton
class LibreLinkUpClient @Inject constructor() {

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.libreview.io"
        private const val CLIENT_VERSION = "4.16.0"
        private const val PRODUCT = "llu.android"
        private const val STATUS_BAD_CREDENTIALS = 2
        private const val STATUS_ACTION_REQUIRED = 4
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    private fun lluHeaders(session: LluSession? = null): HeadersBuilder.() -> Unit = {
        append("product", PRODUCT)
        append("version", CLIENT_VERSION)
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        append(HttpHeaders.CacheControl, "no-cache")
        append(HttpHeaders.Connection, "Keep-Alive")
        if (session != null) {
            append(HttpHeaders.Authorization, "Bearer ${session.token}")
            append("Account-Id", sha256(session.accountId))
        }
    }

    @Suppress("ReturnCount") // Auth flow has multiple distinct failure modes
    suspend fun login(
        email: String,
        password: String,
        baseUrl: String = DEFAULT_BASE_URL,
        allowRedirect: Boolean = true
    ): LluSession? {
        return try {
            val response = client.post("$baseUrl/llu/auth/login") {
                headers(lluHeaders())
                setBody(LluLoginRequest(email, password))
            }

            val loginResponse = response.body<LluLoginResponse>()
            parseLoginResponse(loginResponse, email, password, baseUrl, allowRedirect)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            DebugLog.log(message = "LLU login error: ${e.message?.take(NightscoutClient.MAX_ERROR_LENGTH)}")
            null
        }
    }

    private suspend fun parseLoginResponse(
        loginResponse: LluLoginResponse,
        email: String,
        password: String,
        baseUrl: String,
        allowRedirect: Boolean
    ): LluSession? {
        if (loginResponse.status == STATUS_BAD_CREDENTIALS) {
            DebugLog.log(message = "LLU: bad credentials")
            return null
        }
        if (loginResponse.status == STATUS_ACTION_REQUIRED) {
            DebugLog.log(message = "LLU: account action required (terms/verification)")
            return null
        }

        val data = loginResponse.data
        if (data.redirect && data.region.isNotBlank() && allowRedirect) {
            DebugLog.log(message = "LLU: redirecting to region ${data.region}")
            val regionUrl = resolveRegionUrl(baseUrl, data.region)
            if (regionUrl != null) return login(email, password, regionUrl, allowRedirect = false)
            DebugLog.log(message = "LLU: unknown region ${data.region}")
            return null
        }

        if (data.authTicket.token.isBlank()) {
            DebugLog.log(message = "LLU: no auth ticket in response")
            return null
        }

        return LluSession(
            token = data.authTicket.token,
            accountId = data.user.id,
            baseUrl = baseUrl
        )
    }

    suspend fun getConnections(session: LluSession): List<LluConnection>? {
        return try {
            val response = client.get("${session.baseUrl}/llu/connections") {
                headers(lluHeaders(session))
            }
            if (!response.status.isSuccess()) {
                DebugLog.log(message = "LLU connections HTTP ${response.status.value}")
                return null
            }
            response.body<LluConnectionsResponse>().data
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            DebugLog.log(message = "LLU connections error: ${e.message?.take(NightscoutClient.MAX_ERROR_LENGTH)}")
            null
        }
    }

    suspend fun getGraph(session: LluSession, patientId: String): LluGraphData? {
        return try {
            val response = client.get("${session.baseUrl}/llu/connections/$patientId/graph") {
                headers(lluHeaders(session))
            }
            if (!response.status.isSuccess()) {
                DebugLog.log(message = "LLU graph HTTP ${response.status.value}")
                return null
            }
            response.body<LluGraphResponse>().data
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            DebugLog.log(message = "LLU graph error: ${e.message?.take(NightscoutClient.MAX_ERROR_LENGTH)}")
            null
        }
    }

    private suspend fun resolveRegionUrl(baseUrl: String, region: String): String? {
        return try {
            val response = client.get("$baseUrl/llu/config/country?country=DE") {
                headers(lluHeaders())
            }
            val countryResponse = response.body<LluCountryResponse>()
            val map = countryResponse.data.regionalMap
            val regionDef = when (region.lowercase()) {
                "ap" -> map.ap
                "au" -> map.au
                "ca" -> map.ca
                "de" -> map.de
                "eu" -> map.eu
                "eu2" -> map.eu2
                "fr" -> map.fr
                "jp" -> map.jp
                "us" -> map.us
                "llu" -> map.llu
                else -> null
            }
            regionDef?.lslApi?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            DebugLog.log(message = "LLU region resolve error: ${e.message?.take(NightscoutClient.MAX_ERROR_LENGTH)}")
            null
        }
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
