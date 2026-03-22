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
import com.psjostrom.strimma.network.NightscoutFollower
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.network.TreatmentSyncer
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.notification.NotificationHelper
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import com.psjostrom.strimma.receiver.XdripBroadcastReceiver
import com.psjostrom.strimma.webserver.LocalWebServer
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.psjostrom.strimma.widget.StrimmaWidget
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

        private const val RETRY_INTERVAL_MINUTES = 5
        private const val PRUNE_INTERVAL_DAYS = 1
        private const val PRUNE_RETENTION_DAYS = 30L
        private const val STALE_CHECK_INTERVAL_SECONDS = 60
        private const val SECONDS_TO_MS = 1000L
        private const val HOURS_PER_DAY = 24
        private const val DELTA_ROUNDING_FACTOR = 10.0

        private const val DELTA_DIVISOR = 5.0
        private const val MGDL_FACTOR = 18.0182
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
    @Inject lateinit var nightscoutPuller: NightscoutPuller
    @Inject lateinit var treatmentSyncer: TreatmentSyncer
    @Inject lateinit var treatmentDao: TreatmentDao
    @Inject lateinit var localWebServer: LocalWebServer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pruneJob: Job? = null
    private var xdripReceiver: XdripBroadcastReceiver? = null
    private var followerJob: Job? = null
    private var treatmentSyncJob: Job? = null

    private lateinit var predMinutes: StateFlow<Int>
    private lateinit var notifGraphMinutes: StateFlow<Int>
    private lateinit var glucoseUnit: StateFlow<GlucoseUnit>
    private lateinit var bgLow: StateFlow<Float>
    private lateinit var bgHigh: StateFlow<Float>
    private lateinit var bgBroadcastEnabled: StateFlow<Boolean>
    private lateinit var treatmentsSyncEnabled: StateFlow<Boolean>
    private lateinit var insulinType: StateFlow<InsulinType>
    private lateinit var customDIA: StateFlow<Float>

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
                when (source) {
                    GlucoseSource.COMPANION -> { }
                    GlucoseSource.XDRIP_BROADCAST -> registerXdripReceiver()
                    GlucoseSource.NIGHTSCOUT_FOLLOWER -> startFollower()
                }
            }
        }

        // Treatment sync lifecycle
        scope.launch {
            settings.treatmentsSyncEnabled.collect { enabled ->
                treatmentSyncJob?.cancel()
                treatmentSyncJob = null
                if (enabled) {
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

        pusher.pushPending()
        scope.launch { nightscoutPuller.pullIfEmpty() }
        scope.launch { updateNotification() }
        startPeriodicJobs()
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
                val thirtyDaysAgo = System.currentTimeMillis() - PRUNE_RETENTION_DAYS * HOURS_PER_DAY * MINUTES_TO_MS
                dao.pruneBefore(thirtyDaysAgo)
                delay(PRUNE_INTERVAL_DAYS * HOURS_PER_DAY * MINUTES_TO_MS)
            }
        }
        scope.launch {
            while (isActive) {
                delay(STALE_CHECK_INTERVAL_SECONDS * SECONDS_TO_MS)
                val latest = dao.latestOnce()
                alertManager.checkStale(latest?.ts)
                updateNotification()
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
        treatmentSyncJob?.cancel()
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
        // PredictionComputer uses 12-min lookback internally; 15 min ensures sufficient history
        val alertReadings = dao.since(timestamp - LOOKBACK_MINUTES * MINUTES_TO_MS)
        alertManager.checkReading(reading, alertReadings, predMinutes.value)
        broadcastBgIfEnabled(reading)
        try {
            val mgr = GlanceAppWidgetManager(this@StrimmaService)
            mgr.getGlanceIds(StrimmaWidget::class.java).forEach { id ->
                StrimmaWidget().update(this@StrimmaService, id)
            }
        } catch (_: Exception) {}
    }

    private suspend fun updateNotification() {
        val latest = dao.latestOnce()?.takeUnless { reading ->
            (System.currentTimeMillis() - reading.ts) > AlertManager.STALE_THRESHOLD_MINUTES * MINUTES_TO_MS
        }
        val graphWindowMs = notifGraphMinutes.value.toLong() * MINUTES_TO_MS
        val recent = dao.since(System.currentTimeMillis() - graphWindowMs)

        val iob = if (treatmentsSyncEnabled.value) {
            val tau = IOBComputer.tauForInsulinType(insulinType.value, customDIA.value)
            val treatments = treatmentDao.insulinSince(System.currentTimeMillis() - IOBComputer.lookbackMs(tau))
            IOBComputer.computeIOB(treatments, System.currentTimeMillis(), tau)
        } else 0.0

        notificationHelper.updateNotification(
            latest, recent, bgLow.value.toDouble(), bgHigh.value.toDouble(),
            graphWindowMs, predMinutes.value, glucoseUnit.value, iob
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
}
