package com.psjostrom.strimma.tidepool

import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.annotation.VisibleForTesting
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val uploadMutex = Mutex()

    private data class UploadPlan(
        val userId: String,
        val chunkEnd: Long,
        val records: List<CbgRecord>
    )

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
     * Returns the number of records uploaded, or throws on failure.
     *
     * A completed attempt — success or real failure — stamps `tidepoolLastUploadTime`,
     * so a force-failed upload still rate-limits subsequent background attempts.
     * A cancellation (e.g. caller scope dies) does not stamp the timestamp.
     */
    suspend fun forceUpload(): Int {
        return uploadMutex.withLock {
            val enabled = settings.tidepoolEnabled.first()
            val loggedIn = authManager.isLoggedIn()
            DebugLog.log(message = "Tidepool forceUpload: enabled=$enabled, loggedIn=$loggedIn")
            check(enabled) { "Tidepool upload not enabled" }
            check(loggedIn) { "Not logged in to Tidepool" }
            val plan = prepareUpload() ?: return@withLock 0
            executeUpload(plan)
        }
    }

    @VisibleForTesting
    internal suspend fun uploadIfReady() {
        uploadMutex.withLock {
            val now = System.currentTimeMillis()
            val canUpload = settings.tidepoolEnabled.first() &&
                authManager.isLoggedIn() &&
                now - settings.tidepoolLastUploadTime.first() >= RATE_LIMIT_MS &&
                (!settings.tidepoolOnlyWhileCharging.first() || isCharging()) &&
                (!settings.tidepoolOnlyWhileWifi.first() || isOnWifi())

            if (!canUpload) {
                return
            }

            val plan = prepareUpload() ?: return
            executeUpload(plan)
        }
    }

    private suspend fun prepareUpload(): UploadPlan? {
        val userId = settings.tidepoolUserId.first()
        if (userId.isBlank()) {
            DebugLog.log(message = "Tidepool upload: no user ID")
            return null
        }

        val now = System.currentTimeMillis()
        val storedLastEnd = settings.tidepoolLastUploadEnd.first()
        val lastEnd = clampLastUploadEnd(storedLastEnd, now)
        val chunkEnd = computeChunkEnd(lastEnd, now)

        DebugLog.log(message = "Tidepool uploadChunk: lastEnd=$lastEnd, chunkEnd=$chunkEnd, now=$now")

        if (chunkEnd <= lastEnd) {
            return null
        }

        val readings = dao.since(lastEnd).filter { reading ->
            reading.ts <= chunkEnd && CbgRecord.isValidForUpload(reading)
        }

        if (readings.isEmpty()) {
            settings.setTidepoolLastUploadEnd(chunkEnd)
            return null
        }

        return UploadPlan(
            userId = userId,
            chunkEnd = chunkEnd,
            records = readings.map { CbgRecord.fromReading(it) }
        )
    }

    @Suppress("TooGenericExceptionCaught") // Top-level safety net for background upload
    private suspend fun executeUpload(plan: UploadPlan): Int {
        try {
            val token = authManager.getValidAccessToken()
            if (token == null) {
                if (!authManager.isLoggedIn()) {
                    settings.setTidepoolLastError("Session expired. Please log in again.")
                }
                DebugLog.log(message = "Tidepool upload: no valid token")
                stampUploadAttempt()
                return 0
            }

            val datasetId = getOrCreateDataset(plan.userId, token)
            if (datasetId == null) {
                stampUploadAttempt()
                return 0
            }
            return uploadPreparedRecords(datasetId, token, plan)
        } catch (e: CancellationException) {
            // Don't stamp the rate-limit timestamp on caller cancellation —
            // the upload didn't fail, the caller went away.
            throw e
        } catch (e: Exception) {
            DebugLog.log(message = "Tidepool upload error: ${e.javaClass.simpleName}: ${e.message?.take(MAX_LOG_ERROR_LENGTH)}")
            settings.setTidepoolLastError("Upload error: ${e.message?.take(MAX_USER_ERROR_LENGTH)}")
            stampUploadAttempt()
            throw e
        }
    }

    private suspend fun stampUploadAttempt() {
        settings.setTidepoolLastUploadTime(System.currentTimeMillis())
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

        // Persist the new dataset ID even if the caller is cancelled; otherwise
        // a server-side dataset is created with no local record and the next
        // upload would create another orphan dataset.
        withContext(NonCancellable) {
            settings.setTidepoolDatasetId(datasetId)
        }
        return datasetId
    }

    private suspend fun uploadPreparedRecords(datasetId: String, token: String, plan: UploadPlan): Int {
        val success = client.uploadData(TidepoolAuthManager.API_BASE, datasetId, token, plan.records)

        if (success) {
            settings.setTidepoolLastUploadEnd(plan.chunkEnd)
            stampUploadAttempt()
            settings.setTidepoolLastError("")
            DebugLog.log(message = "Tidepool upload: ${plan.records.size} records uploaded")
            return plan.records.size
        } else {
            settings.setTidepoolLastError("Upload failed")
            settings.setTidepoolDatasetId("")
            stampUploadAttempt()
            DebugLog.log(message = "Tidepool upload: failed to upload ${plan.records.size} records, cleared dataset ID")
            error("Upload failed")
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
