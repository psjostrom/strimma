package com.psjostrom.strimma.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.story.toMillisRange
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle

private const val MIN_DAYS_FOR_STORY = 7
private const val MS_PER_HOUR = 3_600_000L

@Composable
fun MonthlyStoryEntry(
    storyViewedMonth: String?,
    onLoadReadings: suspend (Int) -> List<GlucoseReading>,
    onNavigate: (Int, Int) -> Unit
) {
    val lastMonth = remember { YearMonth.now().minusMonths(1) }
    val lastMonthKey = remember(lastMonth) { "%d-%02d".format(lastMonth.year, lastMonth.monthValue) }
    var hasData by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault()
        val (start, end) = lastMonth.toMillisRange(zone)
        val hoursAgo = ((System.currentTimeMillis() - start) / MS_PER_HOUR).toInt()
        val readings = onLoadReadings(hoursAgo)
        val days = readings.filter { it.ts in start..end }
            .map { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
            .distinct()
            .size
        hasData = days >= MIN_DAYS_FOR_STORY
    }
    if (hasData && storyViewedMonth != null) {
        val monthName = lastMonth.month.getDisplayName(
            TextStyle.FULL, LocalConfiguration.current.locales[0]
        )
        StoryEntryCard(
            monthName = monthName,
            viewed = storyViewedMonth == lastMonthKey,
            onClick = { onNavigate(lastMonth.year, lastMonth.monthValue) }
        )
    }
}
