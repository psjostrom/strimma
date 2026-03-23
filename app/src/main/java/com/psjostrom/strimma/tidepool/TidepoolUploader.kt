package com.psjostrom.strimma.tidepool

import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Tidepool data uploads.
 * Manages auth, dataset lifecycle, chunking, and rate limiting.
 */
@Singleton
class TidepoolUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TidepoolClient,
    private val authManager: TidepoolAuthManager,
    private val dao: ReadingDao,
    private val settings: SettingsRepository
) {

    companion object {
        const val MAX_CHUNK_MS = 7L * 86_400_000L     // 7 days
        const val UPLOAD_BUFFER_MS = 15L * 60_000L    // 15 minutes
        private const val MAX_LOOKBACK_MS = 60L * 86_400_000L  // 2 months
        private const val RATE_LIMIT_MS = 20L * 60_000L        // 20 minutes

        /**
         * Computes the end timestamp for the next upload chunk.
         * Caps at MAX_CHUNK_MS from start, or now minus buffer, whichever is earlier.
         */
        fun computeChunkEnd(start: Long, now: Long): Long {
            val maxEnd = start + MAX_CHUNK_MS
            val bufferedNow = now - UPLOAD_BUFFER_MS
            return minOf(maxEnd, bufferedNow)
        }

        /**
         * Clamps the last upload end timestamp to prevent excessive lookback.
         * Returns max(stored, now - MAX_LOOKBACK_MS).
         */
        fun clampLastUploadEnd(stored: Long, now: Long): Long {
            val earliest = now - MAX_LOOKBACK_MS
            return maxOf(stored, earliest)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when a new glucose reading arrives.
     * Launches uploadIfReady() in background scope.
     */
    fun onNewReading() {
        scope.launch {
            uploadIfReady()
        }
    }

    /**
     * Called periodically to upload pending data.
     * Same as onNewReading().
     */
    fun uploadPending() {
        scope.launch {
            uploadIfReady()
        }
    }

    private suspend fun uploadIfReady() {
        // Check if Tidepool upload is enabled
        if (!settings.tidepoolEnabled.first()) {
            return
        }

        // Check if user is logged in
        if (!authManager.isLoggedIn()) {
            return
        }

        // Rate limit: at least 20 minutes since last attempt
        val lastUploadTime = settings.tidepoolLastUploadTime.first()
        val now = System.currentTimeMillis()
        if (now - lastUploadTime < RATE_LIMIT_MS) {
            return
        }

        // Check charging condition if configured
        if (settings.tidepoolOnlyWhileCharging.first() && !isCharging()) {
            return
        }

        // Check wifi condition if configured
        if (settings.tidepoolOnlyWhileWifi.first() && !isOnWifi()) {
            return
        }

        // All checks passed, attempt upload
        doUpload()
    }

    private suspend fun doUpload() {
        // Get valid access token
        val token = authManager.getValidAccessToken()
        if (token == null) {
            DebugLog.log(message = "Tidepool upload: no valid token")
            return
        }

        // Get API base URL from environment setting
        val environment = settings.tidepoolEnvironment.first()
        val baseUrl = authManager.getApiBase(environment)

        // Get user ID
        val userId = settings.tidepoolUserId.first()
        if (userId.isBlank()) {
            DebugLog.log(message = "Tidepool upload: no user ID")
            return
        }

        // Get or create dataset
        var datasetId = settings.tidepoolDatasetId.first()
        if (datasetId.isBlank()) {
            // Try to get existing dataset
            datasetId = client.getExistingDataset(baseUrl, userId, token) ?: ""

            // If no existing dataset, create one
            if (datasetId.isBlank()) {
                val appVersion = getAppVersion()
                val now = System.currentTimeMillis()
                val datasetRequest = DatasetRequest(
                    client = DatasetRequest.ClientInfo(
                        name = "com.psjostrom.strimma",
                        version = appVersion
                    ),
                    time = TidepoolDateUtil.toUtcIso8601(now),
                    computerTime = TidepoolDateUtil.toLocalNoZone(now),
                    timezoneOffset = TidepoolDateUtil.getTimezoneOffsetMinutes(now),
                    timezone = java.util.TimeZone.getDefault().id,
                    version = appVersion
                )
                datasetId = client.createDataset(baseUrl, userId, token, datasetRequest) ?: ""
            }

            if (datasetId.isBlank()) {
                settings.setTidepoolLastError("Failed to create dataset")
                DebugLog.log(message = "Tidepool upload: failed to create dataset")
                return
            }

            // Store dataset ID
            settings.setTidepoolDatasetId(datasetId)
        }

        // Compute chunk window
        val now = System.currentTimeMillis()
        val storedLastEnd = settings.tidepoolLastUploadEnd.first()
        val lastEnd = clampLastUploadEnd(storedLastEnd, now)
        val chunkEnd = computeChunkEnd(lastEnd, now)

        // Nothing to upload if chunk end hasn't advanced
        if (chunkEnd <= lastEnd) {
            return
        }

        // Query readings from Room
        val allReadings = dao.since(lastEnd)
        val readings = allReadings.filter { reading ->
            reading.ts <= chunkEnd && CbgRecord.isValidForUpload(reading)
        }

        // If no valid readings, advance cursor and return
        if (readings.isEmpty()) {
            settings.setTidepoolLastUploadEnd(chunkEnd)
            settings.setTidepoolLastUploadTime(now)
            return
        }

        // Map to CbgRecord and upload
        val records = readings.map { CbgRecord.fromReading(it) }
        val success = client.uploadData(baseUrl, datasetId, token, records)

        if (success) {
            // Advance cursor, update last upload time, clear error
            settings.setTidepoolLastUploadEnd(chunkEnd)
            settings.setTidepoolLastUploadTime(now)
            settings.setTidepoolLastError("")
            DebugLog.log(message = "Tidepool upload: ${records.size} records uploaded")
        } else {
            // Set error message
            settings.setTidepoolLastError("Upload failed")
            DebugLog.log(message = "Tidepool upload: failed to upload ${records.size} records")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun isCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(BATTERY_SERVICE) as? BatteryManager
            batteryManager?.isCharging ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isOnWifi(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        } catch (e: Exception) {
            false
        }
    }
}
