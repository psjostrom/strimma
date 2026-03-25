package com.psjostrom.strimma.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit

@Composable
fun SetupAlertsStep(
    glucoseUnit: GlucoseUnit,
    alertUrgentLowEnabled: Boolean,
    alertLowEnabled: Boolean,
    alertHighEnabled: Boolean,
    alertUrgentHighEnabled: Boolean,
    alertStaleEnabled: Boolean,
    alertLowSoonEnabled: Boolean,
    alertHighSoonEnabled: Boolean,
    alertUrgentLow: Float,
    alertLow: Float,
    alertHigh: Float,
    alertUrgentHigh: Float,
    onAlertUrgentLowEnabledChange: (Boolean) -> Unit,
    onAlertLowEnabledChange: (Boolean) -> Unit,
    onAlertHighEnabledChange: (Boolean) -> Unit,
    onAlertUrgentHighEnabledChange: (Boolean) -> Unit,
    onAlertStaleEnabledChange: (Boolean) -> Unit,
    onAlertLowSoonEnabledChange: (Boolean) -> Unit,
    onAlertHighSoonEnabledChange: (Boolean) -> Unit,
    onAlertUrgentLowChange: (Float) -> Unit,
    onAlertLowChange: (Float) -> Unit,
    onAlertHighChange: (Float) -> Unit,
    onAlertUrgentHighChange: (Float) -> Unit
) {
    val outlineVar = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactAlertRow(
                    label = stringResource(R.string.settings_alerts_urgent_low),
                    enabled = alertUrgentLowEnabled,
                    onToggle = onAlertUrgentLowEnabledChange,
                    threshold = alertUrgentLow,
                    onThresholdChange = onAlertUrgentLowChange,
                    glucoseUnit = glucoseUnit
                )
                HorizontalDivider(color = outlineVar)
                CompactAlertRow(
                    label = stringResource(R.string.settings_alerts_low),
                    enabled = alertLowEnabled,
                    onToggle = onAlertLowEnabledChange,
                    threshold = alertLow,
                    onThresholdChange = onAlertLowChange,
                    glucoseUnit = glucoseUnit
                )
                HorizontalDivider(color = outlineVar)
                CompactAlertRow(
                    label = stringResource(R.string.settings_alerts_high),
                    enabled = alertHighEnabled,
                    onToggle = onAlertHighEnabledChange,
                    threshold = alertHigh,
                    onThresholdChange = onAlertHighChange,
                    glucoseUnit = glucoseUnit
                )
                HorizontalDivider(color = outlineVar)
                CompactAlertRow(
                    label = stringResource(R.string.settings_alerts_urgent_high),
                    enabled = alertUrgentHighEnabled,
                    onToggle = onAlertUrgentHighEnabledChange,
                    threshold = alertUrgentHigh,
                    onThresholdChange = onAlertUrgentHighChange,
                    glucoseUnit = glucoseUnit
                )
                HorizontalDivider(color = outlineVar)
                SimpleToggleRow(
                    label = stringResource(R.string.settings_alerts_stale),
                    checked = alertStaleEnabled,
                    onCheckedChange = onAlertStaleEnabledChange
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SimpleToggleRow(
                    label = stringResource(R.string.settings_alerts_low_soon),
                    checked = alertLowSoonEnabled,
                    onCheckedChange = onAlertLowSoonEnabledChange
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SimpleToggleRow(
                    label = stringResource(R.string.settings_alerts_high_soon),
                    checked = alertHighSoonEnabled,
                    onCheckedChange = onAlertHighSoonEnabledChange
                )
            }
        }

        Text(
            stringResource(R.string.setup_alerts_footer),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun CompactAlertRow(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    glucoseUnit: GlucoseUnit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (enabled) {
            var text by remember(threshold, glucoseUnit) {
                mutableStateOf(glucoseUnit.formatThreshold(threshold))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v
                    glucoseUnit.parseThreshold(v)?.let { onThresholdChange(it) }
                },
                modifier = Modifier.width(80.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SimpleToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
