package com.psjostrom.strimma.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun SetupUnitsStep(
    selectedUnit: GlucoseUnit,
    onUnitChange: (GlucoseUnit) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UnitCard(
            label = stringResource(R.string.setup_units_mmol),
            description = stringResource(R.string.setup_units_mmol_desc),
            selected = selectedUnit == GlucoseUnit.MMOL,
            onClick = { onUnitChange(GlucoseUnit.MMOL) }
        )
        UnitCard(
            label = stringResource(R.string.setup_units_mgdl),
            description = stringResource(R.string.setup_units_mgdl_desc),
            selected = selectedUnit == GlucoseUnit.MGDL,
            onClick = { onUnitChange(GlucoseUnit.MGDL) }
        )
    }
}

@Composable
private fun UnitCard(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) InRange else MaterialTheme.colorScheme.outlineVariant

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

fun defaultUnitForLocale(locale: java.util.Locale = java.util.Locale.getDefault()): GlucoseUnit {
    val country = locale.country
    val mmolCountries = setOf(
        "GB", "IE", "DE", "FR", "IT", "ES", "PT", "NL", "BE", "AT", "CH",
        "DK", "FI", "SE", "NO", "IS", "PL", "CZ", "SK", "HU", "RO", "BG",
        "HR", "SI", "EE", "LV", "LT", "CY", "MT", "LU", "GR",
        "AU", "NZ", "CA", "ZA", "NG", "KE", "GH", "TZ"
    )
    return if (country in mmolCountries) GlucoseUnit.MMOL else GlucoseUnit.MGDL
}
