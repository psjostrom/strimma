package com.psjostrom.strimma.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.InsulinType
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.ExerciseSyncer
import com.psjostrom.strimma.data.health.HealthConnectManager
import com.psjostrom.strimma.network.LibreLinkUpFollower
import com.psjostrom.strimma.network.NightscoutFollower
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.network.TreatmentSyncer
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.notification.NotificationHelper
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import com.psjostrom.strimma.receiver.XdripBroadcastReceiver
import com.psjostrom.strimma.data.calendar.CalendarReader
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.receiver.WorkoutAlarmReceiver
import com.psjostrom.strimma.webserver.LocalWebServer
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.psjostrom.strimma.widget.StrimmaWidget
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Suppress("TooManyFunctions") // Service lifecycle + source management + core logic
@AndroidEntryPoint
class StrimmaService : Service() {

    companion object {
        private const val DEFAULT_BG_LOW = 72.0
        private const val DEFAULT_BG_HIGH = 180.0
        private const val DEFAULT_PREDICTION_MINUTES = 15
        private const val DEFAULT_NOTIF_GRAPH_MINUTES = 60
        private const val DEFAULT_CUSTOM_DIA = 5.0f

        private const val DUPLICATE_THRESHOLD_MS = 3_000L
        private const val LOOKBACK_MINUTES = 15
        private const val MINUTES_TO_MS = 60 * 1000L
        private const val HOURS_TO_MS = 60 * MINUTES_TO_MS

        private const val RETRY_INTERVAL_MINUTES = 5
        private const val PRUNE_INTERVAL_DAYS = 1
        private const val PRUNE_RETENTION_DAYS = 30L
        private const val STALE_CHECK_INTERVAL_SECONDS = 60
        private const val SECONDS_TO_MS = 1000L
        private const val HOURS_PER_DAY = 24
        private const val DELTA_ROUNDING_FACTOR = 10.0
        private const val MINUTES_PER_HOUR = 60
        private const val DEFAULT_WORKOUT_LOOKAHEAD_HOURS = 3
        private const val DEFAULT_WORKOUT_TRIGGER_MINUTES = 120
        private const val CALENDAR_POLL_INTERVAL_MINUTES = 15
        private const val WORKOUT_GRACE_MINUTES = 15
        private const val FORECAST_HORIZON_MINUTES = 30

        private const val DELTA_DIVISOR = 5.0
        private const val MGDL_FACTOR = GlucoseUnit.MGDL_FACTOR
        private const val MIN_VALID_MGDL = 18.0
        private const val MAX_VALID_MGDL = 900.0
    }

    @Inject lateinit var dao: ReadingDao
    @Inject lateinit var directionComputer: DirectionComputer
    @Inject lateinit var pusher: NightscoutPusher
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var alertManager: AlertManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var nightscoutFollower: NightscoutFollower
    @Inject lateinit var libreLinkUpFollower: LibreLinkUpFollower
    @Inject lateinit var nightscoutPuller: NightscoutPuller
    @Inject lateinit var treatmentSyncer: TreatmentSyncer
    @Inject lateinit var treatmentDao: TreatmentDao
    @Inject lateinit var localWebServer: LocalWebServer
    @Inject lateinit var exerciseDao: ExerciseDao
    @Inject lateinit var exerciseSyncer: ExerciseSyncer
    @Inject lateinit var healthConnectManager: HealthConnectManager
    @Inject lateinit var calendarReader: CalendarReader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pruneJob: Job? = null
    private var xdripReceiver: XdripBroadcastReceiver? = null
    private var followerJob: Job? = null
    private var lluFollowerJob: Job? = null
    private var treatmentSyncJob: Job? = null
    private var exerciseSyncJob: Job? = null
    private var calendarPollJob: Job? = null
    @Volatile
    private var cachedWorkoutEvent: WorkoutEvent? = null
    private lateinit var workoutCalendarId: StateFlow<Long>
    private lateinit var workoutLookaheadHours: StateFlow<Int>
    private lateinit var workoutTriggerMinutes: StateFlow<Int>

    private lateinit var predMinutes: StateFlow<Int>
    private lateinit var notifGraphMinutes: StateFlow<Int>
    private lateinit var glucoseUnit: StateFlow<GlucoseUnit>
    private lateinit var bgLow: StateFlow<Float>
    private lateinit var bgHigh: StateFlow<Float>
    private lateinit var bgBroadcastEnabled: StateFlow<Boolean>
    private lateinit var treatmentsSyncEnabled: StateFlow<Boolean>
    private lateinit var insulinType: StateFlow<InsulinType>
    private lateinit var customDIA: StateFlow<Float>
    private lateinit var hcWriteEnabled: StateFlow<Boolean>

