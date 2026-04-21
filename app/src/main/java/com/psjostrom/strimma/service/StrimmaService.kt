package com.psjostrom.strimma.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.computeCurrentIOB
import com.psjostrom.strimma.data.InsulinType
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.HealthConnectManager
import com.psjostrom.strimma.network.LibreLinkUpFollower
import com.psjostrom.strimma.network.NightscoutFollower
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.graph.Prediction
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.notification.NotificationHelper
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import com.psjostrom.strimma.receiver.XdripBroadcastReceiver
import com.psjostrom.strimma.data.calendar.CalendarPoller
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.receiver.WorkoutAlarmReceiver
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.psjostrom.strimma.widget.StrimmaWidget
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Suppress("TooManyFunctions") // Service lifecycle + source management + side effects
@AndroidEntryPoint
class StrimmaService : Service() {

    companion object {
        private val DEFAULT_BG_LOW = SettingsRepository.DEFAULT_BG_LOW.toDouble()
        private val DEFAULT_BG_HIGH = SettingsRepository.DEFAULT_BG_HIGH.toDouble()
        private const val DEFAULT_PREDICTION_MINUTES = SettingsRepository.DEFAULT_PREDICTION_MINUTES
        private const val DEFAULT_NOTIF_GRAPH_MINUTES = SettingsRepository.DEFAULT_NOTIF_GRAPH_MINUTES
        private const val DEFAULT_CUSTOM_DIA = 5.0f

        private const val STALE_CHECK_INTERVAL_SECONDS = 60
        private const val SECONDS_TO_MS = 1000L
        private const val MINUTES_PER_HOUR = 60
        private const val DEFAULT_WORKOUT_TRIGGER_MINUTES = 120
        private const val WORKOUT_GRACE_MINUTES = 15
        private const val FORECAST_HORIZON_MINUTES = 30

        private const val SLOPE_WINDOW_MS = 5.0 * 60.0 * 1000.0 // 5 min in ms
    }

    @Inject lateinit var dao: ReadingDao
    @Inject lateinit var pusher: NightscoutPusher
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var alertManager: AlertManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var nightscoutFollower: NightscoutFollower
    @Inject lateinit var libreLinkUpFollower: LibreLinkUpFollower
    @Inject lateinit var treatmentDao: TreatmentDao
    @Inject lateinit var exerciseDao: ExerciseDao
    @Inject lateinit var healthConnectManager: HealthConnectManager
    @Inject lateinit var calendarPoller: CalendarPoller
    @Inject lateinit var readingPipeline: ReadingPipeline
    @Inject lateinit var syncOrchestrator: SyncOrchestrator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var xdripReceiver: XdripBroadcastReceiver? = null
    private var followerJob: Job? = null
    private var lluFollowerJob: Job? = null
    private var calendarPollJob: Job? = null
    @Volatile
    private var wasStale = true // Start as stale (no data yet)
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
        workoutTriggerMinutes = settings.workoutTriggerMinutes.stateIn(
            scope, SharingStarted.Eagerly, DEFAULT_WORKOUT_TRIGGER_MINUTES
        )

        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildNotification(null, emptyList(), DEFAULT_BG_LOW, DEFAULT_BG_HIGH),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0
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

