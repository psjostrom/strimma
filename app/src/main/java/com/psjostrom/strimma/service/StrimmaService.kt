package com.psjostrom.strimma.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.psjostrom.strimma.data.*
import com.psjostrom.strimma.network.SpringaPusher
import com.psjostrom.strimma.notification.NotificationHelper
import com.psjostrom.strimma.receiver.AidexReceiver
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

    private var aidexReceiver: AidexReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pruneJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannel()

        // Start foreground immediately with placeholder notification
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildNotification(null, emptyList(), 4.0, 10.0)
        )

        // Register Aidex broadcast receiver dynamically
        aidexReceiver = AidexReceiver(dao, directionComputer, pusher) { reading ->
            scope.launch { updateNotification() }
        }
        val filter = IntentFilter(AidexReceiver.ACTION)
        registerReceiver(aidexReceiver, filter, RECEIVER_EXPORTED)

        // Push any pending readings from previous session
        pusher.pushPending()

        // Update notification with latest reading if available
        scope.launch { updateNotification() }

        // Schedule daily prune of old readings (30 days retention)
        pruneJob = scope.launch {
            while (isActive) {
                val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                dao.pruneBefore(thirtyDaysAgo)
                delay(24 * 60 * 60 * 1000L) // Once per day
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        aidexReceiver?.let { unregisterReceiver(it) }
        aidexReceiver = null
        pruneJob?.cancel()
        scope.cancel()
        super.onDestroy()
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
