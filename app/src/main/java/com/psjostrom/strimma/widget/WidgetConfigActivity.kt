package com.psjostrom.strimma.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.psjostrom.strimma.ui.theme.DarkBg
import com.psjostrom.strimma.ui.theme.DarkSurfaceCard
import com.psjostrom.strimma.ui.theme.DarkTextPrimary
import com.psjostrom.strimma.ui.theme.DarkTextSecondary
import com.psjostrom.strimma.ui.theme.DarkTextTertiary
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import com.psjostrom.strimma.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val prefs = getSharedPreferences("widget_prefs", MODE_PRIVATE)

        setContent {
            StrimmaTheme(themeMode = ThemeMode.Dark) {
                WidgetConfigScreen(
                    initialOpacity = prefs.getFloat("opacity", 0.85f),
                    onSave = { opacity ->
                        prefs.edit().putFloat("opacity", opacity).apply()
                        lifecycleScope.launch {
                            val manager = GlanceAppWidgetManager(this@WidgetConfigActivity)
                            manager.getGlanceIds(StrimmaWidget::class.java).forEach { id ->
                                StrimmaWidget().update(this@WidgetConfigActivity, id)
                            }
                        }
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        )
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    initialOpacity: Float,
    onSave: (Float) -> Unit
) {
    var opacity by remember { mutableFloatStateOf(initialOpacity) }

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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Opacity", color = DarkTextPrimary, fontSize = 14.sp)
                        Text(
                            "${(opacity * 100).toInt()}%",
                            color = DarkTextSecondary,
                            fontSize = 14.sp
                        )
                    }

                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        valueRange = 0.3f..1.0f
                    )

                    Text(
                        "Lower values let your wallpaper show through",
                        color = DarkTextTertiary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onSave(opacity) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = InRange)
            ) {
                Text("Save", color = DarkBg, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
