package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.InsulinType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreatmentsSettings(
    treatmentsSyncEnabled: Boolean,
    insulinType: InsulinType,
    customDIA: Float,
    onTreatmentsSyncEnabledChange: (Boolean) -> Unit,
    onInsulinTypeChange: (InsulinType) -> Unit,
    onCustomDIAChange: (Float) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline

    SettingsScaffold(title = "Treatments", onBack = onBack) {
        SettingsSection("Treatments") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Treatments sync", color = onBg, fontSize = 14.sp)
                    Text(
                        "Fetch bolus, carb, and basal data for IOB display",
                        color = outline,
                        fontSize = 12.sp
                    )
                }
                Switch(checked = treatmentsSyncEnabled, onCheckedChange = onTreatmentsSyncEnabledChange)
            }

            if (treatmentsSyncEnabled) {
                Text("Insulin type", color = onBg, fontSize = 14.sp)
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
                        label = { Text("Custom DIA (hours)") },
                        supportingText = { Text("Duration of Insulin Action, 2-10 hours") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            }
        }
    }
}
