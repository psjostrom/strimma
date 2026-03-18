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
            while (!success && attempt < 12) {
                success = client.pushReadings(url, secret, listOf(reading))
                if (success) {
                    dao.markPushed(listOf(reading.ts))
                    DebugLog.log(message = "Pushed: ${reading.mmol}")
                } else {
                    attempt++
                    val delayMs = (attempt * 5000L).coerceAtMost(60000L)
                    DebugLog.log(message = "Push failed (attempt $attempt), retry in ${delayMs/1000}s")
                    delay(delayMs)
                }
            }
            if (!success) {
                DebugLog.log(message = "Push gave up after 12 attempts: ${reading.mmol}")
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
