package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.notification.NotificationActionType
import com.psjostrom.strimma.notification.SnoozeCategory
import com.psjostrom.strimma.notification.SnoozeDuration

@Composable
fun NotificationSettings(
    notifGraphMinutes: Int,
    predictionMinutes: Int,
    actionType: NotificationActionType,
    snoozeCategory: SnoozeCategory,
    snoozeDuration: SnoozeDuration,
    onNotifGraphMinutesChange: (Int) -> Unit,
    onPredictionMinutesChange: (Int) -> Unit,
    onActionTypeChange: (NotificationActionType) -> Unit,
    onSnoozeCategoryChange: (SnoozeCategory) -> Unit,
    onSnoozeDurationChange: (SnoozeDuration) -> Unit,
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

        SettingsSection(stringResource(R.string.settings_notif_action_section)) {
            Text(stringResource(R.string.settings_notif_action_label), color = onBg, fontSize = 14.sp)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    NotificationActionType.NONE to stringResource(R.string.settings_notif_action_none),
                    NotificationActionType.WORKOUT_TOGGLE to stringResource(R.string.settings_notif_action_workout),
                    NotificationActionType.SNOOZE to stringResource(R.string.settings_notif_action_snooze),
                )
                options.forEachIndexed { index, (type, label) ->
                    SegmentedButton(
                        selected = actionType == type,
                        onClick = { onActionTypeChange(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) {
                        Text(label)
                    }
                }
            }

            if (actionType == NotificationActionType.SNOOZE) {
                Text(stringResource(R.string.settings_notif_snooze_category_label), color = onBg, fontSize = 14.sp)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val cats = listOf(
                        SnoozeCategory.ALL to stringResource(R.string.settings_notif_snooze_category_all),
                        SnoozeCategory.HIGH to stringResource(R.string.settings_notif_snooze_category_high),
                        SnoozeCategory.LOW to stringResource(R.string.settings_notif_snooze_category_low),
                    )
                    cats.forEachIndexed { index, (cat, label) ->
                        SegmentedButton(
                            selected = snoozeCategory == cat,
                            onClick = { onSnoozeCategoryChange(cat) },
                            shape = SegmentedButtonDefaults.itemShape(index, cats.size),
                        ) {
                            Text(label)
                        }
                    }
                }

                Text(stringResource(R.string.settings_notif_snooze_duration_label), color = onBg, fontSize = 14.sp)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val durs = listOf(
                        SnoozeDuration.M15 to stringResource(R.string.snooze_duration_15m),
                        SnoozeDuration.M30 to stringResource(R.string.snooze_duration_30m),
                        SnoozeDuration.H1 to stringResource(R.string.snooze_duration_1h),
                        SnoozeDuration.H2 to stringResource(R.string.snooze_duration_2h),
                        SnoozeDuration.H3 to stringResource(R.string.snooze_duration_3h),
                    )
                    durs.forEachIndexed { index, (dur, label) ->
                        SegmentedButton(
                            selected = snoozeDuration == dur,
                            onClick = { onSnoozeDurationChange(dur) },
                            shape = SegmentedButtonDefaults.itemShape(index, durs.size),
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}
