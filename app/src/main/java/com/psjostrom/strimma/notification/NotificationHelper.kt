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

        // Graph dimensions
        private const val COLLAPSED_GRAPH_WIDTH = 900
        private const val COLLAPSED_GRAPH_HEIGHT = 180
        private const val EXPANDED_GRAPH_WIDTH = 800
        private const val EXPANDED_GRAPH_HEIGHT = 400

        // Icon dimensions
        private const val ICON_SIZE = 96
        private const val ICON_TEXT_SIZE_INITIAL = 96f
        private const val TEXT_SIZE_STEP = 2f
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
                glucoseUnit.formatDeltaCompact(it)
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
            val notifText = arrayOf(bgText, direction.arrow, deltaText)
            attachGraphViews(
                builder, recentReadings, bgLow, bgHigh,
                graphWindowMs, predictionMinutes, notifText
            )
        } else {
            builder.setSmallIcon(createBgIcon("--"))
            builder.setContentTitle("Strimma")
            builder.setContentText("Waiting for glucose data…")
        }

        return builder.build()
    }

    @Suppress("LongParameterList") // Graph rendering needs all dimensions
    private fun attachGraphViews(
        builder: NotificationCompat.Builder,
        recentReadings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        graphWindowMs: Long,
        predictionMinutes: Int,
        text: Array<String>
    ) {
        val (bgText, arrow, deltaText) = text
        val collapsed = RemoteViews(context.packageName, R.layout.notification_collapsed)
        collapsed.setTextViewText(R.id.tv_bg, bgText)
        collapsed.setTextViewText(R.id.tv_arrow, arrow)
        collapsed.setTextViewText(R.id.tv_delta, deltaText)
        val miniGraph = GraphRenderer.render(
            recentReadings, COLLAPSED_GRAPH_WIDTH, COLLAPSED_GRAPH_HEIGHT,
            bgLow, bgHigh, graphWindowMs, compact = true,
            predictionMinutes = predictionMinutes
        )
        collapsed.setImageViewBitmap(R.id.iv_graph, miniGraph)
        builder.setCustomContentView(collapsed)

        val expanded = RemoteViews(context.packageName, R.layout.notification_expanded)
        expanded.setTextViewText(R.id.tv_bg, bgText)
        expanded.setTextViewText(R.id.tv_arrow, arrow)
        expanded.setTextViewText(R.id.tv_delta, deltaText)
        val bigGraph = GraphRenderer.render(
            recentReadings, EXPANDED_GRAPH_WIDTH, EXPANDED_GRAPH_HEIGHT,
            bgLow, bgHigh, graphWindowMs,
            predictionMinutes = predictionMinutes
        )
        expanded.setImageViewBitmap(R.id.iv_graph, bigGraph)
        builder.setCustomBigContentView(expanded)
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
        val size = ICON_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = ICON_TEXT_SIZE_INITIAL
        }
        // Auto-size: shrink until text fits the bitmap width
        while (paint.measureText(text) > size) {
            paint.textSize -= TEXT_SIZE_STEP
        }
        val x = size / 2f
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, x, y, paint)
        return IconCompat.createWithBitmap(bitmap)
    }
}
