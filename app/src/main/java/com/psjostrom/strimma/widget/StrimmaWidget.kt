package com.psjostrom.strimma.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.notification.GraphRenderer
import com.psjostrom.strimma.ui.MainActivity
import kotlinx.coroutines.flow.first

class StrimmaWidget : GlanceAppWidget() {

    companion object {
        val KEY_OPACITY = floatPreferencesKey("opacity")
        val KEY_GRAPH_MINUTES = intPreferencesKey("graph_minutes")
        val KEY_SHOW_PREDICTION = booleanPreferencesKey("show_prediction")
    }

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = StrimmaDatabase.getInstance(context)
        val dao = db.readingDao()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context))
        val bgLow = settings.bgLow.first()
        val bgHigh = settings.bgHigh.first()
        val glucoseUnit = settings.glucoseUnit.first()

        // Fetch Room data here (needs suspend). Max 3h — filter down in provideContent.
        val latest = dao.latestOnce()
        val allReadings = dao.since(System.currentTimeMillis() - MAX_WINDOW_MS)

        provideContent {
            // Read widget settings from Glance state — reactive to updateAppWidgetState
            val state = currentState<Preferences>()
            val opacity = state[KEY_OPACITY] ?: WidgetSettingsRepository.DEFAULT_OPACITY
            val graphMinutes = state[KEY_GRAPH_MINUTES] ?: WidgetSettingsRepository.DEFAULT_GRAPH_MINUTES
            val showPrediction = state[KEY_SHOW_PREDICTION] ?: WidgetSettingsRepository.DEFAULT_SHOW_PREDICTION
            val graphWindowMs = graphMinutes * 60_000L
            val predictionMinutes = if (showPrediction) 5 else 0

            val since = System.currentTimeMillis() - graphWindowMs
            val readings = allReadings.filter { it.ts >= since }
            val graphBitmap = GraphRenderer.render(
                readings = readings,
                width = 800,
                height = 300,
                bgLow = bgLow.toDouble(),
                bgHigh = bgHigh.toDouble(),
                windowMs = graphWindowMs,
                compact = true,
                predictionMinutes = predictionMinutes
            )

            WidgetContent(latest, bgLow, bgHigh, graphBitmap, opacity, glucoseUnit)
        }
    }
}

private const val MAX_WINDOW_MS = 180 * 60_000L // 3h — largest selectable graph window

@Composable
private fun WidgetContent(
    reading: GlucoseReading?,
    bgLow: Float,
    bgHigh: Float,
    graphBitmap: Bitmap,
    opacity: Float,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL
) {
    val staleColor = ColorProvider(Color(0xFF6A5F80))

    val statusColor = when {
        reading == null -> staleColor
        reading.mmol < bgLow -> ColorProvider(Color(0xFFFF4D6A))
        reading.mmol > bgHigh -> ColorProvider(Color(0xFFFFB800))
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

    val bgValue = reading?.let { glucoseUnit.format(it.mmol) } ?: "--"
    val deltaText = reading?.deltaMmol?.let {
        val sign = if (it >= 0) "+" else ""
        when (glucoseUnit) {
            GlucoseUnit.MMOL -> "$sign%.1f".format(it)
            GlucoseUnit.MGDL -> "$sign%.0f".format(it * GlucoseUnit.MGDL_FACTOR)
        }
    } ?: ""
    val timeText = when {
        minutesAgo < 0 -> "No data"
        minutesAgo == 0 -> "now"
        else -> "${minutesAgo}m"
    }
    val subtitle = if (deltaText.isNotEmpty()) "$deltaText · $timeText" else timeText

    val bgColor = Color(0xFF111018).copy(alpha = opacity)

    // Graph always shown as background, text overlaid — works at any size including 2×1
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

        // Text pinned to top-left, graph visible underneath
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(start = 8.dp, top = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bgValue,
                    style = TextStyle(
                        color = textColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = " ${direction.arrow}",
                    style = TextStyle(color = textColor, fontSize = 16.sp)
                )
            }
            Text(
                text = subtitle,
                style = TextStyle(
                    color = if (isStale) staleColor else ColorProvider(Color.White),
                    fontSize = 13.sp
                )
            )
        }
    }
}
