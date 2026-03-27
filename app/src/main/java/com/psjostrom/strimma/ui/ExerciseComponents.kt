package com.psjostrom.strimma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.ui.theme.ExerciseDefault

@Composable
fun StatChip(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun BGSparkline(
    readings: List<GlucoseReading>,
    modifier: Modifier = Modifier
) {
    val sparkColor = ExerciseDefault
    Canvas(modifier = modifier) {
        if (readings.size < 2) return@Canvas

        val sorted = readings.sortedBy { it.ts }
        val minTs = sorted.first().ts
        val maxTs = sorted.last().ts
        val tsRange = (maxTs - minTs).toFloat()
        if (tsRange <= 0f) return@Canvas

        val minSgv = sorted.minOf { it.sgv }.toFloat()
        val maxSgv = sorted.maxOf { it.sgv }.toFloat()
        val sgvRange = (maxSgv - minSgv).coerceAtLeast(20f)

        val w = size.width
        val h = size.height
        val pad = 2f

        var prevX = 0f
        var prevY = 0f
        for ((i, r) in sorted.withIndex()) {
            val x = pad + ((r.ts - minTs) / tsRange) * (w - 2 * pad)
            val y = pad + ((maxSgv - r.sgv) / sgvRange) * (h - 2 * pad)
            if (i > 0) {
                drawLine(
                    color = sparkColor,
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 2f
                )
            }
            prevX = x
            prevY = y
        }
    }
}
