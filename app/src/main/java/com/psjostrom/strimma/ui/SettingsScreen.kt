package com.psjostrom.strimma.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.ui.theme.BgDark
import com.psjostrom.strimma.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    springaUrl: String,
    apiSecret: String,
    graphWindowHours: Int,
    bgLow: Float,
    bgHigh: Float,
    onSpringaUrlChange: (String) -> Unit,
    onApiSecretChange: (String) -> Unit,
    onGraphWindowChange: (Int) -> Unit,
    onBgLowChange: (Float) -> Unit,
    onBgHighChange: (Float) -> Unit,
    onBack: () -> Unit
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = springaUrl,
                onValueChange = onSpringaUrlChange,
                label = { Text("Springa URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = apiSecret,
                onValueChange = onApiSecretChange,
                label = { Text("API Secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Text("Graph Window: $graphWindowHours hours", color = TextPrimary)
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
                label = { Text("BG Low Threshold (mmol/L)") },
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
                label = { Text("BG High Threshold (mmol/L)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}
