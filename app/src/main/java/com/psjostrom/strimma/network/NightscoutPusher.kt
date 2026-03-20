package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NightscoutPusher @Inject constructor(
    private val client: NightscoutClient,
    private val dao: ReadingDao,
    private val settings: SettingsRepository
) {
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 12
        private const val RETRY_BASE_DELAY_MS = 5000L
        private const val MAX_RETRY_DELAY_MS = 60000L
        private const val SECONDS_TO_MS = 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                } else {
                    attempt++
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
            } else {
                DebugLog.log(message = "Pending push failed (${pending.size} readings)")
            }
        }
    }
}
