package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExerciseSyncer @Inject constructor(
    private val manager: HealthConnectManager,
    private val dao: ExerciseDao,
    private val settings: SettingsRepository
) {
    companion object {
        private const val POLL_INTERVAL_MS = 15 * 60 * 1000L
        private const val LOOKBACK_DAYS = 30L
    }

    fun start(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                sync()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun sync() {
        if (manager.isAvailable() != HealthConnectStatus.AVAILABLE) return
        try {
            if (!manager.hasPermissions()) return
        } catch (_: SecurityException) {
            DebugLog.log("HC permissions revoked")
            return
        }

        try {
            val token = settings.getHcChangesToken()
            if (token != null) {
                val result = manager.getChanges(token)
                settings.setHcChangesToken(result.nextToken)
                if (result.tokenExpired) {
                    DebugLog.log("HC changes token expired, full resync")
                    fullSync()
                } else if (result.hasChanges) {
                    DebugLog.log("HC changes detected, full resync")
                    fullSync()
                }
            } else {
                fullSync()
            }
        } catch (_: SecurityException) {
            DebugLog.log("HC permissions revoked during sync")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            DebugLog.log("HC sync error: ${e.message}")
        }
    }

    private suspend fun fullSync() {
        val since = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS)
        val sessions = manager.getExerciseSessions(since)

        for ((session, heartRate) in sessions) {
            dao.upsertSessionWithHeartRate(session, heartRate)
        }

        // Prune old sessions
        val cutoff = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS).toEpochMilli()
        dao.deleteSessionsOlderThan(cutoff)

        // Store new changes token for delta sync
        val newToken = manager.getChangesToken()
        settings.setHcChangesToken(newToken)
        settings.setHcLastSync(System.currentTimeMillis())

        DebugLog.log("HC synced: ${sessions.size} exercise sessions")
    }
}