        scope.launch { updateNotification() }
        startStaleCheckLoop()
        syncOrchestrator.start(scope)
        calendarPollJob = calendarPoller.start(scope)
        observeCalendarForAlarms()
    }

    private fun startStaleCheckLoop() {
        scope.launch {
            while (isActive) {
                delay(STALE_CHECK_INTERVAL_SECONDS * SECONDS_TO_MS)
                val latest = dao.latestOnce()
                alertManager.checkStale(latest?.ts)

                val isStale = AlertManager.isStale(latest?.ts)
                val workoutCountdownActive =
                    WorkoutAlarmReceiver.notificationTriggerFired.get() && calendarPoller.nextEvent.value != null
                if (isStale != wasStale || workoutCountdownActive) {
                    updateNotification()
                }

                calendarPoller.nextEvent.value?.let { event ->
                    if (System.currentTimeMillis() > event.startTime + WORKOUT_GRACE_MINUTES * MS_PER_MINUTE) {
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
                DebugLog.log("Pipeline in: mgdl=${mgdl.toInt()} ts=$timestamp")
                scope.launch {
                    val reading = readingPipeline.processReading(mgdl, timestamp)
                    if (reading != null) {
                        onNewReading(reading)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterXdripReceiver()
        stopFollower()
        stopLluFollower()
        calendarPollJob?.cancel()
        syncOrchestrator.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Side effects triggered after a new reading is stored via the pipeline.
     * Also called by follower callbacks (NS/LLU) after their own storage path.
     */
    private suspend fun onNewReading(reading: GlucoseReading) {
        val prediction = updateNotification()
        val alertReadings = dao.since(reading.ts - ReadingPipeline.LOOKBACK_MINUTES * MS_PER_MINUTE)
        alertManager.checkReading(reading, alertReadings, predMinutes.value, prediction)
        broadcastBgIfEnabled(reading)
        writeToHealthConnectIfEnabled(reading)
        updateWidgets()
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
            val prediction = updateNotification()
            val alertReadings = dao.since(reading.ts - ReadingPipeline.LOOKBACK_MINUTES * MS_PER_MINUTE)
            alertManager.checkReading(reading, alertReadings, predMinutes.value, prediction)
            broadcastBgIfEnabled(reading)
            updateWidgets()
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
            val prediction = updateNotification()
            val alertReadings = dao.since(reading.ts - ReadingPipeline.LOOKBACK_MINUTES * MS_PER_MINUTE)
            alertManager.checkReading(reading, alertReadings, predMinutes.value, prediction)
            broadcastBgIfEnabled(reading)
            writeToHealthConnectIfEnabled(reading)
            updateWidgets()
        }
        DebugLog.log("LibreLinkUp follower started")
    }

    private fun stopLluFollower() {
        lluFollowerJob?.cancel()
        lluFollowerJob = null
        libreLinkUpFollower.stop()
        DebugLog.log("LibreLinkUp follower stopped")
    }

    private suspend fun updateWidgets() {
        try {
            val mgr = GlanceAppWidgetManager(this@StrimmaService)
            mgr.getGlanceIds(StrimmaWidget::class.java).forEach { id ->
                StrimmaWidget().update(this@StrimmaService, id)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // Glance can throw various platform exceptions
            e: Exception
        ) {
            DebugLog.log("Widget update failed: ${e.javaClass.simpleName}: ${e.message}")
        }
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

    private suspend fun updateNotification(): Prediction? {
        val latest = dao.latestOnce()?.takeUnless { reading ->
            AlertManager.isStale(reading.ts)
        }
        wasStale = (latest == null)
        val graphWindowMs = notifGraphMinutes.value.toLong() * MS_PER_MINUTE
        val now = System.currentTimeMillis()
        val recent = dao.since(now - graphWindowMs)

        val iob = computeCurrentIOB(
            treatmentsSyncEnabled.value, insulinType.value, customDIA.value, treatmentDao, now
        )

        val exerciseSessions = exerciseDao.getSessionsInRange(now - graphWindowMs, now)

        // Compute prediction once — reused by notification helper, graph renderer, and alert manager
        val prediction = PredictionComputer.compute(
            recent, predMinutes.value,
            bgLow.value.toDouble(), bgHigh.value.toDouble()
        )

        val workoutText = computeWorkoutText(latest, recent, iob, now)

        notificationHelper.updateNotification(
            latest, recent, bgLow.value.toDouble(), bgHigh.value.toDouble(),
            graphWindowMs, predMinutes.value, glucoseUnit.value, iob, exerciseSessions,
            workoutText, prediction
        )

        return prediction
    }

    private suspend fun computeWorkoutText(
        latest: GlucoseReading?,
        recent: List<GlucoseReading>,
        iob: Double,
        now: Long
    ): String? {
        if (!WorkoutAlarmReceiver.notificationTriggerFired.get() || latest == null) return null
        val event = calendarPoller.nextEvent.value ?: return null
        val minutesUntil = ((event.startTime - now) / MS_PER_MINUTE).toInt()
        if (minutesUntil <= 0) return null
        val timeText = if (minutesUntil >= MINUTES_PER_HOUR) {
            val h = minutesUntil / MINUTES_PER_HOUR
            val m = minutesUntil % MINUTES_PER_HOUR
            if (m > 0) "${h}h${m}m" else "${h}h"
        } else {
            "${minutesUntil}m"
        }
        val velocity = PredictionComputer.currentVelocity(recent)
        val forecastPrediction = PredictionComputer.compute(
            recent, FORECAST_HORIZON_MINUTES,
            bgLow.value.toDouble(), bgHigh.value.toDouble()
        )
        val forecastBg = forecastPrediction?.points?.lastOrNull()?.mgdl
        val targetLow = settings.exerciseTargetLow(event.category).first()
        val result = com.psjostrom.strimma.data.calendar.PreActivityAssessor.assess(
            currentBgMgdl = latest.sgv,
            velocityMgdlPerMin = velocity,
            iob = iob,
            forecastBgAt30minMgdl = forecastBg,
            timeToWorkoutMs = (event.startTime - now),
            targetLowMgdl = targetLow,
            glucoseUnit = glucoseUnit.value
        )
        val actionText = when {
            result.carbRecommendation != null -> "eat ${result.carbRecommendation.totalGrams}g"
            result.readiness == com.psjostrom.strimma.data.calendar.ReadinessLevel.WAIT -> "hold off"
            result.readiness == com.psjostrom.strimma.data.calendar.ReadinessLevel.CAUTION -> "monitor"
            else -> "ready"
        }
        return "\uD83C\uDFC3 $timeText $actionText"
    }

    private fun broadcastBgIfEnabled(reading: GlucoseReading) {
        if (!bgBroadcastEnabled.value) return
        val intent = Intent("com.eveningoutpost.dexdrip.BgEstimate").apply {
            putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", reading.sgv.toDouble())
            putExtra("com.eveningoutpost.dexdrip.Extras.Raw", reading.sgv.toDouble())
            putExtra("com.eveningoutpost.dexdrip.Extras.Time", reading.ts)
            putExtra("com.eveningoutpost.dexdrip.Extras.BgSlope", (reading.delta ?: 0.0) / SLOPE_WINDOW_MS)
            putExtra("com.eveningoutpost.dexdrip.Extras.SensorId", "Strimma")
            val direction = com.psjostrom.strimma.data.Direction.parse(reading.direction)
            putExtra("com.eveningoutpost.dexdrip.Extras.BgSlopeName", direction.name)
        }
        sendBroadcast(intent)
        DebugLog.log("Broadcast BG: ${reading.sgv} mg/dL")
    }

    private fun observeCalendarForAlarms() {
        var previousStartTime: Long? = null
        scope.launch {
            calendarPoller.nextEvent.collect { event ->
                if (event != null && previousStartTime != event.startTime) {
                    val triggerMs = workoutTriggerMinutes.value.toLong() * MS_PER_MINUTE
                    val alarmTime = event.startTime - triggerMs
                    if (alarmTime > System.currentTimeMillis()) {
                        WorkoutAlarmReceiver.notificationTriggerFired.set(false)
                    }
                    scheduleWorkoutAlarm(event)
                } else if (event == null && previousStartTime != null) {
                    cancelWorkoutAlarm()
                    WorkoutAlarmReceiver.notificationTriggerFired.set(false)
                    updateNotification()
                }
                previousStartTime = event?.startTime
            }
        }
    }

    private fun scheduleWorkoutAlarm(event: WorkoutEvent) {
        val triggerMs = workoutTriggerMinutes.value.toLong() * MS_PER_MINUTE
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
