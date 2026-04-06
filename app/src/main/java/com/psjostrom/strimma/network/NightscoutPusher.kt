package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NightscoutPusher @Inject constructor(
    private val client: NightscoutClient,
    private val dao: ReadingDao,
    private val settings: SettingsRepository,
    private val alertManager: AlertManager
) {
    companion object {
        const val MAX_RETRY_ATTEMPTS = 12
        const val RETRY_BASE_DELAY_MS = 5000L
        const val MAX_RETRY_DELAY_MS = 60000L
        private const val SECONDS_TO_MS = 1000L
        private const val PUSH_FAIL_ALERT_MS = 15 * 60 * 1000L // 15 minutes
    }

    /** Overridable for testing — allows injecting a test dispatcher scope. */
    @androidx.annotation.VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Idle)
    val status: StateFlow<IntegrationStatus> = _status

    private val failureTracker = PushFailureTracker(
        alertThresholdMs = PUSH_FAIL_ALERT_MS,
        onAlertChanged = { firing ->
            alertManager.handlePushFailure(firing = firing)
            if (firing) {
                _status.value = IntegrationStatus.Error("Push failing for 15+ minutes")
            }
        }
    )

    fun pushReading(reading: GlucoseReading) {
        scope.launch {
            val url = settings.nightscoutUrl.first()
            val secret = settings.getNightscoutSecret()
            if (url.isBlank() || secret.isBlank()) {
                DebugLog.log(message = "Push skipped: URL or secret empty")
                return@launch
            }

            var attempt = 0
            var success = false
            while (!success && attempt < MAX_RETRY_ATTEMPTS) {
                success = client.pushReadings(url, secret, listOf(reading))
                if (success) {
                    dao.markPushed(listOf(reading.ts))
                    DebugLog.log(message = "Pushed: ${reading.sgv} mg/dL")
                    failureTracker.onSuccess()
                    _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
                } else {
                    attempt++
                    failureTracker.onFailure()
                    val delayMs = (attempt * RETRY_BASE_DELAY_MS).coerceAtMost(MAX_RETRY_DELAY_MS)
                    DebugLog.log(message = "Push failed (attempt $attempt), retry in ${delayMs/SECONDS_TO_MS}s")
                    delay(delayMs)
                }
            }
            if (!success) {
                DebugLog.log(message = "Push gave up after $MAX_RETRY_ATTEMPTS attempts: ${reading.sgv} mg/dL")
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    fun pushPending() {
        scope.launch {
            val url = settings.nightscoutUrl.first()
            val secret = settings.getNightscoutSecret()
            if (url.isBlank() || secret.isBlank()) return@launch

            val pending = dao.unpushed()
            if (pending.isEmpty()) return@launch

            DebugLog.log(message = "Pushing ${pending.size} pending readings")
            val success = client.pushReadings(url, secret, pending)
            if (success) {
                dao.markPushed(pending.map { it.ts })
                DebugLog.log(message = "Pushed ${pending.size} pending")
                failureTracker.onSuccess()
                _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
            } else {
                failureTracker.onFailure()
                DebugLog.log(message = "Pending push failed (${pending.size} readings)")
            }
        }
    }
}
