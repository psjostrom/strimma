package com.psjostrom.strimma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    nightscoutConfigured: Boolean = false
) {
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val surfVar = MaterialTheme.colorScheme.surfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bg,
                    titleContentColor = onBg
                )
            )
        },
        containerColor = bg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            SettingsMenuGroup(surfVar) {
                SettingsMenuItem(
                    icon = Icons.Default.Sensors,
                    title = stringResource(R.string.settings_data_source),
                    subtitle = stringResource(R.string.settings_data_source_subtitle),
                    onClick = { onNavigate("settings/data-source") }
                )
                if (nightscoutConfigured) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsMenuItem(
                        icon = Icons.Default.Medication,
                        title = stringResource(R.string.settings_treatments),
                        subtitle = stringResource(R.string.settings_treatments_subtitle),
                        onClick = { onNavigate("settings/treatments") }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.FitnessCenter,
                    title = stringResource(R.string.settings_exercise),
                    subtitle = stringResource(R.string.settings_exercise_subtitle),
                    onClick = { onNavigate("settings/exercise") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.settings_display),
                    subtitle = stringResource(R.string.settings_display_subtitle),
                    onClick = { onNavigate("settings/display") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_notifications),
                    subtitle = stringResource(R.string.settings_notifications_subtitle),
                    onClick = { onNavigate("settings/notifications") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Warning,
                    title = stringResource(R.string.settings_alerts),
                    subtitle = stringResource(R.string.settings_alerts_subtitle),
                    onClick = { onNavigate("settings/alerts") }
                )
            }

            SettingsMenuGroup(surfVar) {
                SettingsMenuItem(
                    icon = Icons.Outlined.BarChart,
                    title = stringResource(R.string.settings_statistics),
                    subtitle = stringResource(R.string.settings_statistics_subtitle),
                    onClick = { onNavigate("stats") }
                )
            }

            SettingsMenuGroup(surfVar) {
                SettingsMenuItem(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.settings_general),
                    subtitle = stringResource(R.string.settings_general_subtitle),
                    onClick = { onNavigate("settings/general") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.settings_sharing),
                    subtitle = stringResource(R.string.settings_sharing_subtitle),
                    onClick = { onNavigate("settings/data") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.settings_debug_log),
                    onClick = { onNavigate("debug") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsMenuGroup(
    cardColor: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardColor
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = outline,
            modifier = Modifier.size(22.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = title,
                color = onBg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = outline,
                    fontSize = 12.sp
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = outline,
            modifier = Modifier.size(20.dp)
        )
    }
}
