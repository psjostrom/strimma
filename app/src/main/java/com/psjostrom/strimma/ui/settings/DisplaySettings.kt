package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.GlucoseUnit

@Composable
fun DisplaySettings(
    glucoseUnit: GlucoseUnit,
    graphWindowHours: Int,
    bgLow: Float,
    bgHigh: Float,
    themeMode: String,
    onGlucoseUnitChange: (GlucoseUnit) -> Unit,
    onGraphWindowChange: (Int) -> Unit,
    onBgLowChange: (Float) -> Unit,
    onBgHighChange: (Float) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground

    SettingsScaffold(title = "Display", onBack = onBack) {
        SettingsSection("Display") {
            Text("Unit", color = onBg, fontSize = 14.sp)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                GlucoseUnit.entries.forEachIndexed { index, unit ->
                    SegmentedButton(
                        selected = glucoseUnit == unit,
                        onClick = { onGlucoseUnitChange(unit) },
                        shape = SegmentedButtonDefaults.itemShape(index, GlucoseUnit.entries.size),
                    ) {
                        Text(unit.label)
                    }
                }
            }

            Text(
                "Graph Window: $graphWindowHours hours",
                color = onBg,
                fontSize = 14.sp
            )
            Slider(
                value = graphWindowHours.toFloat(),
                onValueChange = { onGraphWindowChange(it.toInt()) },
                valueRange = 1f..8f,
                steps = 6
            )

            var bgLowText by remember(bgLow, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(bgLow)) }
            OutlinedTextField(
                value = bgLowText,
                onValueChange = { text ->
                    bgLowText = text
                    glucoseUnit.parseThreshold(text)?.let { onBgLowChange(it) }
                },
                label = { Text("Low Threshold (${glucoseUnit.label})") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            var bgHighText by remember(bgHigh, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(bgHigh)) }
            OutlinedTextField(
                value = bgHighText,
                onValueChange = { text ->
                    bgHighText = text
                    glucoseUnit.parseThreshold(text)?.let { onBgHighChange(it) }
                },
                label = { Text("High Threshold (${glucoseUnit.label})") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            Text("Theme", color = onBg, fontSize = 14.sp)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Light", "Dark", "System").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = themeMode == label,
                        onClick = { onThemeModeChange(label) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
