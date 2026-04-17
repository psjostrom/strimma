package com.psjostrom.strimma.tidepool

import com.psjostrom.strimma.receiver.DebugLog
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for Tidepool Data API.
 * Handles dataset creation and CBG record uploads.
 */
@Singleton
class TidepoolClient @Inject constructor() {

    companion object {
        private const val MAX_ERROR_LENGTH = 80
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    // Test-only constructor
    internal constructor(testClient: HttpClient) : this() {
        httpClient = testClient
    }

    /**
     * Fetches the most recent existing dataset for this client.
     * Returns the uploadId if found, null otherwise.
     */
    suspend fun getExistingDataset(
        baseUrl: String,
        userId: String,
        token: String
    ): String? {
        if (baseUrl.isBlank() || userId.isBlank() || token.isBlank()) return null

        return try {
            val fullUrl = "${baseUrl.trimEnd('/')}/v1/users/$userId/data_sets?client.name=com.psjostrom.strimma&size=1"
            val response = httpClient.get(fullUrl) {
                header("x-tidepool-session-token", token)
            }

            if (!response.status.isSuccess()) {
                val body = response.bodyAsText().take(MAX_ERROR_LENGTH * 2)
                DebugLog.log(message = "Tidepool getExistingDataset HTTP ${response.status.value}: $body")
                return null
            }

            val datasets = response.body<List<DatasetResponse>>()
            datasets.firstOrNull()?.uploadId
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DebugLog.log(message = "Tidepool getExistingDataset error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            null
        } catch (e: SerializationException) {
            DebugLog.log(message = "Tidepool getExistingDataset parse error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            null
        }
    }

    /**
     * Creates a new dataset.
     * Returns the uploadId if successful, null otherwise.
     */
    suspend fun createDataset(
        baseUrl: String,
        userId: String,
        token: String,
        datasetRequest: DatasetRequest
    ): String? {
        if (baseUrl.isBlank() || userId.isBlank() || token.isBlank()) return null

        return try {
            val fullUrl = "${baseUrl.trimEnd('/')}/v1/users/$userId/data_sets"
            val requestBody = json.encodeToString(datasetRequest)
            DebugLog.log(message = "Tidepool createDataset body: $requestBody")

            val response = httpClient.post(fullUrl) {
                contentType(ContentType.Application.Json)
                header("x-tidepool-session-token", token)
                setBody(datasetRequest)
            }

            if (!response.status.isSuccess()) {
                val body = response.bodyAsText().take(MAX_ERROR_LENGTH * 2)
                DebugLog.log(message = "Tidepool createDataset HTTP ${response.status.value}: $body")
                return null
            }

            val responseText = response.bodyAsText()
            val uploadId = parseUploadId(responseText)
            DebugLog.log(message = "Tidepool createDataset success: uploadId=$uploadId")
            uploadId
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DebugLog.log(message = "Tidepool createDataset error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            null
        } catch (e: SerializationException) {
            DebugLog.log(message = "Tidepool createDataset parse error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            null
        }
    }

    /**
     * Parses uploadId from the create-dataset response.
     * Handles both envelope format {"data": {"uploadId": "..."}} and flat {"uploadId": "..."}.
     */
    private fun parseUploadId(responseText: String): String? {
        return try {
            val root = json.parseToJsonElement(responseText).jsonObject
            val obj = root["data"]?.jsonObject ?: root
            (obj["uploadId"] ?: obj["id"])?.toString()?.trim('"')
        } catch (e: SerializationException) {
            DebugLog.log(message = "Tidepool parseUploadId error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            null
        } catch (e: IllegalArgumentException) {
            DebugLog.log(message = "Tidepool parseUploadId error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            null
        }
    }

    /**
     * Uploads CBG records to an existing dataset.
     * Returns true if successful, false otherwise.
     */
    suspend fun uploadData(
        baseUrl: String,
        datasetId: String,
        token: String,
        records: List<CbgRecord>
    ): Boolean {
        if (baseUrl.isBlank() || datasetId.isBlank() || token.isBlank() || records.isEmpty()) return false

        return try {
            val fullUrl = "${baseUrl.trimEnd('/')}/v1/datasets/$datasetId/data"
            val response = httpClient.post(fullUrl) {
                contentType(ContentType.Application.Json)
                header("x-tidepool-session-token", token)
                setBody(records)
            }

            if (!response.status.isSuccess()) {
                DebugLog.log(message = "Tidepool uploadData HTTP ${response.status.value}: $fullUrl")
                return false
            }

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DebugLog.log(message = "Tidepool uploadData error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            false
        } catch (e: SerializationException) {
            DebugLog.log(message = "Tidepool uploadData parse error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            false
        }
    }
}
