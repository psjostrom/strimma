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
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.InsulinType
import com.psjostrom.strimma.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    glucoseSource: GlucoseSource,
    nightscoutUrl: String,
    nightscoutSecret: String,
    graphWindowHours: Int,
    bgLow: Float,
    bgHigh: Float,
    themeMode: String,
    notifGraphMinutes: Int,
    predictionMinutes: Int,
    glucoseUnit: GlucoseUnit,
    bgBroadcastEnabled: Boolean,
    alertLowEnabled: Boolean,
    alertHighEnabled: Boolean,
    alertUrgentLowEnabled: Boolean,
    alertUrgentHighEnabled: Boolean,
    alertLow: Float,
    alertHigh: Float,
    alertUrgentLow: Float,
    alertUrgentHigh: Float,
    alertStaleEnabled: Boolean,
    alertLowSoonEnabled: Boolean,
    alertHighSoonEnabled: Boolean,
    treatmentsSyncEnabled: Boolean = false,
    insulinType: InsulinType = InsulinType.FIASP,
    customDIA: Float = 5.0f,
    onTreatmentsSyncEnabledChange: (Boolean) -> Unit = {},
    onInsulinTypeChange: (InsulinType) -> Unit = {},
    onCustomDIAChange: (Float) -> Unit = {},
    onGlucoseSourceChange: (GlucoseSource) -> Unit,
    onNightscoutUrlChange: (String) -> Unit,
    onNightscoutSecretChange: (String) -> Unit,
    followerUrl: String = "",
    followerSecret: String = "",
    followerPollSeconds: Int = 60,
    onFollowerUrlChange: (String) -> Unit = {},
    onFollowerSecretChange: (String) -> Unit = {},
    onFollowerPollSecondsChange: (Int) -> Unit = {},
    onGraphWindowChange: (Int) -> Unit,
    onBgLowChange: (Float) -> Unit,
    onBgHighChange: (Float) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onNotifGraphMinutesChange: (Int) -> Unit,
    onPredictionMinutesChange: (Int) -> Unit,
    onGlucoseUnitChange: (GlucoseUnit) -> Unit,
    onBgBroadcastEnabledChange: (Boolean) -> Unit,
    onAlertLowEnabledChange: (Boolean) -> Unit,
    onAlertHighEnabledChange: (Boolean) -> Unit,
    onAlertUrgentLowEnabledChange: (Boolean) -> Unit,
    onAlertUrgentHighEnabledChange: (Boolean) -> Unit,
    onAlertLowChange: (Float) -> Unit,
    onAlertHighChange: (Float) -> Unit,
    onAlertUrgentLowChange: (Float) -> Unit,
    onAlertUrgentHighChange: (Float) -> Unit,
    onAlertStaleEnabledChange: (Boolean) -> Unit,
    onAlertLowSoonEnabledChange: (Boolean) -> Unit,
    onAlertHighSoonEnabledChange: (Boolean) -> Unit,
    onOpenAlertSound: (String) -> Unit,
    onBack: () -> Unit,
    onStats: () -> Unit = {},
    onWidgetSettings: () -> Unit = {},
    onDebugLog: () -> Unit = {},
    onExportSettings: () -> Unit = {},
    onImportSettings: () -> Unit = {}
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

            SettingsSection("Data Source", outline, surfVar) {
                GlucoseSource.entries.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = glucoseSource == source,
                            onClick = { onGlucoseSourceChange(source) }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(source.label, color = onBg, fontSize = 14.sp)
                            Text(source.description, color = outline, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (glucoseSource != GlucoseSource.NIGHTSCOUT_FOLLOWER) {
                SettingsSection("Nightscout", outline, surfVar) {
                    var urlText by remember(nightscoutUrl) { mutableStateOf(nightscoutUrl) }
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("Nightscout URL") },
                        placeholder = { Text("https://your-nightscout.example.com") },
                        supportingText = { Text("Base URL only — no /api path") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) onNightscoutUrlChange(urlText) },
                        singleLine = true
                    )

                    var secretText by remember(nightscoutSecret) { mutableStateOf(nightscoutSecret) }
                    OutlinedTextField(
                        value = secretText,
                        onValueChange = { secretText = it },
                        label = { Text("API Secret") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) onNightscoutSecretChange(secretText) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                }
            } else {
                SettingsSection("Following", outline, surfVar) {
                    var urlText by remember(followerUrl) { mutableStateOf(followerUrl) }
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("Nightscout URL") },
                        placeholder = { Text("https://nightscout.example.com") },
                        supportingText = { Text("The Nightscout server to follow") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) onFollowerUrlChange(urlText) },
                        singleLine = true
                    )

                    var secretText by remember(followerSecret) { mutableStateOf(followerSecret) }
                    OutlinedTextField(
                        value = secretText,
                        onValueChange = { secretText = it },
                        label = { Text("API Secret") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) onFollowerSecretChange(secretText) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Text(
                        "Poll Interval: ${followerPollSeconds}s",
                        color = onBg,
                        fontSize = 14.sp
                    )
                    Text(
                        "How often to check for new readings. Lower values catch updates faster but use more battery. CGM readings typically arrive every 5 minutes.",
                        color = outline,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = followerPollSeconds.toFloat(),
                        onValueChange = { onFollowerPollSecondsChange(it.toInt()) },
                        valueRange = 30f..300f,
                        steps = 8
                    )
                }
            }

            SettingsSection("Treatments", outline, surfVar) {
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
                            value = insulinType.label,
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
                                    text = { Text(type.label) },
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

            SettingsSection("Display", outline, surfVar) {
                Text("Unit", color = onBg, fontSize = 14.sp)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    GlucoseUnit.entries.forEachIndexed { index, unit ->
                        SegmentedButton(
                            selected = glucoseUnit == unit,
                            onClick = { onGlucoseUnitChange(unit) },
                            shape = SegmentedButtonDefaults.itemShape(index, GlucoseUnit.entries.size)
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
                            shape = SegmentedButtonDefaults.itemShape(index, 3)
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            SettingsSection("Notification Graph", outline, surfVar) {
                Text(
                    "Time Range: ${if (notifGraphMinutes < 60) "${notifGraphMinutes}m" else "${notifGraphMinutes / 60}h"}",
                    color = onBg,
                    fontSize = 14.sp
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h").forEachIndexed { index, (minutes, label) ->
                        SegmentedButton(
                            selected = notifGraphMinutes == minutes,
                            onClick = { onNotifGraphMinutesChange(minutes) },
                            shape = SegmentedButtonDefaults.itemShape(index, 4)
                        ) {
                            Text(label)
                        }
                    }
                }

                Text(
                    "Prediction: ${if (predictionMinutes == 0) "Off" else "${predictionMinutes}m"}",
                    color = onBg,
                    fontSize = 14.sp
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(0 to "Off", 10 to "10m", 15 to "15m", 20 to "20m", 30 to "30m").forEachIndexed { index, (minutes, label) ->
                        SegmentedButton(
                            selected = predictionMinutes == minutes,
                            onClick = { onPredictionMinutesChange(minutes) },
                            shape = SegmentedButtonDefaults.itemShape(index, 5)
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
                    thresholdLabel = "Urgent Low (${glucoseUnit.label})",
                    glucoseUnit = glucoseUnit,
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
                    thresholdLabel = "Low Alert (${glucoseUnit.label})",
                    glucoseUnit = glucoseUnit,
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
                    thresholdLabel = "High Alert (${glucoseUnit.label})",
                    glucoseUnit = glucoseUnit,
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
                    thresholdLabel = "Urgent High (${glucoseUnit.label})",
                    glucoseUnit = glucoseUnit,
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
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Low Soon", color = onBg, fontSize = 14.sp)
                        Text("Predicted low within prediction window", color = outline, fontSize = 12.sp)
                    }
                    Switch(checked = alertLowSoonEnabled, onCheckedChange = onAlertLowSoonEnabledChange)
                }
                if (alertLowSoonEnabled) {
                    TextButton(onClick = { onOpenAlertSound(com.psjostrom.strimma.notification.AlertManager.CHANNEL_LOW_SOON) }) {
                        Text("Sound", color = InRange, fontSize = 13.sp)
                    }
                }

                HorizontalDivider(color = outlineVar)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("High Soon", color = onBg, fontSize = 14.sp)
                        Text("Predicted high within prediction window", color = outline, fontSize = 12.sp)
                    }
                    Switch(checked = alertHighSoonEnabled, onCheckedChange = onAlertHighSoonEnabledChange)
                }
                if (alertHighSoonEnabled) {
                    TextButton(onClick = { onOpenAlertSound(com.psjostrom.strimma.notification.AlertManager.CHANNEL_HIGH_SOON) }) {
                        Text("Sound", color = InRange, fontSize = 13.sp)
                    }
                }

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

            SettingsSection("Integration", outline, surfVar) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("BG Broadcast", color = onBg, fontSize = 14.sp)
                        Text(
                            "xDrip-compatible intent for AAPS, GDH, watches",
                            color = outline,
                            fontSize = 12.sp
                        )
                    }
                    Switch(checked = bgBroadcastEnabled, onCheckedChange = onBgBroadcastEnabledChange)
                }
            }

            SettingsSection("Data", outline, surfVar) {
                OutlinedButton(
                    onClick = onStats,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Statistics")
                }
                OutlinedButton(
                    onClick = onWidgetSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Widget Settings")
                }
                HorizontalDivider(color = outlineVar)
                OutlinedButton(
                    onClick = onExportSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export Settings")
                }
                OutlinedButton(
                    onClick = onImportSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Settings")
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
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
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
        var text by remember(threshold, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(threshold)) }
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v
                glucoseUnit.parseThreshold(v)?.let { onThresholdChange(it) }
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
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
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
