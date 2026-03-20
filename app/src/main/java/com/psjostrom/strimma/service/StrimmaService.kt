package com.psjostrom.strimma.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.psjostrom.strimma.data.*
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

@AndroidEntryPoint
class StrimmaService : Service() {

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

        predMinutes = settings.predictionMinutes.stateIn(scope, SharingStarted.Eagerly, 15)
        notifGraphMinutes = settings.notifGraphMinutes.stateIn(scope, SharingStarted.Eagerly, 60)
        glucoseUnit = settings.glucoseUnit.stateIn(scope, SharingStarted.Eagerly, GlucoseUnit.MMOL)
        bgLow = settings.bgLow.stateIn(scope, SharingStarted.Eagerly, 4.0f)
        bgHigh = settings.bgHigh.stateIn(scope, SharingStarted.Eagerly, 10.0f)
        bgBroadcastEnabled = settings.bgBroadcastEnabled.stateIn(scope, SharingStarted.Eagerly, false)
        treatmentsSyncEnabled = settings.treatmentsSyncEnabled.stateIn(scope, SharingStarted.Eagerly, false)
        insulinType = settings.insulinType.stateIn(scope, SharingStarted.Eagerly, InsulinType.FIASP)
        customDIA = settings.customDIA.stateIn(scope, SharingStarted.Eagerly, 5.0f)

        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildNotification(null, emptyList(), 4.0, 10.0),
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

        // Periodic retry for failed pushes + daily prune
        scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                pusher.pushPending()
            }
        }
        scope.launch {
            while (isActive) {
                val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                dao.pruneBefore(thirtyDaysAgo)
                delay(24 * 60 * 60 * 1000L)
            }
        }

        // Stale data check every 60 seconds
        scope.launch {
            while (isActive) {
                delay(60_000L)
                val latest = dao.latestOnce()
                alertManager.checkStale(latest?.ts)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED) {
            val mmol = intent.getDoubleExtra(GlucoseNotificationListener.EXTRA_MMOL, 0.0)
            val timestamp = intent.getLongExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, 0L)
            if (mmol > 0.0 && timestamp > 0L) {
                scope.launch { processReading(mmol, timestamp) }
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

    private fun registerXdripReceiverIfNeeded() {
        if (settings.getGlucoseSourceSync() == GlucoseSource.XDRIP_BROADCAST) {
            registerXdripReceiver()
        }
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
            val alertReadings = dao.since(reading.ts - 15 * 60_000L)
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

    private suspend fun processReading(mmol: Double, timestamp: Long) {
        val sgv = (mmol * 18.0182).toInt()
        val roundedMmol = Math.round(mmol * 10.0) / 10.0

        val existing = dao.lastN(1)
        if (existing.isNotEmpty() && (timestamp - existing[0].ts) < 3_000) return

        val recentReadings = dao.since(timestamp - 15 * 60 * 1000)
        val tempReading = GlucoseReading(
            ts = timestamp, sgv = sgv, mmol = roundedMmol,
            direction = "NONE", deltaMmol = null, pushed = 0
        )
        val (direction, deltaMmol) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            deltaMmol = deltaMmol?.let { Math.round(it * 10.0) / 10.0 }
        )

        dao.insert(reading)
        DebugLog.log("Stored: ${reading.mmol} ${direction.arrow}")
        pusher.pushReading(reading)
        updateNotification()
        // PredictionComputer uses 12-min lookback internally; 15 min ensures sufficient history
        val alertReadings = dao.since(timestamp - 15 * 60_000L)
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
        val latest = dao.latestOnce()
        val graphWindowMs = notifGraphMinutes.value.toLong() * 60_000L
        val recent = dao.since(System.currentTimeMillis() - graphWindowMs)

        val iob = if (treatmentsSyncEnabled.value) {
            val tau = IOBComputer.tauForInsulinType(insulinType.value, customDIA.value)
            val lookbackMs = (5.0 * tau * 60_000).toLong()
            val treatments = treatmentDao.insulinSince(System.currentTimeMillis() - lookbackMs)
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
            putExtra("com.eveningoutpost.dexdrip.Extras.BgSlope", (reading.deltaMmol ?: 0.0) / 5.0)
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
