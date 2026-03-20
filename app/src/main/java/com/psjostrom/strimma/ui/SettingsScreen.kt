package com.psjostrom.strimma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val surfVar = MaterialTheme.colorScheme.surfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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

            SettingsMenuGroup(outline, surfVar) {
                SettingsMenuItem(
                    icon = Icons.Default.Sensors,
                    title = "Data Source",
                    subtitle = "Source, Nightscout connection",
                    onClick = { onNavigate("settings/data-source") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Medication,
                    title = "Treatments",
                    subtitle = "IOB, insulin type",
                    onClick = { onNavigate("settings/treatments") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Palette,
                    title = "Display",
                    subtitle = "Units, graph, thresholds, theme",
                    onClick = { onNavigate("settings/display") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    subtitle = "Graph range, prediction",
                    onClick = { onNavigate("settings/notifications") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.Warning,
                    title = "Alerts",
                    subtitle = "Low, high, stale alerts",
                    onClick = { onNavigate("settings/alerts") }
                )
            }

            SettingsMenuGroup(outline, surfVar) {
                SettingsMenuItem(
                    icon = Icons.Default.Storage,
                    title = "Data",
                    subtitle = "Statistics, export, Nightscout pull",
                    onClick = { onNavigate("settings/data") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsMenuItem(
                    icon = Icons.Default.BugReport,
                    title = "Debug Log",
                    onClick = { onNavigate("debug") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsMenuGroup(
    titleColor: androidx.compose.ui.graphics.Color,
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
