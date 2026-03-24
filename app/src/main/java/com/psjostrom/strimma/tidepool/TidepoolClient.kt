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
import kotlinx.serialization.json.Json
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

    private var httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
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
                DebugLog.log(message = "Tidepool getExistingDataset HTTP ${response.status.value}: $fullUrl")
                return null
            }

            val datasets = response.body<List<DatasetResponse>>()
            datasets.firstOrNull()?.uploadId
        } catch (
            @Suppress("TooGenericExceptionCaught") // Network boundary — Ktor can throw any exception type
            e: Exception
        ) {
            DebugLog.log(message = "Tidepool getExistingDataset error: ${e.message?.take(MAX_ERROR_LENGTH)}")
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
            val response = httpClient.post(fullUrl) {
                contentType(ContentType.Application.Json)
                header("x-tidepool-session-token", token)
                setBody(datasetRequest)
            }

            if (!response.status.isSuccess()) {
                DebugLog.log(message = "Tidepool createDataset HTTP ${response.status.value}: $fullUrl")
                return null
            }

            val datasetResponse = response.body<DatasetResponse>()
            datasetResponse.uploadId
        } catch (
            @Suppress("TooGenericExceptionCaught") // Network boundary — Ktor can throw any exception type
            e: Exception
        ) {
            DebugLog.log(message = "Tidepool createDataset error: ${e.message?.take(MAX_ERROR_LENGTH)}")
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
        } catch (
            @Suppress("TooGenericExceptionCaught") // Network boundary — Ktor can throw any exception type
            e: Exception
        ) {
            DebugLog.log(message = "Tidepool uploadData error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            false
        }
    }
}
