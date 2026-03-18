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
    alertLowEnabled: Boolean,
    alertHighEnabled: Boolean,
    alertUrgentLowEnabled: Boolean,
    alertLow: Float,
    alertHigh: Float,
    alertUrgentLow: Float,
    alertStaleEnabled: Boolean,
    onSpringaUrlChange: (String) -> Unit,
    onApiSecretChange: (String) -> Unit,
    onGraphWindowChange: (Int) -> Unit,
    onBgLowChange: (Float) -> Unit,
    onBgHighChange: (Float) -> Unit,
    onAlertLowEnabledChange: (Boolean) -> Unit,
    onAlertHighEnabledChange: (Boolean) -> Unit,
    onAlertUrgentLowEnabledChange: (Boolean) -> Unit,
    onAlertLowChange: (Float) -> Unit,
    onAlertHighChange: (Float) -> Unit,
    onAlertUrgentLowChange: (Float) -> Unit,
    onAlertStaleEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDebugLog: () -> Unit = {}
) {
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
                    containerColor = BgDark,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BgDark
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

            SettingsSection("Connection") {
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

            SettingsSection("Display") {
                Text(
                    "Graph Window: $graphWindowHours hours",
                    color = TextPrimary,
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
            }

            SettingsSection("Alerts") {
                AlertToggleRow("Urgent Low", alertUrgentLowEnabled, onAlertUrgentLowEnabledChange)
                if (alertUrgentLowEnabled) {
                    var urgentLowText by remember(alertUrgentLow) { mutableStateOf("%.1f".format(alertUrgentLow)) }
                    OutlinedTextField(
                        value = urgentLowText,
                        onValueChange = { text ->
                            urgentLowText = text
                            text.toFloatOrNull()?.let { onAlertUrgentLowChange(it) }
                        },
                        label = { Text("Urgent Low (mmol/L)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }

                AlertToggleRow("Low", alertLowEnabled, onAlertLowEnabledChange)
                if (alertLowEnabled) {
                    var alertLowText by remember(alertLow) { mutableStateOf("%.1f".format(alertLow)) }
                    OutlinedTextField(
                        value = alertLowText,
                        onValueChange = { text ->
                            alertLowText = text
                            text.toFloatOrNull()?.let { onAlertLowChange(it) }
                        },
                        label = { Text("Low Alert (mmol/L)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }

                AlertToggleRow("High", alertHighEnabled, onAlertHighEnabledChange)
                if (alertHighEnabled) {
                    var alertHighText by remember(alertHigh) { mutableStateOf("%.1f".format(alertHigh)) }
                    OutlinedTextField(
                        value = alertHighText,
                        onValueChange = { text ->
                            alertHighText = text
                            text.toFloatOrNull()?.let { onAlertHighChange(it) }
                        },
                        label = { Text("High Alert (mmol/L)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }

                AlertToggleRow("Stale Data (10+ min)", alertStaleEnabled, onAlertStaleEnabledChange)
            }

            SettingsSection("Developer") {
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
private fun AlertToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 14.sp)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title.uppercase(),
            color = TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceCard
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}
