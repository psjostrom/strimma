package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.InsulinType
import com.psjostrom.strimma.data.meal.MealTimeSlotConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreatmentsSettings(
    treatmentsSyncEnabled: Boolean,
    insulinType: InsulinType,
    customDIA: Float,
    onTreatmentsSyncEnabledChange: (Boolean) -> Unit,
    onInsulinTypeChange: (InsulinType) -> Unit,
    onCustomDIAChange: (Float) -> Unit,
    onPullTreatments: (Int) -> Unit,
    mealTimeSlotConfig: MealTimeSlotConfig,
    onMealSlotChange: (String, Int) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline

    SettingsScaffold(title = stringResource(R.string.settings_treatments_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_treatments_section)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_treatments_sync), color = onBg, fontSize = 14.sp)
                    Text(
                        stringResource(R.string.settings_treatments_sync_desc),
                        color = outline,
                        fontSize = 12.sp
                    )
                }
                Switch(checked = treatmentsSyncEnabled, onCheckedChange = onTreatmentsSyncEnabledChange)
            }

            if (treatmentsSyncEnabled) {
                Text(stringResource(R.string.settings_treatments_insulin_type), color = onBg, fontSize = 14.sp)
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = stringResource(insulinType.labelRes),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        InsulinType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(stringResource(type.labelRes)) },
                                onClick = {
                                    onInsulinTypeChange(type)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                if (insulinType == InsulinType.CUSTOM) {
                    var diaText by remember(customDIA) { mutableStateOf("%.1f".format(customDIA)) }
                    OutlinedTextField(
                        value = diaText,
                        onValueChange = { text ->
                            diaText = text
                            text.toFloatOrNull()?.let { if (it in 2f..10f) onCustomDIAChange(it) }
                        },
                        label = { Text(stringResource(R.string.settings_treatments_custom_dia)) },
                        supportingText = { Text(stringResource(R.string.settings_treatments_dia_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            }
        }

        if (treatmentsSyncEnabled) {
            PullDataSection(
                title = stringResource(R.string.settings_data_pull_treatments),
                description = stringResource(R.string.settings_data_pull_treatments_desc),
                onPull = onPullTreatments
            )

            MealTimeSlotsSection(
                config = mealTimeSlotConfig,
                onSlotChange = onMealSlotChange
            )
        }
    }
}

@Composable
private fun MealTimeSlotsSection(
    config: MealTimeSlotConfig,
    onSlotChange: (String, Int) -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline

    SettingsSection(stringResource(R.string.settings_meal_slots_title)) {
        Text(
            stringResource(R.string.settings_meal_slots_desc),
            color = outline,
            fontSize = 12.sp
        )
        MealSlotRow(
            stringResource(R.string.meal_slot_breakfast),
            "breakfast",
            config.breakfastStart,
            config.breakfastEnd,
            onBg,
            outline,
            onSlotChange
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MealSlotRow(stringResource(R.string.meal_slot_lunch), "lunch", config.lunchStart, config.lunchEnd, onBg, outline, onSlotChange)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MealSlotRow(stringResource(R.string.meal_slot_dinner), "dinner", config.dinnerStart, config.dinnerEnd, onBg, outline, onSlotChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealSlotRow(
    label: String,
    key: String,
    startMinutes: Int,
    endMinutes: Int,
    textColor: androidx.compose.ui.graphics.Color,
    subtitleColor: androidx.compose.ui.graphics.Color,
    onSlotChange: (String, Int) -> Unit
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showStartPicker = true }) {
                Text(formatMinutes(startMinutes), fontSize = 14.sp)
            }
            Text("–", color = subtitleColor, modifier = Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { showEndPicker = true }) {
                Text(formatMinutes(endMinutes), fontSize = 14.sp)
            }
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            initialMinutes = startMinutes,
            onConfirm = { minutes ->
                onSlotChange("${key}_start", minutes)
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            initialMinutes = endMinutes,
            onConfirm = { minutes ->
                onSlotChange("${key}_end", minutes)
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / MINUTES_PER_HOUR,
        initialMinute = initialMinutes % MINUTES_PER_HOUR,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * MINUTES_PER_HOUR + state.minute) }) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        text = { TimePicker(state = state) }
    )
}

private const val MINUTES_PER_HOUR = 60

private fun formatMinutes(minutes: Int): String {
    val h = minutes / MINUTES_PER_HOUR
    val m = minutes % MINUTES_PER_HOUR
    return "%02d:%02d".format(h, m)
}
