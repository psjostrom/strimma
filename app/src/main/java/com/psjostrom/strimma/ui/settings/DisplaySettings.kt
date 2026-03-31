package com.psjostrom.strimma.ui.settings

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.HbA1cUnit
import com.psjostrom.strimma.ui.theme.ThemeMode
import java.util.Locale

@Composable
fun DisplaySettings(
    glucoseUnit: GlucoseUnit,
    hbA1cUnit: HbA1cUnit,
    graphWindowHours: Int,
    bgLow: Float,
    bgHigh: Float,
    themeMode: ThemeMode,
    onGlucoseUnitChange: (GlucoseUnit) -> Unit,
    onHbA1cUnitChange: (HbA1cUnit) -> Unit,
    onGraphWindowChange: (Int) -> Unit,
    onBgLowChange: (Float) -> Unit,
    onBgHighChange: (Float) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground

    SettingsScaffold(title = stringResource(R.string.settings_display_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_display_section)) {
            Text(stringResource(R.string.settings_display_unit), color = onBg, fontSize = 14.sp)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                GlucoseUnit.entries.forEachIndexed { index, unit ->
                    SegmentedButton(
                        selected = glucoseUnit == unit,
                        onClick = { onGlucoseUnitChange(unit) },
                        shape = SegmentedButtonDefaults.itemShape(index, GlucoseUnit.entries.size),
                    ) {
                        Text(unit.label)
                    }
                }
            }

            Text(stringResource(R.string.settings_display_hba1c_unit), color = onBg, fontSize = 14.sp)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                HbA1cUnit.entries.forEachIndexed { index, unit ->
                    SegmentedButton(
                        selected = hbA1cUnit == unit,
                        onClick = { onHbA1cUnitChange(unit) },
                        shape = SegmentedButtonDefaults.itemShape(index, HbA1cUnit.entries.size),
                    ) {
                        Text(unit.label)
                    }
                }
            }

            Text(
                stringResource(R.string.settings_display_graph_window, graphWindowHours),
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
                label = { Text(stringResource(R.string.settings_display_low_threshold, glucoseUnit.label)) },
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
                label = { Text(stringResource(R.string.settings_display_high_threshold, glucoseUnit.label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            LanguagePicker()

            Text(stringResource(R.string.settings_display_theme), color = onBg, fontSize = 14.sp)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    ) {
                        Text(stringResource(mode.labelRes))
                    }
                }
            }
        }
    }
}

private val SUPPORTED_LOCALE_TAGS = listOf("", "en", "sv", "es", "fr", "de")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val onBg = MaterialTheme.colorScheme.onBackground

    val systemLabel = stringResource(R.string.settings_display_language_system)

    val currentTag = remember {
        val appLocales = localeManager.applicationLocales
        if (appLocales.isEmpty) "" else appLocales.get(0)?.toLanguageTag() ?: ""
    }

    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(currentTag) }

    fun displayName(tag: String): String = if (tag.isEmpty()) {
        systemLabel
    } else {
        Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.uppercase() } }
    }

    Text(stringResource(R.string.settings_display_language), color = onBg, fontSize = 14.sp)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayName(selected),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SUPPORTED_LOCALE_TAGS.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(displayName(tag)) },
                    onClick = {
                        selected = tag
                        expanded = false
                        val locales = if (tag.isEmpty()) {
                            LocaleList.getEmptyLocaleList()
                        } else {
                            LocaleList.forLanguageTags(tag)
                        }
                        localeManager.applicationLocales = locales
                    }
                )
            }
        }
    }
}
