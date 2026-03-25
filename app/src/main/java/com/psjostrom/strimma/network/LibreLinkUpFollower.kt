package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val POLL_INTERVAL_MS = 60_000L
private const val RELOGIN_INTERVAL_MS = 50 * 60 * 1000L // 50 minutes (tokens last ~60 min)

@Singleton
class LibreLinkUpFollower @Inject constructor(
    private val client: LibreLinkUpClient,
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val settings: SettingsRepository
) {
    private val _status = MutableStateFlow<FollowerStatus>(FollowerStatus.Idle)
    val status: StateFlow<FollowerStatus> = _status

    private var session: LluSession? = null
    private var patientId: String? = null
    private var lastLoginTs: Long = 0

    @Suppress("CyclomaticComplexMethod") // Polling loop with auth + reconnect + data processing
    fun start(scope: CoroutineScope, onNewReading: suspend (GlucoseReading) -> Unit): Job {
        return scope.launch {
            _status.value = FollowerStatus.Connecting

            val email = settings.getLluEmail()
            val password = settings.getLluPassword()
            if (email.isBlank() || password.isBlank()) {
                DebugLog.log(message = "LLU follower: email or password empty")
                _status.value = FollowerStatus.Idle
                return@launch
            }

            if (!doLogin(email, password)) {
                _status.value = FollowerStatus.Disconnected(since = System.currentTimeMillis())
                return@launch
            }

            while (isActive) {
                poll(email, password, onNewReading)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun poll(
        email: String,
        password: String,
        onNewReading: suspend (GlucoseReading) -> Unit
    ) {
        if (session == null || patientId == null) {
            _status.value = FollowerStatus.Disconnected(since = System.currentTimeMillis())
            doLogin(email, password)
            return
        }

        // Re-login before token expires
        if (System.currentTimeMillis() - lastLoginTs > RELOGIN_INTERVAL_MS) {
            DebugLog.log(message = "LLU: re-authenticating")
            if (!doLogin(email, password)) {
                _status.value = FollowerStatus.Disconnected(since = System.currentTimeMillis())
                DebugLog.log(message = "LLU: re-authentication failed")
                return
            }
        }

        // Read session/patientId after potential re-login
        val activeSession = session ?: return
        val activePatientId = patientId ?: return

        val graphData = client.getGraph(activeSession, activePatientId)
        if (graphData == null) {
            if (_status.value !is FollowerStatus.Disconnected) {
                _status.value = FollowerStatus.Disconnected(since = System.currentTimeMillis())
            }
            DebugLog.log(message = "LLU: poll failed")
            return
        }

        val newCount = processGraphData(graphData, onNewReading)
        _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
        if (newCount > 0) {
            DebugLog.log(message = "LLU: $newCount new readings")
        }
    }

    private suspend fun processGraphData(
        graphData: LluGraphData,
        onNewReading: suspend (GlucoseReading) -> Unit
    ): Int {
        var newCount = 0

        for (item in graphData.graphData) {
            val reading = processLluItem(item, onNewReading)
            if (reading) newCount++
        }

        graphData.connection.glucoseMeasurement?.let { current ->
            if (processLluItem(current, onNewReading)) newCount++
        }

        return newCount
    }

    private suspend fun processLluItem(
        item: LluGlucoseItem,
        onNewReading: suspend (GlucoseReading) -> Unit
    ): Boolean {
        if (item.value < MIN_VALID_SGV || item.value > MAX_VALID_SGV) return false
        val ts = parseLluTimestamp(item.timestamp) ?: return false
        val entry = NightscoutEntryResponse(sgv = item.value, date = ts, type = "sgv")
        val reading = processNightscoutEntry(entry, dao, directionComputer) ?: return false
        onNewReading(reading)
        return true
    }

    fun stop() {
        session = null
        patientId = null
        _status.value = FollowerStatus.Idle
    }

    private suspend fun doLogin(email: String, password: String): Boolean {
        val newSession = client.login(email, password) ?: return false
        session = newSession
        lastLoginTs = System.currentTimeMillis()

        val connections = client.getConnections(newSession)
        if (connections.isNullOrEmpty()) {
            DebugLog.log(message = "LLU: no connections found — is sharing set up in the Libre app?")
            return false
        }

        patientId = connections.first().patientId
        DebugLog.log(message = "LLU: connected as follower of ${connections.first().firstName}")
        _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
        return true
    }

    companion object {
        private const val MIN_VALID_SGV = 18
        private const val MAX_VALID_SGV = 900

        // LLU returns timestamps in regional formats — try all known variants
        private val FORMATS = listOf(
            DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a"),  // US (12h with AM/PM)
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),    // US (24h)
            DateTimeFormatter.ofPattern("d/M/yyyy H:mm:ss"),    // EU (24h, day-first)
            DateTimeFormatter.ofPattern("d/M/yyyy h:mm:ss a")   // EU (12h, day-first)
        )

        fun parseLluTimestamp(timestamp: String): Long? {
            if (timestamp.isBlank()) return null
            for (fmt in FORMATS) {
                try {
                    val ldt = LocalDateTime.parse(timestamp, fmt)
                    return ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: java.time.format.DateTimeParseException) {
                    // Try next format
                }
            }
            DebugLog.log(message = "LLU: unparseable timestamp: $timestamp")
            return null
        }
    }
}
