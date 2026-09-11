package com.psjostrom.strimma.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.pattern.GlucosePattern
import com.psjostrom.strimma.data.pattern.PatternType
import com.psjostrom.strimma.ui.theme.Danger
import com.psjostrom.strimma.ui.theme.LightTintDanger
import com.psjostrom.strimma.ui.theme.LightTintWarning
import com.psjostrom.strimma.ui.theme.TintDanger
import com.psjostrom.strimma.ui.theme.TintWarning
import com.psjostrom.strimma.ui.theme.Warning

@Composable
fun InsightCard(
    patterns: List<GlucosePattern>,
    glucoseUnit: GlucoseUnit,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (patterns.isEmpty()) return

    val primary = patterns.first()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val (bgColor, badgeColor) = when (primary.type) {
        PatternType.HIGH -> (if (isDark) TintWarning else LightTintWarning) to Warning
        PatternType.LOW -> (if (isDark) TintDanger else LightTintDanger) to Danger
    }

    val timeSpan = primary.formattedTimeSpan

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.insight_pattern_badge),
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = stringResource(
                            R.string.insight_pattern_header,
                            timeSpan,
                            primary.daysDetected,
                            primary.daysEvaluated
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_dismiss),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val detailText = if (patterns.size == 1) {
                val avgFormatted = glucoseUnit.formatWithUnit(primary.avgBgMgdl)
                when (primary.type) {
                    PatternType.HIGH -> stringResource(
                        R.string.insight_pattern_detail_high,
                        timeSpan,
                        avgFormatted
                    )
                    PatternType.LOW -> stringResource(
                        R.string.insight_pattern_detail_low,
                        timeSpan,
                        avgFormatted
                    )
                }
            } else {
                stringResource(R.string.insight_pattern_detail_multiple, patterns.size)
            }

            Text(
                text = detailText,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}
