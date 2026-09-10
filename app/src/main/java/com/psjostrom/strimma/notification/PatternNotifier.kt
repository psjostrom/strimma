package com.psjostrom.strimma.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.pattern.GlucosePattern
import com.psjostrom.strimma.data.pattern.PatternResult
import com.psjostrom.strimma.data.pattern.PatternType
import com.psjostrom.strimma.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PatternNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val NOTIFICATION_ID_PATTERN = 200

        fun formatHourRange(startHour: Int, endHour: Int): String {
            return "%02d:00–%02d:00".format(startHour, endHour)
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun notifyPatterns(result: PatternResult, unit: GlucoseUnit) {
        val patterns = result.patterns
        if (patterns.isEmpty()) {
            cancel()
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_PATTERN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, AlertManager.CHANNEL_PATTERN)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        if (patterns.size == 1) {
            val p = patterns.first()
            val timeSpan = formatHourRange(p.startHour, p.endHour)
            val title = when (p.type) {
                PatternType.HIGH -> context.getString(R.string.pattern_notif_title_high, timeSpan)
                PatternType.LOW -> context.getString(R.string.pattern_notif_title_low, timeSpan)
            }
            val avgFormatted = unit.formatWithUnit(p.avgBgMgdl)
            val text = when (p.type) {
                PatternType.HIGH -> context.getString(
                    R.string.pattern_notif_body_high,
                    p.daysDetected,
                    p.daysEvaluated,
                    avgFormatted
                )
                PatternType.LOW -> context.getString(
                    R.string.pattern_notif_body_low,
                    p.daysDetected,
                    p.daysEvaluated,
                    avgFormatted
                )
            }
            builder.setContentTitle(title)
                .setContentText(text)
        } else {
            val title = context.getString(R.string.pattern_notif_title_multiple, patterns.size)
            val inboxStyle = NotificationCompat.InboxStyle().setBigContentTitle(title)
            for (p in patterns) {
                val timeSpan = formatHourRange(p.startHour, p.endHour)
                val line = when (p.type) {
                    PatternType.HIGH -> context.getString(
                        R.string.pattern_notif_item_high,
                        timeSpan,
                        p.daysDetected,
                        p.daysEvaluated
                    )
                    PatternType.LOW -> context.getString(
                        R.string.pattern_notif_item_low,
                        timeSpan,
                        p.daysDetected,
                        p.daysEvaluated
                    )
                }
                inboxStyle.addLine(line)
            }
            builder.setContentTitle(title)
                .setContentText(context.getString(R.string.pattern_notif_summary_multiple, patterns.size))
                .setStyle(inboxStyle)
        }

        notificationManager.notify(NOTIFICATION_ID_PATTERN, builder.build())
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID_PATTERN)
    }
}
