package com.psjostrom.strimma.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.ui.theme.DarkBg
import com.psjostrom.strimma.ui.theme.DarkSurfaceCard
import com.psjostrom.strimma.ui.theme.DarkTextPrimary
import com.psjostrom.strimma.ui.theme.DarkTextSecondary
import com.psjostrom.strimma.ui.theme.DarkTextTertiary
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import com.psjostrom.strimma.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var widgetSettings: WidgetSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                RESULT_CANCELED,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
        }

        setContent {
            StrimmaTheme(themeMode = ThemeMode.Dark) {
                WidgetConfigScreen(
                    initialOpacity = widgetSettings.getOpacity(),
                    initialGraphMinutes = widgetSettings.getGraphMinutes(),
                    initialShowPrediction = widgetSettings.getShowPrediction(),
                    onSave = { opacity, graphMinutes, showPrediction ->
                        widgetSettings.save(opacity, graphMinutes, showPrediction)

                        val appContext = applicationContext
                        lifecycleScope.launch {
                            val manager = GlanceAppWidgetManager(appContext)
                            val ids = manager.getGlanceIds(StrimmaWidget::class.java)
                            for (id in ids) {
                                updateAppWidgetState(appContext, id) {
                                    it[StrimmaWidget.KEY_OPACITY] = opacity
                                    it[StrimmaWidget.KEY_GRAPH_MINUTES] = graphMinutes
                                    it[StrimmaWidget.KEY_SHOW_PREDICTION] = showPrediction
                                }
                                StrimmaWidget().update(appContext, id)
                            }
                            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                )
                            }
                            finish()
                        }
                    }
                )
            }
        }
    }
}

private val GRAPH_OPTIONS = listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h")

@Composable
private fun WidgetConfigScreen(
    initialOpacity: Float,
    initialGraphMinutes: Int,
    initialShowPrediction: Boolean,
    onSave: (Float, Int, Boolean) -> Unit
) {
    var opacity by remember { mutableFloatStateOf(initialOpacity) }
    var graphMinutes by remember { mutableIntStateOf(initialGraphMinutes) }
    var showPrediction by remember { mutableStateOf(initialShowPrediction) }

    Scaffold(containerColor = DarkBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Widget Settings",
                color = DarkTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(32.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurfaceCard
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Graph time range
                    Text("Graph Range", color = DarkTextPrimary, fontSize = 14.sp)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        GRAPH_OPTIONS.forEachIndexed { index, (minutes, label) ->
                            SegmentedButton(
                                selected = graphMinutes == minutes,
                                onClick = { graphMinutes = minutes },
                                shape = SegmentedButtonDefaults.itemShape(index, GRAPH_OPTIONS.size)
                            ) {
                                Text(label)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Prediction", color = DarkTextPrimary, fontSize = 14.sp)
                        Switch(checked = showPrediction, onCheckedChange = { showPrediction = it })
                    }

                    HorizontalDivider(color = DarkTextTertiary.copy(alpha = 0.3f))

                    // Background opacity
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Background", color = DarkTextPrimary, fontSize = 14.sp)
                        Text(
                            "${(opacity * 100).toInt()}%",
                            color = DarkTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        valueRange = 0.0f..1.0f
                    )
                    Text(
                        "Controls background only — graph and values stay fully visible",
                        color = DarkTextTertiary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onSave(opacity, graphMinutes, showPrediction) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = InRange)
            ) {
                Text("Save", color = DarkBg, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
