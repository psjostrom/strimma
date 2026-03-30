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

@Composable
fun GeneralSettings(
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    appVersion: String,
    isDebug: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    onOpenBatteryOptimization: () -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant

    SettingsScaffold(title = stringResource(R.string.settings_general_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_general_startup)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_general_start_on_boot), color = onBg, fontSize = 14.sp)
                    Text(
                        stringResource(R.string.settings_general_start_on_boot_desc),
                        color = outline,
                        fontSize = 12.sp
                    )
                }
                Switch(checked = startOnBoot, onCheckedChange = onStartOnBootChange)
            }
            HorizontalDivider(color = outlineVar)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_general_battery), color = onBg, fontSize = 14.sp)
                Text(
                    stringResource(R.string.settings_general_battery_desc),
                    color = outline,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isBatteryOptimizationIgnored) {
                    Text(
                        stringResource(R.string.settings_general_battery_allowed),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                } else {
                    OutlinedButton(onClick = onOpenBatteryOptimization) {
                        Text(stringResource(R.string.settings_general_battery_button))
                    }
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_general_about)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.settings_general_version), color = onBg, fontSize = 14.sp)
                Text(appVersion, color = outline, fontSize = 14.sp)
            }
            if (isDebug) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.settings_general_build), color = onBg, fontSize = 14.sp)
                    Text("Debug", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            }
        }
    }
}
