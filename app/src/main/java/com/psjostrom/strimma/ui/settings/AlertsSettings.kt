package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun AlertsSettings(
    glucoseUnit: GlucoseUnit,
    alertLowEnabled: Boolean,
    alertHighEnabled: Boolean,
    alertUrgentLowEnabled: Boolean,
    alertUrgentHighEnabled: Boolean,
    alertLow: Float,
    alertHigh: Float,
    alertUrgentLow: Float,
    alertUrgentHigh: Float,
    alertStaleEnabled: Boolean,
    alertLowSoonEnabled: Boolean,
    alertHighSoonEnabled: Boolean,
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
    onOpenAlertSound: (String) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant

    SettingsScaffold(title = stringResource(R.string.settings_alerts_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_alerts_section)) {
            Text(
                stringResource(R.string.settings_alerts_tap_sound),
                color = outline,
                fontSize = 12.sp
            )

            AlertBlock(
                label = stringResource(R.string.settings_alerts_urgent_low),
                enabled = alertUrgentLowEnabled,
                onToggle = onAlertUrgentLowEnabledChange,
                threshold = alertUrgentLow,
                onThresholdChange = onAlertUrgentLowChange,
                thresholdLabel = stringResource(R.string.settings_alerts_urgent_low_threshold, glucoseUnit.label),
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_URGENT_LOW,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            AlertBlock(
                label = stringResource(R.string.settings_alerts_low),
                enabled = alertLowEnabled,
                onToggle = onAlertLowEnabledChange,
                threshold = alertLow,
                onThresholdChange = onAlertLowChange,
                thresholdLabel = stringResource(R.string.settings_alerts_low_threshold, glucoseUnit.label),
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_LOW,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            AlertBlock(
                label = stringResource(R.string.settings_alerts_high),
                enabled = alertHighEnabled,
                onToggle = onAlertHighEnabledChange,
                threshold = alertHigh,
                onThresholdChange = onAlertHighChange,
                thresholdLabel = stringResource(R.string.settings_alerts_high_threshold, glucoseUnit.label),
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_HIGH,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            AlertBlock(
                label = stringResource(R.string.settings_alerts_urgent_high),
                enabled = alertUrgentHighEnabled,
                onToggle = onAlertUrgentHighEnabledChange,
                threshold = alertUrgentHigh,
                onThresholdChange = onAlertUrgentHighChange,
                thresholdLabel = stringResource(R.string.settings_alerts_urgent_high_threshold, glucoseUnit.label),
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_URGENT_HIGH,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_alerts_low_soon), color = onBg, fontSize = 14.sp)
                    Text(stringResource(R.string.settings_alerts_low_soon_desc), color = outline, fontSize = 12.sp)
                }
                Switch(checked = alertLowSoonEnabled, onCheckedChange = onAlertLowSoonEnabledChange)
            }
            if (alertLowSoonEnabled) {
                TextButton(onClick = { onOpenAlertSound(AlertManager.CHANNEL_LOW_SOON) }) {
                    Text(stringResource(R.string.common_sound), color = InRange, fontSize = 13.sp)
                }
            }

            HorizontalDivider(color = outlineVar)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_alerts_high_soon), color = onBg, fontSize = 14.sp)
                    Text(stringResource(R.string.settings_alerts_high_soon_desc), color = outline, fontSize = 12.sp)
                }
                Switch(checked = alertHighSoonEnabled, onCheckedChange = onAlertHighSoonEnabledChange)
            }
            if (alertHighSoonEnabled) {
                TextButton(onClick = { onOpenAlertSound(AlertManager.CHANNEL_HIGH_SOON) }) {
                    Text(stringResource(R.string.common_sound), color = InRange, fontSize = 13.sp)
                }
            }

            HorizontalDivider(color = outlineVar)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_alerts_stale), color = onBg, fontSize = 14.sp)
                Switch(checked = alertStaleEnabled, onCheckedChange = onAlertStaleEnabledChange)
            }
            if (alertStaleEnabled) {
                TextButton(onClick = { onOpenAlertSound(AlertManager.CHANNEL_STALE) }) {
                    Text(stringResource(R.string.common_sound), color = InRange, fontSize = 13.sp)
                }
            }
        }
    }
}
