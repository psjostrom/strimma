package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit

private const val MIN_HOURS = 1
private const val MAX_HOURS = 12

/**
 * Settings → Exercise → Workout mode subsection.
 * Caller passes the current [GlucoseUnit] (read from the parent Settings VM)
 * so the threshold inputs render in mmol or mg/dL consistently with the rest of the app.
 */
@Composable
fun WorkoutSettings(
    glucoseUnit: GlucoseUnit,
    viewModel: WorkoutSettingsViewModel = hiltViewModel()
) {
    val low by viewModel.workoutLow.collectAsState()
    val urgentLow by viewModel.workoutUrgentLow.collectAsState()
    val high by viewModel.workoutHigh.collectAsState()
    val urgentHigh by viewModel.workoutUrgentHigh.collectAsState()
    val maxHours by viewModel.maxHours.collectAsState()

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.workout_mode_settings_title),
            style = MaterialTheme.typography.titleMedium
        )

        Text(stringResource(R.string.workout_mode_auto_off_after, maxHours))
        Slider(
            value = maxHours.toFloat(),
            onValueChange = { viewModel.setMaxHours(it.toInt().coerceIn(MIN_HOURS, MAX_HOURS)) },
            valueRange = MIN_HOURS.toFloat()..MAX_HOURS.toFloat(),
            steps = MAX_HOURS - MIN_HOURS - 1
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.workout_mode_thresholds_header),
            style = MaterialTheme.typography.labelLarge
        )

        ThresholdInput(
            label = stringResource(R.string.workout_mode_threshold_low),
            mgdl = low,
            unit = glucoseUnit,
            onChange = viewModel::setLow
        )
        ThresholdInput(
            label = stringResource(R.string.workout_mode_threshold_urgent_low),
            mgdl = urgentLow,
            unit = glucoseUnit,
            onChange = viewModel::setUrgentLow
        )
        ThresholdInput(
            label = stringResource(R.string.workout_mode_threshold_high),
            mgdl = high,
            unit = glucoseUnit,
            onChange = viewModel::setHigh
        )
        ThresholdInput(
            label = stringResource(R.string.workout_mode_threshold_urgent_high),
            mgdl = urgentHigh,
            unit = glucoseUnit,
            onChange = viewModel::setUrgentHigh
        )

        TextButton(onClick = { viewModel.resetToDefaults() }) {
            Text(stringResource(R.string.workout_mode_reset_defaults))
        }

        Text(
            text = stringResource(R.string.workout_mode_settings_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThresholdInput(
    label: String,
    mgdl: Float,
    unit: GlucoseUnit,
    onChange: (Float) -> Unit
) {
    var text by remember(mgdl, unit) { mutableStateOf(unit.formatThreshold(mgdl)) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v
                unit.parseThreshold(v)?.let(onChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .padding(start = 12.dp)
                .width(96.dp)
        )
    }
}
