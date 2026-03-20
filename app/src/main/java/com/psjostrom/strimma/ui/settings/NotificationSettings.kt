package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

@Composable
fun NotificationSettings(
    notifGraphMinutes: Int,
    predictionMinutes: Int,
    onNotifGraphMinutesChange: (Int) -> Unit,
    onPredictionMinutesChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground

    SettingsScaffold(title = "Notifications", onBack = onBack) {
        SettingsSection("Notification Graph") {
            Text(
                "Time Range: ${if (notifGraphMinutes < 60) "${notifGraphMinutes}m" else "${notifGraphMinutes / 60}h"}",
                color = onBg,
                fontSize = 14.sp
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h").forEachIndexed { index, (minutes, label) ->
                    SegmentedButton(
                        selected = notifGraphMinutes == minutes,
                        onClick = { onNotifGraphMinutesChange(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index, 4),
                    ) {
                        Text(label)
                    }
                }
            }

            Text(
                "Prediction: ${if (predictionMinutes == 0) "Off" else "${predictionMinutes}m"}",
                color = onBg,
                fontSize = 14.sp
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(0 to "Off", 15 to "15m", 30 to "30m").forEachIndexed { index, (minutes, label) ->
                    SegmentedButton(
                        selected = predictionMinutes == minutes,
                        onClick = { onPredictionMinutesChange(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
