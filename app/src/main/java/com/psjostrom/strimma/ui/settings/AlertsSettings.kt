package com.psjostrom.strimma.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.data.workout.AlertSet
import com.psjostrom.strimma.data.workout.NightWindow
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.ui.theme.InRange
import kotlinx.coroutines.flow.Flow

private val cooldownOptions = listOf(0, 5, 10, 15)

private val soundChannels = listOf(
    R.string.settings_alerts_urgent_low to AlertManager.CHANNEL_URGENT_LOW,
    R.string.settings_alerts_low to AlertManager.CHANNEL_LOW,
    R.string.settings_alerts_high to AlertManager.CHANNEL_HIGH,
    R.string.settings_alerts_urgent_high to AlertManager.CHANNEL_URGENT_HIGH,
    R.string.settings_alerts_low_soon to AlertManager.CHANNEL_LOW_SOON,
    R.string.settings_alerts_high_soon to AlertManager.CHANNEL_HIGH_SOON,
    R.string.settings_alerts_stale to AlertManager.CHANNEL_STALE,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSettings(
    glucoseUnit: GlucoseUnit,
    activeSet: AlertSet,
    dayProtocol: AlertProtocol,
    nightProtocol: AlertProtocol,
    exerciseProtocol: AlertProtocol,
    nightAlertsEnabled: Boolean,
    nightWindow: NightWindow,
    dayCallbacks: AlertProtocolCallbacks,
    nightCallbacks: AlertProtocolCallbacks,
    exerciseCallbacks: AlertProtocolCallbacks,
    onNightAlertsEnabledChange: (Boolean) -> Unit,
    onNightWindowStartChange: (Int) -> Unit,
    onNightWindowEndChange: (Int) -> Unit,
    alertCooldownMinutes: Int,
    alertSnoozeDuration: SnoozeDuration,
    onAlertCooldownChange: (Int) -> Unit,
    onAlertSnoozeDurationChange: (SnoozeDuration) -> Unit,
    patternAlertsEnabled: Boolean,
    onPatternAlertsEnabledChange: (Boolean) -> Unit,
    validationError: Flow<AlertsViewModel.ValidationError>,
    onOpenAlertSound: (String) -> Unit,
    onBack: () -> Unit,
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant
    val context = LocalContext.current
    val orderErrorMessage = stringResource(R.string.alert_threshold_order_error)
    val emptyWindowMessage = stringResource(R.string.settings_alerts_night_empty_window)

    LaunchedEffect(validationError) {
        validationError.collect { error ->
            val message = when (error) {
                AlertsViewModel.ValidationError.Order -> orderErrorMessage
                AlertsViewModel.ValidationError.EmptyWindow -> emptyWindowMessage
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    SettingsScaffold(title = stringResource(R.string.settings_alerts_title), onBack = onBack) {
        var selectedSet by rememberSaveable { mutableStateOf(activeSet) }
        SettingsSection(
            title = stringResource(R.string.settings_alerts_sets_section),
            header = { AlertSetTabs(selected = selectedSet, activeSet = activeSet, onSelect = { selectedSet = it }) },
        ) {
            when (selectedSet) {
                AlertSet.DAY -> AlertProtocolRows(
                    protocol = dayProtocol,
                    glucoseUnit = glucoseUnit,
                    callbacks = dayCallbacks,
                    textColor = onBg,
                    outlineColor = outline,
                    dividerColor = outlineVar,
                )
                AlertSet.NIGHT -> {
                    AlertToggleRow(
                        label = stringResource(R.string.settings_alerts_night_enabled),
                        description = stringResource(R.string.settings_alerts_night_enabled_desc),
                        enabled = nightAlertsEnabled,
                        onToggle = onNightAlertsEnabledChange,
                        textColor = onBg,
                        outlineColor = outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeField(
                            label = stringResource(R.string.settings_alerts_night_from),
                            minutes = nightWindow.startMinute,
                            onChange = onNightWindowStartChange,
                            modifier = Modifier.weight(1f),
                        )
                        TimeField(
                            label = stringResource(R.string.settings_alerts_night_to),
                            minutes = nightWindow.endMinute,
                            onChange = onNightWindowEndChange,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (nightAlertsEnabled) {
                        HorizontalDivider(color = outlineVar)
                        AlertProtocolRows(
                            protocol = nightProtocol,
                            glucoseUnit = glucoseUnit,
                            callbacks = nightCallbacks,
                            textColor = onBg,
                            outlineColor = outline,
                            dividerColor = outlineVar,
                        )
                    }
                }
                AlertSet.EXERCISE -> {
                    Text(
                        stringResource(R.string.settings_alerts_exercise_desc),
                        color = outline,
                        fontSize = 12.sp
                    )
                    AlertProtocolRows(
                        protocol = exerciseProtocol,
                        glucoseUnit = glucoseUnit,
                        callbacks = exerciseCallbacks,
                        textColor = onBg,
                        outlineColor = outline,
                        dividerColor = outlineVar,
                    )
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_alerts_sounds_section)) {
            Text(
                stringResource(R.string.settings_alerts_tap_sound),
                color = outline,
                fontSize = 12.sp
            )
            soundChannels.forEachIndexed { index, (labelRes, channelId) ->
                if (index > 0) HorizontalDivider(color = outlineVar)
                SoundRow(
                    label = stringResource(labelRes),
                    onClick = { onOpenAlertSound(channelId) },
                    textColor = onBg,
                    chevronColor = outline,
                )
            }
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

        SettingsSection(stringResource(R.string.settings_alerts_pattern_section)) {
            AlertToggleRow(
                label = stringResource(R.string.settings_alerts_pattern_title),
                description = stringResource(R.string.settings_alerts_pattern_desc),
                enabled = patternAlertsEnabled,
                onToggle = onPatternAlertsEnabledChange,
                textColor = onBg,
                outlineColor = outline,
            )
        }
    }
}

@Composable
private fun SoundRow(
    label: String,
    onClick: () -> Unit,
    textColor: Color,
    chevronColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = textColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = chevronColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertSetTabs(selected: AlertSet, activeSet: AlertSet, onSelect: (AlertSet) -> Unit) {
    val tabs = listOf(
        AlertSet.DAY to stringResource(R.string.alert_set_day),
        AlertSet.NIGHT to stringResource(R.string.alert_set_night),
        AlertSet.EXERCISE to stringResource(R.string.alert_set_exercise),
    )
    val activeNow = stringResource(R.string.settings_alerts_active_now)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        tabs.forEachIndexed { index, (set, label) ->
            SegmentedButton(
                selected = selected == set,
                onClick = { onSelect(set) },
                shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                icon = {},
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, maxLines = 1)
                    if (set == activeSet) {
                        Box(
                            Modifier
                                .padding(start = 6.dp)
                                .size(6.dp)
                                .background(InRange, CircleShape)
                                .semantics { contentDescription = activeNow }
                        )
                    }
                }
            }
        }
    }
}

// A read-only field cannot take clicks itself, so a transparent overlay opens the picker.
@Composable
private fun TimeField(label: String, minutes: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    val time = formatMinutes(minutes)
    // TalkBack gets one button that names the field and its time; the read-only text field underneath has no action of its own.
    Box(
        modifier.clearAndSetSemantics {
            contentDescription = "$label, $time"
            role = Role.Button
            onClick { showPicker = true; true }
        }
    ) {
        OutlinedTextField(
            value = time,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { showPicker = true })
    }
    if (showPicker) {
        TimePickerDialog(
            initialMinutes = minutes,
            onConfirm = {
                onChange(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun AlertProtocolRows(
    protocol: AlertProtocol,
    glucoseUnit: GlucoseUnit,
    callbacks: AlertProtocolCallbacks,
    textColor: Color,
    outlineColor: Color,
    dividerColor: Color,
) {
    AlertBlock(
        label = stringResource(R.string.settings_alerts_urgent_low),
        enabled = protocol.urgentLowEnabled,
        onToggle = callbacks.onUrgentLowEnabledChange,
        threshold = protocol.urgentLowMgdl,
        onThresholdChange = callbacks.onUrgentLowChange,
        thresholdLabel = stringResource(R.string.settings_alerts_urgent_low_threshold, glucoseUnit.label),
        glucoseUnit = glucoseUnit,
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

    HorizontalDivider(color = dividerColor)

    AlertToggleRow(
        label = stringResource(R.string.settings_alerts_high_soon),
        description = stringResource(R.string.settings_alerts_high_soon_desc),
        enabled = protocol.highSoonEnabled,
        onToggle = callbacks.onHighSoonEnabledChange,
        textColor = textColor,
        outlineColor = outlineColor,
    )

    HorizontalDivider(color = dividerColor)

    AlertToggleRow(
        label = stringResource(R.string.settings_alerts_stale),
        description = null,
        enabled = protocol.staleEnabled,
        onToggle = callbacks.onStaleEnabledChange,
        textColor = textColor,
        outlineColor = outlineColor,
    )
}

@Composable
private fun AlertToggleRow(
    label: String,
    description: String?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    textColor: Color,
    outlineColor: Color,
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
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
