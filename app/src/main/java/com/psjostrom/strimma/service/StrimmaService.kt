package com.psjostrom.strimma.service

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.psjostrom.strimma.data.*
import com.psjostrom.strimma.network.SpringaPusher
import com.psjostrom.strimma.notification.NotificationHelper
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.receiver.GlucoseNotificationListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class StrimmaService : Service() {

    @Inject lateinit var dao: ReadingDao
    @Inject lateinit var directionComputer: DirectionComputer
    @Inject lateinit var pusher: SpringaPusher
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pruneJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannel()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notificationHelper.buildNotification(null, emptyList(), 4.0, 10.0),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notificationHelper.buildNotification(null, emptyList(), 4.0, 10.0)
            )
        }

        DebugLog.log(message = "Service started")

        pusher.pushPending()
        scope.launch { updateNotification() }

        // Periodic retry for failed pushes + daily prune
        scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // every 5 minutes
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
        pruneJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun processReading(mmol: Double, timestamp: Long) {
        val sgv = (mmol * 18.0182).toInt()
        val roundedMmol = Math.round(mmol * 10.0) / 10.0

        // Deduplicate: CamAPS fires onNotificationPosted multiple times per
        // reading (~5-18ms apart). Libre 3 reads every ~60s, so 3s window is safe.
        val existing = dao.lastN(1)
        if (existing.isNotEmpty() && (timestamp - existing[0].ts) < 3_000) return

        // Direction computation
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
        DebugLog.log(message = "Stored: ${reading.mmol} ${direction.arrow}")
        pusher.pushReading(reading)
        updateNotification()
    }

    private suspend fun updateNotification() {
        val latest = dao.latest().first()
        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000
        val recent = dao.since(oneHourAgo)
        val bgLow = settings.bgLow.first().toDouble()
        val bgHigh = settings.bgHigh.first().toDouble()
        notificationHelper.updateNotification(latest, recent, bgLow, bgHigh)
    }
}
