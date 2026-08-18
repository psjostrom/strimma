package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.psjostrom.strimma.R

private const val MIN_HOURS = 1
private const val MAX_HOURS = 12

@Composable
fun WorkoutSettings(
    viewModel: WorkoutSettingsViewModel = hiltViewModel()
) {
    val maxHours by viewModel.maxHours.collectAsState()
    val outline = MaterialTheme.colorScheme.outline

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(pluralStringResource(R.plurals.workout_mode_auto_off_after_plural, maxHours, maxHours))
        Slider(
            value = maxHours.toFloat(),
            onValueChange = { viewModel.setMaxHours(it.toInt().coerceIn(MIN_HOURS, MAX_HOURS)) },
            valueRange = MIN_HOURS.toFloat()..MAX_HOURS.toFloat(),
            steps = MAX_HOURS - MIN_HOURS - 1
        )
        Text(
            stringResource(R.string.workout_mode_settings_info),
            color = outline,
            fontSize = 12.sp
        )
    }
}
