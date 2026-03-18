package com.psjostrom.strimma.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.notification.GraphRenderer
import com.psjostrom.strimma.ui.MainActivity
import kotlinx.coroutines.flow.first

private const val GRAPH_WINDOW_MS = 2 * 60 * 60 * 1000L

class StrimmaWidget : GlanceAppWidget() {

    companion object {
        private val SMALL = DpSize(110.dp, 40.dp)
        private val LARGE = DpSize(180.dp, 100.dp)

        fun getOpacity(context: Context): Float {
            return context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                .getFloat("opacity", 0.85f)
        }
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = StrimmaDatabase.getInstance(context)
        val dao = db.readingDao()
        val latest = dao.latestOnce()
        val settings = SettingsRepository(context)
        val bgLow = settings.bgLow.first()
        val bgHigh = settings.bgHigh.first()
        val opacity = getOpacity(context)

        val since = System.currentTimeMillis() - GRAPH_WINDOW_MS
        val readings = dao.since(since)
        val graphBitmap = GraphRenderer.render(
            readings = readings,
            width = 800,
            height = 300,
            bgLow = bgLow.toDouble(),
            bgHigh = bgHigh.toDouble(),
            windowMs = GRAPH_WINDOW_MS,
            compact = true
        )

        provideContent {
            WidgetContent(latest, bgLow, bgHigh, graphBitmap, opacity)
        }
    }
}

@Composable
private fun WidgetContent(
    reading: GlucoseReading?,
    bgLow: Float,
    bgHigh: Float,
    graphBitmap: Bitmap,
    opacity: Float
) {
    val size = LocalSize.current
    val showGraph = size.height >= 90.dp

    val staleColor = ColorProvider(Color(0xFF636E7B))
    val mutedColor = ColorProvider(Color(0xFF8892A0))

    val statusColor = when {
        reading == null -> staleColor
        reading.mmol < bgLow -> ColorProvider(Color(0xFFFF6B6B))
        reading.mmol > bgHigh -> ColorProvider(Color(0xFFFFBE76))
        else -> ColorProvider(Color(0xFF56CCF2))
    }

    val minutesAgo = if (reading != null) {
        ((System.currentTimeMillis() - reading.ts) / 60_000).toInt()
    } else -1
    val isStale = minutesAgo > 10
    val textColor = if (isStale) staleColor else statusColor

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

    val bgColor = Color(0xFF0C1017).copy(alpha = opacity)

    if (showGraph) {
        // Large: graph background with text overlay
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Image(
                provider = ImageProvider(graphBitmap),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bgValue,
                        style = TextStyle(
                            color = textColor,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = " ${direction.arrow}",
                        style = TextStyle(color = textColor, fontSize = 20.sp)
                    )
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        text = subtitle,
                        style = TextStyle(
                            color = if (isStale) staleColor else mutedColor,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
    } else {
        // Small: text only, centered
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(16.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bgValue,
                    style = TextStyle(
                        color = textColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = " ${direction.arrow}",
                    style = TextStyle(color = textColor, fontSize = 18.sp)
                )
            }
            Text(
                text = subtitle,
                style = TextStyle(
                    color = if (isStale) staleColor else mutedColor,
                    fontSize = 11.sp
                )
            )
        }
    }
}
