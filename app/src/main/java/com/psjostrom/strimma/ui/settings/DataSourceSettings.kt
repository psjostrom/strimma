package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.GlucoseSource

@Composable
fun DataSourceSettings(
    glucoseSource: GlucoseSource,
    nightscoutUrl: String,
    nightscoutSecret: String,
    followerUrl: String,
    followerSecret: String,
    followerPollSeconds: Int,
    onGlucoseSourceChange: (GlucoseSource) -> Unit,
    onNightscoutUrlChange: (String) -> Unit,
    onNightscoutSecretChange: (String) -> Unit,
    onFollowerUrlChange: (String) -> Unit,
    onFollowerSecretChange: (String) -> Unit,
    onFollowerPollSecondsChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline

    SettingsScaffold(title = "Data Source", onBack = onBack) {
        SettingsSection("Source") {
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
                        Text(stringResource(source.labelRes), color = onBg, fontSize = 14.sp)
                        Text(stringResource(source.descriptionRes), color = outline, fontSize = 12.sp)
                    }
                }
            }
        }

        if (glucoseSource != GlucoseSource.NIGHTSCOUT_FOLLOWER) {
            SettingsSection("Nightscout Push") {
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
            SettingsSection("Following") {
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
                    "How often to check for new readings. Lower values catch updates faster" +
                        " but use more battery. CGM readings typically arrive every 5 minutes.",
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
    }
}
