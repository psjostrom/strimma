package com.psjostrom.strimma.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.ui.theme.InRange
import kotlinx.coroutines.flow.Flow

private val cooldownOptions = listOf(0, 5, 10, 15)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSettings(
    glucoseUnit: GlucoseUnit,
    regularAlertProtocol: AlertProtocol,
    exerciseAlertProtocol: AlertProtocol,
    alertCooldownMinutes: Int,
    alertSnoozeDuration: SnoozeDuration,
    onAlertLowEnabledChange: (Boolean) -> Unit,
    onAlertHighEnabledChange: (Boolean) -> Unit,
    onAlertUrgentLowEnabledChange: (Boolean) -> Unit,
    onAlertUrgentHighEnabledChange: (Boolean) -> Unit,
    onAlertLowChange: (Float) -> Unit,
    onAlertHighChange: (Float) -> Unit,
    onAlertUrgentLowChange: (Float) -> Unit,
    onAlertUrgentHighChange: (Float) -> Unit,
    onAlertStaleEnabledChange: (Boolean) -> Unit,
    onAlertLowSoonEnabledChange: (Boolean) -> Unit,
    onAlertHighSoonEnabledChange: (Boolean) -> Unit,
    onAlertCooldownChange: (Int) -> Unit,
    onAlertSnoozeDurationChange: (SnoozeDuration) -> Unit,
    onExerciseAlertLowEnabledChange: (Boolean) -> Unit,
    onExerciseAlertHighEnabledChange: (Boolean) -> Unit,
    onExerciseAlertUrgentLowEnabledChange: (Boolean) -> Unit,
    onExerciseAlertUrgentHighEnabledChange: (Boolean) -> Unit,
    onExerciseAlertLowChange: (Float) -> Unit,
    onExerciseAlertHighChange: (Float) -> Unit,
    onExerciseAlertUrgentLowChange: (Float) -> Unit,
    onExerciseAlertUrgentHighChange: (Float) -> Unit,
    onExerciseAlertStaleEnabledChange: (Boolean) -> Unit,
    onExerciseAlertLowSoonEnabledChange: (Boolean) -> Unit,
    onExerciseAlertHighSoonEnabledChange: (Boolean) -> Unit,
    validationError: Flow<AlertsViewModel.ValidationError>,
    onOpenAlertSound: (String) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant
    val context = LocalContext.current
    val orderErrorMessage = stringResource(R.string.exercise_alert_threshold_order_error)

    LaunchedEffect(validationError) {
        validationError.collect { error ->
            if (error == AlertsViewModel.ValidationError.Order) {
                Toast.makeText(context, orderErrorMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    SettingsScaffold(title = stringResource(R.string.settings_alerts_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_alerts_section)) {
            Text(
                stringResource(R.string.settings_alerts_tap_sound),
                color = outline,
                fontSize = 12.sp
            )

            AlertProtocolRows(
                protocol = regularAlertProtocol,
                glucoseUnit = glucoseUnit,
                showSound = true,
                callbacks = AlertProtocolCallbacks(
                    onUrgentLowEnabledChange = onAlertUrgentLowEnabledChange,
                    onLowEnabledChange = onAlertLowEnabledChange,
                    onHighEnabledChange = onAlertHighEnabledChange,
                    onUrgentHighEnabledChange = onAlertUrgentHighEnabledChange,
                    onUrgentLowChange = onAlertUrgentLowChange,
                    onLowChange = onAlertLowChange,
                    onHighChange = onAlertHighChange,
                    onUrgentHighChange = onAlertUrgentHighChange,
                    onLowSoonEnabledChange = onAlertLowSoonEnabledChange,
                    onHighSoonEnabledChange = onAlertHighSoonEnabledChange,
                    onStaleEnabledChange = onAlertStaleEnabledChange,
                ),
                onOpenSound = onOpenAlertSound,
                textColor = onBg,
                outlineColor = outline,
                dividerColor = outlineVar,
            )
        }

        SettingsSection(stringResource(R.string.settings_alerts_exercise_section)) {
            Text(
                stringResource(R.string.settings_alerts_exercise_desc),
                color = outline,
                fontSize = 12.sp
            )

            AlertProtocolRows(
                protocol = exerciseAlertProtocol,
                glucoseUnit = glucoseUnit,
                showSound = false,
                callbacks = AlertProtocolCallbacks(
                    onUrgentLowEnabledChange = onExerciseAlertUrgentLowEnabledChange,
                    onLowEnabledChange = onExerciseAlertLowEnabledChange,
                    onHighEnabledChange = onExerciseAlertHighEnabledChange,
                    onUrgentHighEnabledChange = onExerciseAlertUrgentHighEnabledChange,
                    onUrgentLowChange = onExerciseAlertUrgentLowChange,
                    onLowChange = onExerciseAlertLowChange,
                    onHighChange = onExerciseAlertHighChange,
                    onUrgentHighChange = onExerciseAlertUrgentHighChange,
                    onLowSoonEnabledChange = onExerciseAlertLowSoonEnabledChange,
                    onHighSoonEnabledChange = onExerciseAlertHighSoonEnabledChange,
                    onStaleEnabledChange = onExerciseAlertStaleEnabledChange,
                ),
                onOpenSound = null,
                textColor = onBg,
                outlineColor = outline,
                dividerColor = outlineVar,
            )
        }

        SettingsSection(stringResource(R.string.settings_alerts_behavior_section)) {
            Text(stringResource(R.string.settings_alerts_snooze_duration), color = onBg, fontSize = 14.sp)
            Text(
                stringResource(R.string.settings_alerts_snooze_duration_desc),
                color = outline,
                fontSize = 12.sp
            )
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
                        selected = alertSnoozeDuration == dur,
                        onClick = { onAlertSnoozeDurationChange(dur) },
                        shape = SegmentedButtonDefaults.itemShape(index, durs.size),
                    ) {
                        Text(label)
                    }
                }
            }

            Text(stringResource(R.string.settings_alerts_cooldown), color = onBg, fontSize = 14.sp)
            Text(
                stringResource(R.string.settings_alerts_cooldown_desc),
                color = outline,
                fontSize = 12.sp
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                cooldownOptions.forEachIndexed { index, minutes ->
                    SegmentedButton(
                        selected = alertCooldownMinutes == minutes,
                        onClick = { onAlertCooldownChange(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index, cooldownOptions.size),
                        icon = {},
                    ) {
                        val labelResId = when (minutes) {
                            5 -> R.string.cooldown_label_5
                            10 -> R.string.cooldown_label_10
                            15 -> R.string.cooldown_label_15
                            else -> R.string.cooldown_off
                        }
                        Text(stringResource(labelResId))
                    }
                }
            }
        }
    }
}

private data class AlertProtocolCallbacks(
    val onUrgentLowEnabledChange: (Boolean) -> Unit,
    val onLowEnabledChange: (Boolean) -> Unit,
    val onHighEnabledChange: (Boolean) -> Unit,
    val onUrgentHighEnabledChange: (Boolean) -> Unit,
    val onUrgentLowChange: (Float) -> Unit,
    val onLowChange: (Float) -> Unit,
    val onHighChange: (Float) -> Unit,
    val onUrgentHighChange: (Float) -> Unit,
    val onLowSoonEnabledChange: (Boolean) -> Unit,
    val onHighSoonEnabledChange: (Boolean) -> Unit,
    val onStaleEnabledChange: (Boolean) -> Unit,
)

@Composable
private fun AlertProtocolRows(
    protocol: AlertProtocol,
    glucoseUnit: GlucoseUnit,
    showSound: Boolean,
    callbacks: AlertProtocolCallbacks,
    onOpenSound: ((String) -> Unit)?,
    textColor: androidx.compose.ui.graphics.Color,
    outlineColor: androidx.compose.ui.graphics.Color,
    dividerColor: androidx.compose.ui.graphics.Color,
) {
    AlertBlock(
        label = stringResource(R.string.settings_alerts_urgent_low),
        enabled = protocol.urgentLowEnabled,
        onToggle = callbacks.onUrgentLowEnabledChange,
        threshold = protocol.urgentLowMgdl,
        onThresholdChange = callbacks.onUrgentLowChange,
        thresholdLabel = stringResource(R.string.settings_alerts_urgent_low_threshold, glucoseUnit.label),
        glucoseUnit = glucoseUnit,
        channelId = if (showSound) AlertManager.CHANNEL_URGENT_LOW else null,
        onOpenSound = onOpenSound,
        textColor = textColor,
    )

    HorizontalDivider(color = dividerColor)

    AlertBlock(
        label = stringResource(R.string.settings_alerts_low),
        enabled = protocol.lowEnabled,
        onToggle = callbacks.onLowEnabledChange,
        threshold = protocol.lowMgdl,
        onThresholdChange = callbacks.onLowChange,
        thresholdLabel = stringResource(R.string.settings_alerts_low_threshold, glucoseUnit.label),
        glucoseUnit = glucoseUnit,
        channelId = if (showSound) AlertManager.CHANNEL_LOW else null,
        onOpenSound = onOpenSound,
        textColor = textColor,
    )

    HorizontalDivider(color = dividerColor)

    AlertBlock(
        label = stringResource(R.string.settings_alerts_high),
        enabled = protocol.highEnabled,
        onToggle = callbacks.onHighEnabledChange,
        threshold = protocol.highMgdl,
        onThresholdChange = callbacks.onHighChange,
        thresholdLabel = stringResource(R.string.settings_alerts_high_threshold, glucoseUnit.label),
        glucoseUnit = glucoseUnit,
        channelId = if (showSound) AlertManager.CHANNEL_HIGH else null,
        onOpenSound = onOpenSound,
        textColor = textColor,
    )

    HorizontalDivider(color = dividerColor)

    AlertBlock(
        label = stringResource(R.string.settings_alerts_urgent_high),
        enabled = protocol.urgentHighEnabled,
        onToggle = callbacks.onUrgentHighEnabledChange,
        threshold = protocol.urgentHighMgdl,
        onThresholdChange = callbacks.onUrgentHighChange,
        thresholdLabel = stringResource(R.string.settings_alerts_urgent_high_threshold, glucoseUnit.label),
        glucoseUnit = glucoseUnit,
        channelId = if (showSound) AlertManager.CHANNEL_URGENT_HIGH else null,
        onOpenSound = onOpenSound,
        textColor = textColor,
    )

    HorizontalDivider(color = dividerColor)

    AlertToggleRow(
        label = stringResource(R.string.settings_alerts_low_soon),
        description = stringResource(R.string.settings_alerts_low_soon_desc),
        enabled = protocol.lowSoonEnabled,
        onToggle = callbacks.onLowSoonEnabledChange,
        textColor = textColor,
        outlineColor = outlineColor,
    )
    if (showSound && protocol.lowSoonEnabled && onOpenSound != null) {
        TextButton(onClick = { onOpenSound(AlertManager.CHANNEL_LOW_SOON) }) {
            Text(stringResource(R.string.common_sound), color = InRange, fontSize = 13.sp)
        }
    }

    HorizontalDivider(color = dividerColor)

    AlertToggleRow(
        label = stringResource(R.string.settings_alerts_high_soon),
        description = stringResource(R.string.settings_alerts_high_soon_desc),
        enabled = protocol.highSoonEnabled,
        onToggle = callbacks.onHighSoonEnabledChange,
        textColor = textColor,
        outlineColor = outlineColor,
    )
    if (showSound && protocol.highSoonEnabled && onOpenSound != null) {
        TextButton(onClick = { onOpenSound(AlertManager.CHANNEL_HIGH_SOON) }) {
            Text(stringResource(R.string.common_sound), color = InRange, fontSize = 13.sp)
        }
    }

    HorizontalDivider(color = dividerColor)

    AlertToggleRow(
        label = stringResource(R.string.settings_alerts_stale),
        description = null,
        enabled = protocol.staleEnabled,
        onToggle = callbacks.onStaleEnabledChange,
        textColor = textColor,
        outlineColor = outlineColor,
    )
    if (showSound && protocol.staleEnabled && onOpenSound != null) {
        TextButton(onClick = { onOpenSound(AlertManager.CHANNEL_STALE) }) {
            Text(stringResource(R.string.common_sound), color = InRange, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AlertToggleRow(
    label: String,
    description: String?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    textColor: androidx.compose.ui.graphics.Color,
    outlineColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = textColor, fontSize = 14.sp)
            if (description != null) {
                Text(description, color = outlineColor, fontSize = 12.sp)
            }
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}
