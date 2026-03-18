package com.psjostrom.strimma.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.ui.MainActivity
import kotlinx.coroutines.flow.first

class StrimmaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = StrimmaDatabase.getInstance(context)
        val latest = db.readingDao().latestOnce()
        val settings = SettingsRepository(context)
        val bgLow = settings.bgLow.first()
        val bgHigh = settings.bgHigh.first()

        provideContent {
            WidgetContent(latest, bgLow, bgHigh)
        }
    }
}

@Composable
private fun WidgetContent(
    reading: com.psjostrom.strimma.data.GlucoseReading?,
    bgLow: Float,
    bgHigh: Float
) {
    val statusColor = when {
        reading == null -> ColorProvider(Color(0xFF636E7B))
        reading.mmol < bgLow -> ColorProvider(Color(0xFFFF6B6B))
        reading.mmol > bgHigh -> ColorProvider(Color(0xFFFFBE76))
        else -> ColorProvider(Color(0xFF56CCF2))
    }

    val minutesAgo = if (reading != null) {
        ((System.currentTimeMillis() - reading.ts) / 60_000).toInt()
    } else -1

    val isStale = minutesAgo > 10
    if (isStale && reading != null) {
        // Override to stale color
    }

    val direction = reading?.let {
        try { Direction.valueOf(it.direction) } catch (_: Exception) { Direction.NONE }
    } ?: Direction.NONE

    val bgValue = reading?.let { "%.1f".format(it.mmol) } ?: "--"

    val deltaText = reading?.deltaMmol?.let {
        val sign = if (it >= 0) "+" else ""
        "$sign%.1f".format(it)
    } ?: ""

    val timeText = when {
        minutesAgo < 0 -> "No data"
        minutesAgo == 0 -> "now"
        else -> "${minutesAgo}m"
    }

    val subtitle = if (deltaText.isNotEmpty()) "$deltaText · $timeText" else timeText

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF0C1017))
            .cornerRadius(16.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = bgValue,
                style = TextStyle(
                    color = if (isStale) ColorProvider(Color(0xFF636E7B)) else statusColor,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = " ${direction.arrow}",
                style = TextStyle(
                    color = if (isStale) ColorProvider(Color(0xFF636E7B)) else statusColor,
                    fontSize = 22.sp
                )
            )
        }
        Text(
            text = subtitle,
            style = TextStyle(
                color = if (isStale) ColorProvider(Color(0xFF636E7B)) else ColorProvider(Color(0xFF8892A0)),
                fontSize = 12.sp
            )
        )
    }
}
