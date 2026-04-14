package com.psjostrom.strimma.ui.story

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.story.MealStoryData
import com.psjostrom.strimma.data.story.StoryData
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.TintDanger
import com.psjostrom.strimma.ui.theme.TintGood
import com.psjostrom.strimma.ui.theme.TintInRange
import com.psjostrom.strimma.ui.theme.TintWarning
import com.psjostrom.strimma.ui.theme.TirGood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

private const val TIR_GOOD_THRESHOLD = 70.0
private const val TIR_OK_THRESHOLD = 50.0

@Composable
fun OverviewPage(data: StoryData, glucoseUnit: GlucoseUnit, hbA1cUnit: com.psjostrom.strimma.data.HbA1cUnit) {
    val stats = data.stats
    var showExplainer by remember { mutableStateOf<Pair<String, String>?>(null) }
    val explainTir = stringResource(R.string.explain_tir)
    val explainGmi = stringResource(R.string.explain_gmi)
    val explainCv = stringResource(R.string.explain_cv)
    val explainAvg = stringResource(R.string.explain_avg_glucose)

    StoryPageScaffold(tintColor = MaterialTheme.colorScheme.surfaceVariant) {
        // Label
        Text(
            "${data.monthLabel.uppercase()} ${data.year}",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // Hero title — actual month name
        Text(
            data.monthLabel.uppercase(),
            fontSize = 38.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 42.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${data.dayCount} days \u00B7 %,d readings".format(data.readingCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        // TIR hero
        Text(
            "TIME IN RANGE",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "${stats.tirPercent.toInt()}%",
            fontSize = 72.sp,
            fontWeight = FontWeight.ExtraBold,
            color = InRange,
            modifier = Modifier.clickable {
                showExplainer = "Time in Range" to explainTir
            }
        )
        data.previousStats?.let { prev ->
            val delta = stats.tirPercent - prev.tirPercent
            val sign = if (delta >= 0) "+" else ""
            Text(
                "${sign}${"%.1f".format(delta)}% vs last month",
                style = MaterialTheme.typography.bodySmall,
                color = if (delta >= 0) TirGood else BelowLow
            )
        }

        Spacer(Modifier.height(24.dp))

        // Stat cards — 2x2 grid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StoryCard("GMI", Modifier.weight(1f), onClick = {
                showExplainer = "GMI" to explainGmi
            }) {
                Text(
                    hbA1cUnit.format(stats.gmi),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TirGood
                )
            }
            StoryCard("CV", Modifier.weight(1f), onClick = {
                showExplainer = "CV" to explainCv
            }) {
                Text(
                    "${"%.0f".format(stats.cv)}%",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = InRange
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StoryCard("AVG GLUCOSE", Modifier.weight(1f), onClick = {
                showExplainer = "Avg Glucose" to explainAvg
            }) {
                Text(
                    glucoseUnit.format(stats.averageMgdl),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    if (glucoseUnit == GlucoseUnit.MMOL) "mmol/L" else "mg/dL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StoryCard("READINGS", Modifier.weight(1f)) {
                Text(
                    "%,d".format(data.readingCount),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }

    showExplainer?.let { (title, text) ->
        MetricExplainerDialog(title, text, onDismiss = { showExplainer = null })
    }
}

@Composable
fun StabilityPage(data: StoryData) {
    var showExplainer by remember { mutableStateOf<Pair<String, String>?>(null) }
    val explainFlatline = stringResource(R.string.explain_flatline)
    val explainStreak = stringResource(R.string.explain_in_range_streak)
    val explainSteadiest = stringResource(R.string.explain_steadiest_day)

    StoryPageScaffold(tintColor = TintGood) {
        Text(
            "Stability",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(32.dp))

        // Flatline hero
        val flatCount = data.stability.flatlineCount
        Text(
            "$flatCount",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = TirGood,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showExplainer = "Flatlines" to explainFlatline }
        )
        Text(
            if (flatCount == 1) "Flatline" else "Flatlines",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Periods of ultra-stable glucose",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        data.stability.longestInRangeStreak?.let { streak ->
            StoryCard("Longest in-range streak", onClick = {
                showExplainer = "In-Range Streak" to explainStreak
            }) {
                val hours = streak.durationMinutes / 60
                val mins = streak.durationMinutes % 60
                val label = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                Text(
                    label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = InRange
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        data.stability.longestFlatline?.let { flat ->
            StoryCard("Longest flatline", onClick = {
                showExplainer = "Flatlines" to explainFlatline
            }) {
                val hours = flat.durationMinutes / 60
                val mins = flat.durationMinutes % 60
                val label = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                Text(
                    label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TirGood
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        data.stability.steadiestDay?.let { day ->
            StoryCard("Steadiest day", onClick = {
                showExplainer = "Steadiest Day" to explainSteadiest
            }) {
                Text(
                    day.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                MetricRow("TIR", "%.0f%%".format(day.tirPercent))
                Spacer(Modifier.height(4.dp))
                MetricRow("CV", "%.1f%%".format(day.cv))
            }
        }
    }

    showExplainer?.let { (title, text) ->
        MetricExplainerDialog(title, text, onDismiss = { showExplainer = null })
    }
}

@Composable
fun EventsPage(data: StoryData) {
    var showExplainer by remember { mutableStateOf<Pair<String, String>?>(null) }
    val explainLow = stringResource(R.string.explain_low_events)
    val explainHigh = stringResource(R.string.explain_high_events)

    StoryPageScaffold(tintColor = TintDanger) {
        Text(
            "Events",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Low events
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { showExplainer = "Low Events" to explainLow }
            ) {
                Text(
                    "${data.events.lowEvents}",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = BelowLow
                )
                Text(
                    "Low events",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                data.events.previousLowEvents?.let { prev ->
                    val delta = data.events.lowEvents - prev
                    val arrow = when {
                        delta < 0 -> "\u2193" // down arrow
                        delta > 0 -> "\u2191" // up arrow
                        else -> "="
                    }
                    Text(
                        "$arrow vs last month",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (delta <= 0) TirGood else BelowLow
                    )
                }
            }

            // High events
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { showExplainer = "High Events" to explainHigh }
            ) {
                Text(
                    "${data.events.highEvents}",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = AboveHigh
                )
                Text(
                    "High events",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                data.events.previousHighEvents?.let { prev ->
                    val delta = data.events.highEvents - prev
                    val arrow = when {
                        delta < 0 -> "\u2193"
                        delta > 0 -> "\u2191"
                        else -> "="
                    }
                    Text(
                        "$arrow vs last month",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (delta <= 0) TirGood else AboveHigh
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        StoryCard("Time out of range") {
            MetricRow("Below range", "%.1f%%".format(data.events.belowPercent))
            Spacer(Modifier.height(8.dp))
            MetricRow("Above range", "%.1f%%".format(data.events.abovePercent))
        }

        Spacer(Modifier.height(12.dp))

        val avgLow = data.events.avgLowDurationMinutes
        val avgHigh = data.events.avgHighDurationMinutes
        if (avgLow != null || avgHigh != null) {
            StoryCard("Average duration") {
                avgLow?.let {
                    MetricRow("Low events", "${it}m avg")
                    Spacer(Modifier.height(8.dp))
                }
                avgHigh?.let {
                    MetricRow("High events", "${it}m avg")
                }
            }
        }
    }

    showExplainer?.let { (title, text) ->
        MetricExplainerDialog(title, text, onDismiss = { showExplainer = null })
    }
}

@Composable
fun PatternsPage(data: StoryData) {
    var showExplainer by remember { mutableStateOf<Pair<String, String>?>(null) }
    val explainTir = stringResource(R.string.explain_tir)

    StoryPageScaffold(tintColor = TintInRange) {
        Text(
            "Time of Day",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "How your glucose varies throughout the day",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        data.timeOfDay.blocks.forEach { block ->
            StoryCard(block.name, onClick = {
                showExplainer = "Time in Range" to explainTir
            }) {
                val tirColor = when {
                    block.tirPercent >= TIR_GOOD_THRESHOLD -> InRange
                    block.tirPercent >= TIR_OK_THRESHOLD -> AboveHigh
                    else -> BelowLow
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "%02d:00 - %02d:00".format(block.startHour, block.endHour),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "%.0f%% TIR".format(block.tirPercent),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = tirColor
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    showExplainer?.let { (title, text) ->
        MetricExplainerDialog(title, text, onDismiss = { showExplainer = null })
    }
}

@Composable
fun MealsPage(meals: MealStoryData, glucoseUnit: GlucoseUnit) {
    var showExplainer by remember { mutableStateOf<Pair<String, String>?>(null) }
    val explainTir = stringResource(R.string.explain_tir)

    StoryPageScaffold(tintColor = TintWarning) {
        Text(
            "Meals",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${meals.mealCount} meals analyzed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        meals.bestSlot?.let { best ->
            StoryCard("Best meal slot", onClick = {
                showExplainer = "Time in Range" to explainTir
            }) {
                Text(
                    best.slot.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TirGood
                )
                Spacer(Modifier.height(4.dp))
                MetricRow("TIR", "%.0f%%".format(best.tirPercent))
                Spacer(Modifier.height(4.dp))
                MetricRow("Meals", "${best.mealCount}")
            }
            Spacer(Modifier.height(12.dp))
        }

        meals.worstSlot?.let { worst ->
            if (worst != meals.bestSlot) {
                StoryCard("Needs attention", onClick = {
                    showExplainer = "Time in Range" to explainTir
                }) {
                    Text(
                        worst.slot.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = AboveHigh
                    )
                    Spacer(Modifier.height(4.dp))
                    MetricRow("TIR", "%.0f%%".format(worst.tirPercent))
                    Spacer(Modifier.height(4.dp))
                    MetricRow("Meals", "${worst.mealCount}")
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (meals.avgExcursionBySlot.isNotEmpty()) {
            StoryCard("Avg excursion by slot") {
                meals.avgExcursionBySlot.forEach { (slot, excursion) ->
                    MetricRow(slot.label, glucoseUnit.formatWithUnit(excursion))
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    showExplainer?.let { (title, text) ->
        MetricExplainerDialog(title, text, onDismiss = { showExplainer = null })
    }
}

@Composable
fun SummaryPage(data: StoryData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    val shareLabel = stringResource(R.string.story_share)

    StoryPageScaffold(tintColor = TintInRange) {
        Box(
            modifier = Modifier.drawWithContent {
                graphicsLayer.record {
                    this@drawWithContent.drawContent()
                }
                drawLayer(graphicsLayer)
            }
        ) {
            Column {
                Text(
                    "YOUR STORY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${data.monthLabel.uppercase()}\n${data.year}",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 42.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    data.narrative,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 28.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    shareStoryBitmap(context, graphicsLayer, shareLabel)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(shareLabel)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // Bitmap capture + file I/O + FileProvider — multiple failure modes
private suspend fun shareStoryBitmap(
    context: Context,
    graphicsLayer: GraphicsLayer,
    chooserTitle: String
) {
    try {
        val bitmap = graphicsLayer.toImageBitmap()
        val androidBitmap = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        androidBitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val file = withContext(Dispatchers.IO) {
            val f = File(context.cacheDir, "strimma_story.png")
            f.outputStream().use { out ->
                androidBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            f
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Share failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
