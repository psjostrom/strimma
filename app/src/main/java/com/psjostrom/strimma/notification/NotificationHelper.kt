package com.psjostrom.strimma.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "strimma_glucose"
        const val NOTIFICATION_ID = 1
        private const val GRAPH_WINDOW_MS = 60 * 60 * 1000L // 1 hour for notifications
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun buildNotification(
        reading: GlucoseReading?,
        recentReadings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        graphWindowMs: Long = GRAPH_WINDOW_MS,
        predictionMinutes: Int = 10,
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
        iob: Double = 0.0
    ): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setColor(ContextCompat.getColor(context, R.color.brand_accent))

        if (reading != null) {
            val direction = try { Direction.valueOf(reading.direction) } catch (_: Exception) { Direction.NONE }
            val bgText = glucoseUnit.format(reading.mmol)
            val baseDelta = reading.deltaMmol?.let {
                glucoseUnit.formatDelta(it)
            } ?: ""

            val prediction = PredictionComputer.compute(recentReadings, predictionMinutes, bgLow, bgHigh)
            val crossingText = prediction?.crossing?.let { crossing ->
                when (crossing.type) {
                    CrossingType.LOW -> "Low ${crossing.minutesUntil}m"
                    CrossingType.HIGH -> "High ${crossing.minutesUntil}m"
                }
            }
            val iobText = if (iob > 0.0) "IOB ${"%.1f".format(iob)}U" else null
            val deltaText = listOfNotNull(
                baseDelta.ifEmpty { null },
                crossingText,
                iobText
            ).joinToString(" · ")

            builder.setSmallIcon(createBgIcon(bgText))

            // Collapsed view with compact mini graph
            val collapsed = RemoteViews(context.packageName, R.layout.notification_collapsed)
            collapsed.setTextViewText(R.id.tv_bg, bgText)
            collapsed.setTextViewText(R.id.tv_arrow, direction.arrow)
            collapsed.setTextViewText(R.id.tv_delta, deltaText)
            val miniGraph = GraphRenderer.render(
                recentReadings, 900, 180, bgLow, bgHigh, graphWindowMs, compact = true, predictionMinutes = predictionMinutes
            )
            collapsed.setImageViewBitmap(R.id.iv_graph, miniGraph)
            builder.setCustomContentView(collapsed)

            // Expanded view
            val expanded = RemoteViews(context.packageName, R.layout.notification_expanded)
            expanded.setTextViewText(R.id.tv_bg, bgText)
            expanded.setTextViewText(R.id.tv_arrow, direction.arrow)
            expanded.setTextViewText(R.id.tv_delta, deltaText)
            val bigGraph = GraphRenderer.render(
                recentReadings, 800, 400, bgLow, bgHigh, graphWindowMs, predictionMinutes = predictionMinutes
            )
            expanded.setImageViewBitmap(R.id.iv_graph, bigGraph)
            builder.setCustomBigContentView(expanded)
        } else {
            builder.setSmallIcon(createBgIcon("--"))
            builder.setContentTitle("Strimma")
            builder.setContentText("Waiting for glucose data…")
        }

        return builder.build()
    }

    fun updateNotification(
        reading: GlucoseReading?,
        recentReadings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        graphWindowMs: Long = GRAPH_WINDOW_MS,
        predictionMinutes: Int = 10,
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
        iob: Double = 0.0
    ) {
        val notification = buildNotification(reading, recentReadings, bgLow, bgHigh, graphWindowMs, predictionMinutes, glucoseUnit, iob)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createBgIcon(text: String): IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 96f
        }
        // Auto-size: shrink until text fits the bitmap width
        while (paint.measureText(text) > size) {
            paint.textSize -= 2f
        }
        val x = size / 2f
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, x, y, paint)
        return IconCompat.createWithBitmap(bitmap)
    }
}