    @Suppress("LongMethod") // Service lifecycle setup — all initialization must happen here
    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannel()
        alertManager.createChannels()

        predMinutes = settings.predictionMinutes.stateIn(scope, SharingStarted.Eagerly, DEFAULT_PREDICTION_MINUTES)
        notifGraphMinutes = settings.notifGraphMinutes.stateIn(scope, SharingStarted.Eagerly, DEFAULT_NOTIF_GRAPH_MINUTES)
        glucoseUnit = settings.glucoseUnit.stateIn(scope, SharingStarted.Eagerly, GlucoseUnit.MMOL)
        bgLow = settings.bgLow.stateIn(scope, SharingStarted.Eagerly, DEFAULT_BG_LOW.toFloat())
        bgHigh = settings.bgHigh.stateIn(scope, SharingStarted.Eagerly, DEFAULT_BG_HIGH.toFloat())
        bgBroadcastEnabled = settings.bgBroadcastEnabled.stateIn(scope, SharingStarted.Eagerly, false)
        treatmentsSyncEnabled = settings.treatmentsSyncEnabled.stateIn(scope, SharingStarted.Eagerly, false)
        insulinType = settings.insulinType.stateIn(scope, SharingStarted.Eagerly, InsulinType.FIASP)
        customDIA = settings.customDIA.stateIn(scope, SharingStarted.Eagerly, DEFAULT_CUSTOM_DIA)
        hcWriteEnabled = settings.hcWriteEnabled.stateIn(scope, SharingStarted.Eagerly, false)
        workoutCalendarId = settings.workoutCalendarId.stateIn(scope, SharingStarted.Eagerly, -1L)
        workoutLookaheadHours = settings.workoutLookaheadHours.stateIn(
            scope, SharingStarted.Eagerly, DEFAULT_WORKOUT_LOOKAHEAD_HOURS
        )
        workoutTriggerMinutes = settings.workoutTriggerMinutes.stateIn(
            scope, SharingStarted.Eagerly, DEFAULT_WORKOUT_TRIGGER_MINUTES
        )

        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildNotification(null, emptyList(), DEFAULT_BG_LOW, DEFAULT_BG_HIGH),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        DebugLog.log("Service started")

        scope.launch {
            settings.glucoseSource.collect { source ->
                unregisterXdripReceiver()
                stopFollower()
                stopLluFollower()
                when (source) {
                    GlucoseSource.COMPANION -> { }
                    GlucoseSource.XDRIP_BROADCAST -> registerXdripReceiver()
                    GlucoseSource.NIGHTSCOUT_FOLLOWER -> startFollower()
                    GlucoseSource.LIBRELINKUP -> startLluFollower()
                }
            }
        }

        // Treatment sync lifecycle — only run when NS is configured
        scope.launch {
            combine(
                settings.treatmentsSyncEnabled,
                settings.nightscoutUrl,
                settings.secretVersion
            ) { enabled, nsUrl, _ ->
                if (!enabled) return@combine false
                val hasConfig = nsUrl.isNotBlank() && settings.getNightscoutSecret().isNotBlank()
                if (!hasConfig && enabled) {
                    settings.setTreatmentsSyncEnabled(false)
                    DebugLog.log("Treatment sync auto-disabled: Nightscout not configured")
                }
                hasConfig && enabled
            }.collect { shouldSync ->
                treatmentSyncJob?.cancel()
                treatmentSyncJob = null
                if (shouldSync) {
                    treatmentSyncJob = treatmentSyncer.start(scope)
                    DebugLog.log("Treatment sync started")
                } else {
                    DebugLog.log("Treatment sync stopped")
                }
            }
        }

        // Web server lifecycle
        scope.launch {
            settings.webServerEnabled.collect { enabled ->
                if (enabled) {
                    localWebServer.start()
                } else {
                    localWebServer.stop()
                }
            }
        }

        // Exercise sync (HC availability + permissions checked inside syncer)
        exerciseSyncJob = exerciseSyncer.start(scope)

