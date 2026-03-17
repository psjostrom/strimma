package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpringaPusher @Inject constructor(
    private val client: SpringaClient,
    private val dao: ReadingDao,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pushReading(reading: GlucoseReading) {
        scope.launch {
            val url = settings.springaUrl.first()
            val secret = settings.getApiSecret()
            if (url.isBlank() || secret.isBlank()) return@launch

            var attempt = 0
            var success = false
            while (!success && attempt < 12) {
                success = client.pushReadings(url, secret, listOf(reading))
                if (success) {
                    dao.markPushed(listOf(reading.ts))
                } else {
                    attempt++
                    val delayMs = (attempt * 5000L).coerceAtMost(60000L)
                    delay(delayMs)
                }
            }
        }
    }

    fun pushPending() {
        scope.launch {
            val url = settings.springaUrl.first()
            val secret = settings.getApiSecret()
            if (url.isBlank() || secret.isBlank()) return@launch

            val pending = dao.unpushed()
            if (pending.isEmpty()) return@launch

            val success = client.pushReadings(url, secret, pending)
            if (success) {
                dao.markPushed(pending.map { it.ts })
            }
        }
    }
}
