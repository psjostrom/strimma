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
import java.util.Locale
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
    private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Idle)
    val status: StateFlow<IntegrationStatus> = _status

    private var session: LluSession? = null
    private var patientId: String? = null
    private var lastLoginTs: Long = 0

    @Suppress("CyclomaticComplexMethod") // Polling loop with auth + reconnect + data processing
    fun start(scope: CoroutineScope, onNewReading: suspend (GlucoseReading) -> Unit): Job {
        return scope.launch {
            _status.value = IntegrationStatus.Connecting

            val email = settings.getLluEmail()
            val password = settings.getLluPassword()
            if (email.isBlank() || password.isBlank()) {
                DebugLog.log(message = "LLU follower: email or password empty")
                _status.value = IntegrationStatus.Idle
                return@launch
            }

            if (!doLogin(email, password)) {
                _status.value = IntegrationStatus.Error("Login failed")
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
            _status.value = IntegrationStatus.Error("Connection lost")
            doLogin(email, password)
            return
        }

        // Re-login before token expires
        if (System.currentTimeMillis() - lastLoginTs > RELOGIN_INTERVAL_MS) {
            DebugLog.log(message = "LLU: re-authenticating")
            if (!doLogin(email, password)) {
                _status.value = IntegrationStatus.Error("Re-authentication failed")
                DebugLog.log(message = "LLU: re-authentication failed")
                return
            }
        }

        // Read session/patientId after potential re-login
        val activeSession = session ?: return
        val activePatientId = patientId ?: return

        val graphData = client.getGraph(activeSession, activePatientId)
        if (graphData == null) {
            if (_status.value !is IntegrationStatus.Error) {
                _status.value = IntegrationStatus.Error("Connection lost")
            }
            DebugLog.log(message = "LLU: poll failed")
            return
        }

        val newCount = processGraphData(graphData, onNewReading)
        _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
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
        val sgv = item.valueInMgPerDl
        if (!GlucoseReading.isValidSgv(sgv)) {
            DebugLog.log(
                message = "LLU: rejected SGV $sgv " +
                    "(outside ${GlucoseReading.MIN_VALID_SGV}–${GlucoseReading.MAX_VALID_SGV})",
            )
            return false
        }
        val ts = parseLluTimestamp(item.factoryTimestamp) ?: return false
        val entry = NightscoutEntryResponse(sgv = sgv, date = ts, type = "sgv")
        val reading = processNightscoutEntry(entry, dao, directionComputer, pushed = 0) ?: return false
        onNewReading(reading)
        return true
    }

    fun stop() {
        session = null
        patientId = null
        _status.value = IntegrationStatus.Idle
    }

    private suspend fun doLogin(email: String, password: String): Boolean {
        val newSession = client.login(email, password) ?: return false
        session = newSession
        lastLoginTs = System.currentTimeMillis()

        val connections = client.getConnections(newSession)
        if (connections.isNullOrEmpty()) {
            DebugLog.log(message = "LLU: no connections found — is sharing set up in the Libre app?")
            _status.value = IntegrationStatus.Error("No connections found")
            return false
        }

        patientId = connections.first().patientId
        DebugLog.log(message = "LLU: connected as follower of ${connections.first().firstName}")
        _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
        return true
    }

    companion object {

        // FactoryTimestamp is UTC and uses a consistent M/d/yyyy format across all regions,
        // unlike Timestamp which uses regional formats (EU day-first vs US month-first).
        // We still support both 12h and 24h variants as observed in the API.
        private val FORMATS = listOf(
            DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.US),  // 12h with AM/PM
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss", Locale.US)     // 24h
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