        pusher.pushPending()
        scope.launch { nightscoutPuller.pullIfEmpty() }
        scope.launch { updateNotification() }
        startPeriodicJobs()
        startCalendarPoll()
    }

    private fun startPeriodicJobs() {
        scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MINUTES * MINUTES_TO_MS)
                pusher.pushPending()
            }
        }
        scope.launch {
            while (isActive) {
                val thirtyDaysAgo = System.currentTimeMillis() - PRUNE_RETENTION_DAYS * HOURS_PER_DAY * HOURS_TO_MS
                dao.pruneBefore(thirtyDaysAgo)
                delay(PRUNE_INTERVAL_DAYS * HOURS_PER_DAY * HOURS_TO_MS)
            }
        }
        scope.launch {
            while (isActive) {
                delay(STALE_CHECK_INTERVAL_SECONDS * SECONDS_TO_MS)
                val latest = dao.latestOnce()
                alertManager.checkStale(latest?.ts)
                updateNotification()
                cachedWorkoutEvent?.let { event ->
                    if (System.currentTimeMillis() > event.startTime + WORKOUT_GRACE_MINUTES * MINUTES_TO_MS) {
                        cachedWorkoutEvent = null
                        cancelWorkoutAlarm()
                        WorkoutAlarmReceiver.notificationTriggerFired.set(false)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED) {
            val mgdl = intent.getDoubleExtra(GlucoseNotificationListener.EXTRA_MGDL, 0.0)
            val timestamp = intent.getLongExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, 0L)
            if (mgdl > 0.0 && timestamp > 0L) {
                scope.launch { processReading(mgdl, timestamp) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterXdripReceiver()
        stopFollower()
        stopLluFollower()
        treatmentSyncJob?.cancel()
        exerciseSyncJob?.cancel()
        calendarPollJob?.cancel()
        pruneJob?.cancel()
        localWebServer.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerXdripReceiver() {
        if (xdripReceiver != null) return
        val receiver = XdripBroadcastReceiver()
        val filter = IntentFilter(XdripBroadcastReceiver.ACTION)
        registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        xdripReceiver = receiver
        DebugLog.log("xDrip broadcast receiver registered")
    }

    private fun unregisterXdripReceiver() {
        xdripReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            xdripReceiver = null
            DebugLog.log("xDrip broadcast receiver unregistered")
        }
    }

    private fun startFollower() {
        if (followerJob != null) return
        followerJob = nightscoutFollower.start(scope) { reading ->
            updateNotification()
            val alertReadings = dao.since(reading.ts - LOOKBACK_MINUTES * MINUTES_TO_MS)
            alertManager.checkReading(reading, alertReadings, predMinutes.value)
            broadcastBgIfEnabled(reading)
            try {
                val mgr = GlanceAppWidgetManager(this@StrimmaService)
                mgr.getGlanceIds(StrimmaWidget::class.java).forEach { id ->
                    StrimmaWidget().update(this@StrimmaService, id)
                }
            } catch (_: Exception) {}
        }
        DebugLog.log("Nightscout follower started")
    }

    private fun stopFollower() {
        followerJob?.cancel()
        followerJob = null
        nightscoutFollower.stop()
        DebugLog.log("Nightscout follower stopped")
    }

    private fun startLluFollower() {
        if (lluFollowerJob != null) return
        lluFollowerJob = libreLinkUpFollower.start(scope) { reading ->
            pusher.pushReading(reading)
            updateNotification()
            val alertReadings = dao.since(reading.ts - LOOKBACK_MINUTES * MINUTES_TO_MS)
            alertManager.checkReading(reading, alertReadings, predMinutes.value)
            broadcastBgIfEnabled(reading)
            writeToHealthConnectIfEnabled(reading)
            try {
                val mgr = GlanceAppWidgetManager(this@StrimmaService)
                mgr.getGlanceIds(StrimmaWidget::class.java).forEach { id ->
                    StrimmaWidget().update(this@StrimmaService, id)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") // Glance SDK can throw various platform exceptions
                e: Exception
            ) {
                DebugLog.log("LLU widget update failed: ${e.message}")
            }
        }
        DebugLog.log("LibreLinkUp follower started")
    }

    private fun stopLluFollower() {
        lluFollowerJob?.cancel()
        lluFollowerJob = null
        libreLinkUpFollower.stop()
        DebugLog.log("LibreLinkUp follower stopped")
    }

    private suspend fun processReading(mgdl: Double, timestamp: Long) {
        if (mgdl < MIN_VALID_MGDL || mgdl > MAX_VALID_MGDL) {
            DebugLog.log("Rejected invalid mg/dL value: $mgdl")
            return
        }
        val sgv = Math.round(mgdl).toInt()

        val existing = dao.lastN(1)
        if (existing.isNotEmpty() && (timestamp - existing[0].ts) < DUPLICATE_THRESHOLD_MS) return

        val recentReadings = dao.since(timestamp - LOOKBACK_MINUTES * MINUTES_TO_MS)
        val tempReading = GlucoseReading(
            ts = timestamp, sgv = sgv,
            direction = "NONE", delta = null, pushed = 0
        )
        val (direction, deltaMgdl) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            delta = deltaMgdl?.let { Math.round(it * DELTA_ROUNDING_FACTOR) / DELTA_ROUNDING_FACTOR }
        )

        dao.insert(reading)
        DebugLog.log("Stored: ${reading.sgv} mg/dL ${direction.arrow}")
        pusher.pushReading(reading)
        updateNotification()
        // 15-min lookback covers PredictionComputer (12 min) + DirectionComputer (5 min lookback, 10 min max gap)
        val alertReadings = dao.since(timestamp - LOOKBACK_MINUTES * MINUTES_TO_MS)
        alertManager.checkReading(reading, alertReadings, predMinutes.value)
        broadcastBgIfEnabled(reading)
        writeToHealthConnectIfEnabled(reading)
        try {
            val mgr = GlanceAppWidgetManager(this@StrimmaService)
            mgr.getGlanceIds(StrimmaWidget::class.java).forEach { id ->
                StrimmaWidget().update(this@StrimmaService, id)
            }
        } catch (_: Exception) {}
    }

    private fun writeToHealthConnectIfEnabled(reading: GlucoseReading) {
        if (!hcWriteEnabled.value) return
        scope.launch {
            try {
                if (!healthConnectManager.hasPermissions()) return@launch
                healthConnectManager.writeGlucoseReading(reading)
            } catch (
                @Suppress("TooGenericExceptionCaught") // HC SDK can throw various platform exceptions
                e: Exception
            ) {
                DebugLog.log("HC write skipped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun updateNotification() {
        val latest = dao.latestOnce()?.takeUnless { reading ->
            (System.currentTimeMillis() - reading.ts) > AlertManager.STALE_THRESHOLD_MINUTES * MINUTES_TO_MS
        }
        val graphWindowMs = notifGraphMinutes.value.toLong() * MINUTES_TO_MS
        val now = System.currentTimeMillis()
        val recent = dao.since(now - graphWindowMs)

        val iob = if (treatmentsSyncEnabled.value) {
            val tau = IOBComputer.tauForInsulinType(insulinType.value, customDIA.value)
            val treatments = treatmentDao.insulinSince(now - IOBComputer.lookbackMs(tau))
            IOBComputer.computeIOB(treatments, now, tau)
        } else 0.0

        val exerciseSessions = exerciseDao.getSessionsInRange(now - graphWindowMs, now)

        val workoutText = if (WorkoutAlarmReceiver.notificationTriggerFired.get() && latest != null) {
            cachedWorkoutEvent?.let { event ->
                val minutesUntil = ((event.startTime - now) / MINUTES_TO_MS).toInt()
                if (minutesUntil <= 0) return@let null
                val timeText = if (minutesUntil >= MINUTES_PER_HOUR) {
                    val h = minutesUntil / MINUTES_PER_HOUR
                    val m = minutesUntil % MINUTES_PER_HOUR
                    if (m > 0) "${h}h${m}m" else "${h}h"
                } else {
                    "${minutesUntil}m"
                }
                val velocity = com.psjostrom.strimma.graph.PredictionComputer.currentVelocity(recent)
                val prediction = com.psjostrom.strimma.graph.PredictionComputer.compute(
                    recent, FORECAST_HORIZON_MINUTES,
                    bgLow.value.toDouble(), bgHigh.value.toDouble()
                )
                val forecastBg = prediction?.points?.lastOrNull()?.mgdl
                val targetLow = settings.exerciseTargetLow(event.category).first()
                val targetHigh = settings.exerciseTargetHigh(event.category).first()
                val result = com.psjostrom.strimma.data.calendar.PreActivityAssessor.assess(
                    currentBgMgdl = latest.sgv,
                    velocityMgdlPerMin = velocity,
                    iob = iob,
                    forecastBgAt30minMgdl = forecastBg,
                    timeToWorkoutMs = (event.startTime - now),
                    targetLowMgdl = targetLow,
                    targetHighMgdl = targetHigh,
                    glucoseUnit = glucoseUnit.value
                )
                val actionText = when {
                    result.carbRecommendation != null -> "eat ${result.carbRecommendation.totalGrams}g"
                    result.readiness == com.psjostrom.strimma.data.calendar.ReadinessLevel.WAIT ->
                        "hold off"
                    result.readiness == com.psjostrom.strimma.data.calendar.ReadinessLevel.CAUTION ->
                        "monitor"
                    else -> "ready"
                }
                "\uD83C\uDFC3 $timeText $actionText"
            }
        } else null

        notificationHelper.updateNotification(
            latest, recent, bgLow.value.toDouble(), bgHigh.value.toDouble(),
            graphWindowMs, predMinutes.value, glucoseUnit.value, iob, exerciseSessions,
            workoutText
        )
    }

    private fun broadcastBgIfEnabled(reading: com.psjostrom.strimma.data.GlucoseReading) {
        if (!bgBroadcastEnabled.value) return
        val intent = Intent("com.eveningoutpost.dexdrip.BgEstimate").apply {
            putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", reading.sgv.toDouble())
            putExtra("com.eveningoutpost.dexdrip.Extras.Raw", reading.sgv.toDouble())
            putExtra("com.eveningoutpost.dexdrip.Extras.Time", reading.ts)
            putExtra("com.eveningoutpost.dexdrip.Extras.BgSlope", ((reading.delta ?: 0.0) / MGDL_FACTOR) / DELTA_DIVISOR)
            putExtra("com.eveningoutpost.dexdrip.Extras.SensorId", "Strimma")
            val direction = try {
                com.psjostrom.strimma.data.Direction.valueOf(reading.direction)
            } catch (_: Exception) {
                com.psjostrom.strimma.data.Direction.NONE
            }
            putExtra("com.eveningoutpost.dexdrip.Extras.BgSlopeName", direction.name)
        }
        sendBroadcast(intent)
        DebugLog.log("Broadcast BG: ${reading.sgv} mg/dL")
    }

    private fun startCalendarPoll() {
        // React immediately when calendarId loads from DataStore
        // (avoids the race where first poll sees -1 and cancels the alarm)
        scope.launch {
            workoutCalendarId.collect {
                try {
                    pollCalendar()
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception
                ) {
                    DebugLog.log("Calendar poll (reactive) failed: ${e.message}")
                }
            }
        }
        // Background poll for calendar changes (events added/removed externally)
        calendarPollJob = scope.launch {
            while (isActive) {
                delay(CALENDAR_POLL_INTERVAL_MINUTES * MINUTES_TO_MS)
                try {
                    pollCalendar()
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception
                ) {
                    DebugLog.log("Calendar poll failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun pollCalendar() {
        val calId = workoutCalendarId.value
        if (calId < 0) {
            cachedWorkoutEvent = null
            cancelWorkoutAlarm()
            WorkoutAlarmReceiver.notificationTriggerFired.set(false)
            return
        }
        val lookaheadMs = workoutLookaheadHours.value.toLong() * HOURS_TO_MS
        val event = calendarReader.getNextWorkout(calId, lookaheadMs)
        val previous = cachedWorkoutEvent
        cachedWorkoutEvent = event

        if (event != null && (previous == null || previous.startTime != event.startTime)) {
            val triggerMs = workoutTriggerMinutes.value.toLong() * MINUTES_TO_MS
            val alarmTime = event.startTime - triggerMs
            if (alarmTime > System.currentTimeMillis()) {
                WorkoutAlarmReceiver.notificationTriggerFired.set(false)
            }
            scheduleWorkoutAlarm(event)
        } else if (event == null && previous != null) {
            cancelWorkoutAlarm()
            WorkoutAlarmReceiver.notificationTriggerFired.set(false)
            scope.launch { updateNotification() }
        }
    }

    private fun scheduleWorkoutAlarm(event: WorkoutEvent) {
        val triggerMs = workoutTriggerMinutes.value.toLong() * MINUTES_TO_MS
        val alarmTime = event.startTime - triggerMs
        if (alarmTime <= System.currentTimeMillis()) {
            WorkoutAlarmReceiver.notificationTriggerFired.set(true)
            scope.launch { updateNotification() }
            return
        }
        val alarmManager = getSystemService(AlarmManager::class.java)
        val intent = Intent(this, WorkoutAlarmReceiver::class.java).apply {
            action = WorkoutAlarmReceiver.ACTION
            putExtra(WorkoutAlarmReceiver.EXTRA_EVENT_START_TIME, event.startTime)
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pi)
            DebugLog.log("Workout alarm scheduled for ${event.title} at $alarmTime")
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, pi)
            DebugLog.log("Workout alarm (inexact) scheduled: ${e.message}")
        }
    }

    private fun cancelWorkoutAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val intent = Intent(this, WorkoutAlarmReceiver::class.java).apply {
            action = WorkoutAlarmReceiver.ACTION
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
        WorkoutAlarmReceiver.notificationTriggerFired.set(false)
    }

}
