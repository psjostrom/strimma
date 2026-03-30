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
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.notification.GraphRenderer
import com.psjostrom.strimma.ui.MainActivity
import kotlinx.coroutines.flow.first

class StrimmaWidget : GlanceAppWidget() {

    companion object {
        val KEY_OPACITY = floatPreferencesKey("opacity")
        val KEY_GRAPH_MINUTES = intPreferencesKey("graph_minutes")
        val KEY_SHOW_PREDICTION = booleanPreferencesKey("show_prediction")
        val KEY_LIGHT_MODE = booleanPreferencesKey("light_mode")
        val KEY_COLOR_CODED = booleanPreferencesKey("color_coded")

        private val MAX_WINDOW_MS = 180 * MS_PER_MINUTE // 3h — largest selectable graph window
        private const val DEFAULT_PREDICTION_MINUTES = 5
        val STALE_THRESHOLD_MINUTES = AlertManager.STALE_THRESHOLD_MINUTES
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
            val lightMode = state[KEY_LIGHT_MODE] ?: WidgetSettingsRepository.DEFAULT_LIGHT_MODE
            val colorCoded = state[KEY_COLOR_CODED] ?: WidgetSettingsRepository.DEFAULT_COLOR_CODED
            val graphWindowMs = graphMinutes * MS_PER_MINUTE
            val predictionMinutes = if (showPrediction) DEFAULT_PREDICTION_MINUTES else 0

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

            WidgetContent(latest, bgLow, bgHigh, graphBitmap, opacity, glucoseUnit, lightMode, colorCoded)
        }
    }
}

@Composable
private fun WidgetContent(
    reading: GlucoseReading?,
    bgLow: Float,
    bgHigh: Float,
    graphBitmap: Bitmap,
    opacity: Float,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    lightMode: Boolean = false,
    colorCoded: Boolean = false
) {
    val ctx = LocalContext.current
    val staleColor = ColorProvider(Color(0xFF6A5F80))

    val minutesAgo = if (reading != null) {
        ((System.currentTimeMillis() - reading.ts) / 60_000).toInt()
    } else -1
    val isStale = minutesAgo > StrimmaWidget.STALE_THRESHOLD_MINUTES

    val textColor = when {
        isStale || reading == null -> staleColor
        colorCoded -> when {
            reading.sgv < bgLow -> ColorProvider(Color(0xFFFF4D6A))
            reading.sgv > bgHigh -> ColorProvider(Color(0xFFFFB800))
            else -> ColorProvider(Color(0xFF56CCF2))
        }
        lightMode -> ColorProvider(Color(0xFF18151F))  // LightTextPrimary
        else -> ColorProvider(Color.White)
    }
    val subtitleColor = when {
        isStale -> staleColor
        lightMode -> ColorProvider(Color(0xFF6A5F80))  // LightTextSecondary
        else -> ColorProvider(Color.White)
    }

    val direction = reading?.let {
        Direction.parse(it.direction)
    } ?: Direction.NONE

    val bgValue = reading?.let { glucoseUnit.format(it.sgv) } ?: "--"
    val deltaText = reading?.delta?.let { glucoseUnit.formatDeltaCompact(it) } ?: ""
    val timeText = when {
        minutesAgo < 0 -> ctx.getString(R.string.common_no_data)
        minutesAgo == 0 -> ctx.getString(R.string.widget_now)
        else -> ctx.getString(R.string.widget_minutes_suffix, minutesAgo)
    }
    val subtitle = if (deltaText.isNotEmpty()) "$deltaText · $timeText" else timeText

    val bgColor = if (lightMode) {
        Color(0xFFF4F2F7).copy(alpha = opacity)  // LightBg
    } else {
        Color(0xFF111018).copy(alpha = opacity)
    }

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
                    color = subtitleColor,
                    fontSize = 13.sp
                )
            )
        }
    }
}
