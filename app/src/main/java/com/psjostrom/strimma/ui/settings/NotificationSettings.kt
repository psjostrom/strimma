package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R

@Composable
fun NotificationSettings(
    notifGraphMinutes: Int,
    predictionMinutes: Int,
    onNotifGraphMinutesChange: (Int) -> Unit,
    onPredictionMinutesChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground

    SettingsScaffold(title = stringResource(R.string.settings_notif_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_notif_section)) {
            Text(
                if (notifGraphMinutes < 60) stringResource(R.string.settings_notif_time_range_minutes, notifGraphMinutes)
                else stringResource(R.string.settings_notif_time_range_hours, notifGraphMinutes / 60),
                color = onBg,
                fontSize = 14.sp
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    30 to stringResource(R.string.settings_notif_30m),
                    60 to stringResource(R.string.settings_notif_1h),
                    120 to stringResource(R.string.settings_notif_2h),
                    180 to stringResource(R.string.settings_notif_3h)
                ).forEachIndexed { index, (minutes, label) ->
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
                if (predictionMinutes == 0) stringResource(R.string.settings_notif_prediction_off)
                else stringResource(R.string.settings_notif_prediction_minutes, predictionMinutes),
                color = onBg,
                fontSize = 14.sp
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    0 to stringResource(R.string.settings_notif_off),
                    15 to stringResource(R.string.settings_notif_15m),
                    30 to stringResource(R.string.settings_notif_30m)
                ).forEachIndexed { index, (minutes, label) ->
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
