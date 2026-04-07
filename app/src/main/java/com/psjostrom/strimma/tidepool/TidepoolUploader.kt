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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Tidepool data uploads.
 * Manages auth, dataset lifecycle, chunking, and rate limiting.
 */
@Suppress("TooManyFunctions") // Upload lifecycle + debug entry point
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
        private const val MAX_LOG_ERROR_LENGTH = 80
        private const val MAX_USER_ERROR_LENGTH = 50

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

    private var job = SupervisorJob()
    private var scope = CoroutineScope(job + Dispatchers.IO)

    /** Cancels the upload scope. Called from StrimmaService.onDestroy(). */
    fun stop() {
        scope.cancel()
        job = SupervisorJob()
        scope = CoroutineScope(job + Dispatchers.IO)
    }

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

    /**
     * Forces an upload attempt, bypassing rate limit and charging/wifi checks.
     * Used for debugging.
     */
    fun forceUpload() {
        scope.launch {
            val enabled = settings.tidepoolEnabled.first()
            val loggedIn = authManager.isLoggedIn()
            DebugLog.log(message = "Tidepool forceUpload: enabled=$enabled, loggedIn=$loggedIn")
            if (!enabled) return@launch
            if (!loggedIn) return@launch
            doUpload()
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

    @Suppress("TooGenericExceptionCaught") // Top-level safety net for background upload
    private suspend fun doUpload() {
        try {
            val token = authManager.getValidAccessToken()
            if (token == null) {
                if (!authManager.isLoggedIn()) {
                    settings.setTidepoolLastError("Session expired. Please log in again.")
                }
                DebugLog.log(message = "Tidepool upload: no valid token")
                return
            }

            val userId = settings.tidepoolUserId.first()
            if (userId.isBlank()) {
                DebugLog.log(message = "Tidepool upload: no user ID")
                return
            }

            val datasetId = getOrCreateDataset(userId, token) ?: return
            uploadChunk(datasetId, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log(message = "Tidepool upload error: ${e.javaClass.simpleName}: ${e.message?.take(MAX_LOG_ERROR_LENGTH)}")
            settings.setTidepoolLastError("Upload error: ${e.message?.take(MAX_USER_ERROR_LENGTH)}")
        }
    }

    private suspend fun getOrCreateDataset(userId: String, token: String): String? {
        var datasetId = settings.tidepoolDatasetId.first()
        if (datasetId.isNotBlank()) return datasetId

        datasetId = client.getExistingDataset(TidepoolAuthManager.API_BASE, userId, token) ?: ""

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
            datasetId = client.createDataset(TidepoolAuthManager.API_BASE, userId, token, datasetRequest) ?: ""
        }

        if (datasetId.isBlank()) {
            settings.setTidepoolLastError("Failed to create dataset")
            DebugLog.log(message = "Tidepool upload: failed to create dataset")
            return null
        }

        settings.setTidepoolDatasetId(datasetId)
        return datasetId
    }

    private suspend fun uploadChunk(datasetId: String, token: String) {
        val now = System.currentTimeMillis()
        val storedLastEnd = settings.tidepoolLastUploadEnd.first()
        val lastEnd = clampLastUploadEnd(storedLastEnd, now)
        val chunkEnd = computeChunkEnd(lastEnd, now)

        DebugLog.log(message = "Tidepool uploadChunk: lastEnd=$lastEnd, chunkEnd=$chunkEnd, now=$now")

        if (chunkEnd <= lastEnd) return

        val readings = dao.since(lastEnd).filter { reading ->
            reading.ts <= chunkEnd && CbgRecord.isValidForUpload(reading)
        }

        if (readings.isEmpty()) {
            settings.setTidepoolLastUploadEnd(chunkEnd)
            return
        }

        val records = readings.map { CbgRecord.fromReading(it) }
        val success = client.uploadData(TidepoolAuthManager.API_BASE, datasetId, token, records)

        if (success) {
            settings.setTidepoolLastUploadEnd(chunkEnd)
            settings.setTidepoolLastUploadTime(now)
            settings.setTidepoolLastError("")
            DebugLog.log(message = "Tidepool upload: ${records.size} records uploaded")
        } else {
            settings.setTidepoolLastError("Upload failed")
            settings.setTidepoolDatasetId("")
            DebugLog.log(message = "Tidepool upload: failed to upload ${records.size} records, cleared dataset ID")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            DebugLog.log(message = "Tidepool: package info not found: ${e.message}")
            "unknown"
        }
    }

    private fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.isCharging ?: false
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
    }
}
