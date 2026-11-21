package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            content()
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val outline = MaterialTheme.colorScheme.outline
    val surfVar = MaterialTheme.colorScheme.surfaceVariant

    Column {
        Text(
            text = title.uppercase(),
            color = outline,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        header?.invoke()
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = surfVar
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
fun PullDataSection(
    title: String,
    description: String,
    onPull: (Int) -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline

    SettingsSection(stringResource(R.string.settings_data_nightscout)) {
        Text(title, color = onBg, fontSize = 14.sp)
        Text(description, color = outline, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                30 to stringResource(R.string.settings_data_pull_30d),
                90 to stringResource(R.string.settings_data_pull_90d),
                365 to stringResource(R.string.settings_data_pull_365d)
            ).forEach { (days, label) ->
                OutlinedButton(
                    onClick = { onPull(days) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun AlertBlock(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    thresholdLabel: String,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    textColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = textColor, fontSize = 14.sp)
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
    if (enabled) {
        ThresholdField(threshold, onThresholdChange, glucoseUnit, Modifier.fillMaxWidth(), label = thresholdLabel)
    }
}

// Saving on every keystroke would store partial values such as "3" on the way to "3.9", and each save would
// reformat the text while the user types. The field saves once, when the user leaves it.
@Composable
internal fun ThresholdField(
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    glucoseUnit: GlucoseUnit,
    modifier: Modifier,
    label: String? = null,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    var text by remember(threshold, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(threshold)) }
    var edited by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val commit by rememberUpdatedState {
        if (edited) {
            edited = false
            glucoseUnit.parseThreshold(text)?.takeIf { it != threshold }?.let(onThresholdChange)
            // A rejected or unparseable edit leaves the stored threshold unchanged, so the field shows it again.
            text = glucoseUnit.formatThreshold(threshold)
        }
    }
    // Switching tab, turning the alert off or leaving the screen removes the field without a focus change.
    DisposableEffect(Unit) { onDispose { commit() } }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            edited = true
        },
        label = label?.let { { Text(it) } },
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
        singleLine = true,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}
