package com.psjostrom.strimma.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    springaUrl: String,
    apiSecret: String,
    graphWindowHours: Int,
    bgLow: Float,
    bgHigh: Float,
    themeMode: String,
    alertLowEnabled: Boolean,
    alertHighEnabled: Boolean,
    alertUrgentLowEnabled: Boolean,
    alertUrgentHighEnabled: Boolean,
    alertLow: Float,
    alertHigh: Float,
    alertUrgentLow: Float,
    alertUrgentHigh: Float,
    alertStaleEnabled: Boolean,
    onSpringaUrlChange: (String) -> Unit,
    onApiSecretChange: (String) -> Unit,
    onGraphWindowChange: (Int) -> Unit,
    onBgLowChange: (Float) -> Unit,
    onBgHighChange: (Float) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onAlertLowEnabledChange: (Boolean) -> Unit,
    onAlertHighEnabledChange: (Boolean) -> Unit,
    onAlertUrgentLowEnabledChange: (Boolean) -> Unit,
    onAlertUrgentHighEnabledChange: (Boolean) -> Unit,
    onAlertLowChange: (Float) -> Unit,
    onAlertHighChange: (Float) -> Unit,
    onAlertUrgentLowChange: (Float) -> Unit,
    onAlertUrgentHighChange: (Float) -> Unit,
    onAlertStaleEnabledChange: (Boolean) -> Unit,
    onOpenAlertSound: (String) -> Unit,
    onBack: () -> Unit,
    onStats: () -> Unit = {},
    onDebugLog: () -> Unit = {}
) {
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val surfVar = MaterialTheme.colorScheme.surfaceVariant
    val outlineVar = MaterialTheme.colorScheme.outlineVariant

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

            SettingsSection("Connection", outline, surfVar) {
                var urlText by remember(springaUrl) { mutableStateOf(springaUrl) }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Springa URL") },
                    placeholder = { Text("https://springa.vercel.app") },
                    supportingText = { Text("Base URL only — no /api path") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) onSpringaUrlChange(urlText) },
                    singleLine = true
                )

                var secretText by remember(apiSecret) { mutableStateOf(apiSecret) }
                OutlinedTextField(
                    value = secretText,
                    onValueChange = { secretText = it },
                    label = { Text("API Secret") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) onApiSecretChange(secretText) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            SettingsSection("Display", outline, surfVar) {
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

                var bgLowText by remember(bgLow) { mutableStateOf("%.1f".format(bgLow)) }
                OutlinedTextField(
                    value = bgLowText,
                    onValueChange = { text ->
                        bgLowText = text
                        text.toFloatOrNull()?.let { onBgLowChange(it) }
                    },
                    label = { Text("Low Threshold (mmol/L)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                var bgHighText by remember(bgHigh) { mutableStateOf("%.1f".format(bgHigh)) }
                OutlinedTextField(
                    value = bgHighText,
                    onValueChange = { text ->
                        bgHighText = text
                        text.toFloatOrNull()?.let { onBgHighChange(it) }
                    },
                    label = { Text("High Threshold (mmol/L)") },
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
                            shape = SegmentedButtonDefaults.itemShape(index, 3)
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            SettingsSection("Alerts", outline, surfVar) {
                Text(
                    "Tap \"Sound\" to choose ringtone, vibration, and override settings per alarm.",
                    color = outline,
                    fontSize = 12.sp
                )

                AlertBlock(
                    label = "Urgent Low",
                    enabled = alertUrgentLowEnabled,
                    onToggle = onAlertUrgentLowEnabledChange,
                    threshold = alertUrgentLow,
                    onThresholdChange = onAlertUrgentLowChange,
                    thresholdLabel = "Urgent Low (mmol/L)",
                    channelId = com.psjostrom.strimma.notification.AlertManager.CHANNEL_URGENT_LOW,
                    onOpenSound = onOpenAlertSound,
                    textColor = onBg
                )

                HorizontalDivider(color = outlineVar)

                AlertBlock(
                    label = "Low",
                    enabled = alertLowEnabled,
                    onToggle = onAlertLowEnabledChange,
                    threshold = alertLow,
                    onThresholdChange = onAlertLowChange,
                    thresholdLabel = "Low Alert (mmol/L)",
                    channelId = com.psjostrom.strimma.notification.AlertManager.CHANNEL_LOW,
                    onOpenSound = onOpenAlertSound,
                    textColor = onBg
                )

                HorizontalDivider(color = outlineVar)

                AlertBlock(
                    label = "High",
                    enabled = alertHighEnabled,
                    onToggle = onAlertHighEnabledChange,
                    threshold = alertHigh,
                    onThresholdChange = onAlertHighChange,
                    thresholdLabel = "High Alert (mmol/L)",
                    channelId = com.psjostrom.strimma.notification.AlertManager.CHANNEL_HIGH,
                    onOpenSound = onOpenAlertSound,
                    textColor = onBg
                )

                HorizontalDivider(color = outlineVar)

                AlertBlock(
                    label = "Urgent High",
                    enabled = alertUrgentHighEnabled,
                    onToggle = onAlertUrgentHighEnabledChange,
                    threshold = alertUrgentHigh,
                    onThresholdChange = onAlertUrgentHighChange,
                    thresholdLabel = "Urgent High (mmol/L)",
                    channelId = com.psjostrom.strimma.notification.AlertManager.CHANNEL_URGENT_HIGH,
                    onOpenSound = onOpenAlertSound,
                    textColor = onBg
                )

                HorizontalDivider(color = outlineVar)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stale Data (10+ min)", color = onBg, fontSize = 14.sp)
                    Switch(checked = alertStaleEnabled, onCheckedChange = onAlertStaleEnabledChange)
                }
                if (alertStaleEnabled) {
                    TextButton(onClick = { onOpenAlertSound(com.psjostrom.strimma.notification.AlertManager.CHANNEL_STALE) }) {
                        Text("Sound", color = InRange, fontSize = 13.sp)
                    }
                }
            }

            SettingsSection("Data", outline, surfVar) {
                OutlinedButton(
                    onClick = onStats,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Statistics")
                }
            }

            SettingsSection("Developer", outline, surfVar) {
                OutlinedButton(
                    onClick = onDebugLog,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Debug Log")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AlertBlock(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    thresholdLabel: String,
    channelId: String,
    onOpenSound: (String) -> Unit,
    textColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = textColor, fontSize = 14.sp)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
    if (enabled) {
        var text by remember(threshold) { mutableStateOf("%.1f".format(threshold)) }
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v
                v.toFloatOrNull()?.let { onThresholdChange(it) }
            },
            label = { Text(thresholdLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        TextButton(onClick = { onOpenSound(channelId) }) {
            Text("Sound", color = InRange, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    cardColor: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title.uppercase(),
            color = titleColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardColor
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}
