# Per-Meal Postprandial Analysis — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Meals" tab to the Stats screen that analyzes glucose response to each carb event from Nightscout treatments — per-meal cards with sparklines and clinical metrics, aggregated by time-of-day and carb size.

**Architecture:** Follows the ExerciseBGAnalyzer/CategoryStatsCalculator pattern. `MealAnalyzer` computes per-meal metrics from treatments + readings. `MealStatsCalculator` groups results by time-of-day and carb size. A new `MealStatsTab` composable renders inside the existing `StatsScreen` as a third tab.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, JUnit 4

**Design spec:** `docs/specs/2026-03-27-meal-postprandial-analysis-design.md`

---

## File Map

### New files

| File | Responsibility |
|------|---------------|
| `data/meal/MealTimeSlot.kt` | Enum: BREAKFAST/LUNCH/DINNER/SNACK with hour boundaries |
| `data/meal/CarbSizeBucket.kt` | Enum: SMALL/MEDIUM/LARGE with gram thresholds |
| `data/meal/MealPostprandialResult.kt` | Data class for per-meal analysis output |
| `data/meal/MealAnalyzer.kt` | Computes one `MealPostprandialResult` from a carb event + readings |
| `data/meal/MealStatsCalculator.kt` | Groups results, computes aggregates |
| `ui/MealStatsTab.kt` | Composable: aggregate header + meal card list + sparkline |
| `test/.../meal/MealTimeSlotTest.kt` | Time slot classification tests |
| `test/.../meal/MealAnalyzerTest.kt` | Per-meal metric computation tests |
| `test/.../meal/MealStatsCalculatorTest.kt` | Grouping and aggregate tests |

### Modified files

| File | Change |
|------|--------|
| `network/TreatmentSyncer.kt` | `PRUNE_MS` and `LOOKBACK_MS` already 30d — no change needed |
| `data/TreatmentDao.kt` | Add `carbsInRange()` query |
| `ui/StatsScreen.kt` | Add TAB_MEALS, third SegmentedButton, wire MealStatsTab |

---

## Task 1: Treatment Retention & DAO Query

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/network/TreatmentSyncer.kt:25`
- Modify: `app/src/main/java/com/psjostrom/strimma/data/TreatmentDao.kt`

- [ ] **Step 1: Verify treatment retention is sufficient**

`TreatmentSyncer.kt` already has `PRUNE_MS` and `LOOKBACK_MS` set to 30 days, which exceeds the 14-day requirement for meal analysis. No change needed — verify the current values:

```kotlin
companion object {
    private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    private const val LOOKBACK_MS = 30 * 24 * 60 * 60 * 1000L
    private const val PRUNE_MS = 30 * 24 * 60 * 60 * 1000L
}
```

- [ ] **Step 2: Add carbsInRange query to TreatmentDao**

In `TreatmentDao.kt`, add:

```kotlin
@Query("SELECT * FROM treatments WHERE carbs IS NOT NULL AND carbs > 0 AND createdAt >= :start AND createdAt <= :end ORDER BY createdAt ASC")
suspend fun carbsInRange(start: Long, end: Long): List<Treatment>
```

- [ ] **Step 3: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/TreatmentSyncer.kt \
       app/src/main/java/com/psjostrom/strimma/data/TreatmentDao.kt
git commit -m "Extend treatment retention to 14 days, add carbsInRange query"
```

---

## Task 2: MealTimeSlot Enum

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/meal/MealTimeSlot.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/data/meal/MealTimeSlotTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/psjostrom/strimma/data/meal/MealTimeSlotTest.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MealTimeSlotTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    private fun tsAt(hour: Int, minute: Int = 0): Long {
        return Instant.parse("2026-03-27T00:00:00Z")
            .atZone(ZoneId.of("UTC"))
            .withHour(hour).withMinute(minute)
            .withZoneSameLocal(zone)
            .toInstant().toEpochMilli()
    }

    @Test
    fun `breakfast at 07-30`() {
        assertEquals(MealTimeSlot.BREAKFAST, MealTimeSlot.fromTimestamp(tsAt(7, 30), zone))
    }

    @Test
    fun `breakfast starts at 06-00`() {
        assertEquals(MealTimeSlot.BREAKFAST, MealTimeSlot.fromTimestamp(tsAt(6, 0), zone))
    }

    @Test
    fun `lunch at 12-00`() {
        assertEquals(MealTimeSlot.LUNCH, MealTimeSlot.fromTimestamp(tsAt(12, 0), zone))
    }

    @Test
    fun `lunch starts at 11-30`() {
        assertEquals(MealTimeSlot.LUNCH, MealTimeSlot.fromTimestamp(tsAt(11, 30), zone))
    }

    @Test
    fun `lunch ends before 14-30`() {
        assertEquals(MealTimeSlot.LUNCH, MealTimeSlot.fromTimestamp(tsAt(14, 29), zone))
    }

    @Test
    fun `snack at 14-30`() {
        assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(14, 30), zone))
    }

    @Test
    fun `dinner at 19-00`() {
        assertEquals(MealTimeSlot.DINNER, MealTimeSlot.fromTimestamp(tsAt(19, 0), zone))
    }

    @Test
    fun `snack between breakfast and lunch`() {
        assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(10, 30), zone))
    }

    @Test
    fun `snack at 22-00`() {
        assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(22, 0), zone))
    }

    @Test
    fun `snack at 03-00`() {
        assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(3, 0), zone))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.meal.MealTimeSlotTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MealTimeSlot**

Create `app/src/main/java/com/psjostrom/strimma/data/meal/MealTimeSlot.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

import java.time.Instant
import java.time.ZoneId

enum class MealTimeSlot(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack");

    companion object {
        // Boundaries in minutes from midnight
        private const val BREAKFAST_START = 6 * 60       // 06:00
        private const val BREAKFAST_END = 10 * 60         // 10:00
        private const val LUNCH_START = 11 * 60 + 30      // 11:30
        private const val LUNCH_END = 14 * 60 + 30        // 14:30
        private const val DINNER_START = 17 * 60          // 17:00
        private const val DINNER_END = 21 * 60            // 21:00

        fun fromTimestamp(ts: Long, zone: ZoneId): MealTimeSlot {
            val localTime = Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
            val minuteOfDay = localTime.hour * 60 + localTime.minute
            return when {
                minuteOfDay in BREAKFAST_START until BREAKFAST_END -> BREAKFAST
                minuteOfDay in LUNCH_START until LUNCH_END -> LUNCH
                minuteOfDay in DINNER_START until DINNER_END -> DINNER
                else -> SNACK
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.meal.MealTimeSlotTest"`
Expected: PASS — all 10 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/meal/MealTimeSlot.kt \
       app/src/test/java/com/psjostrom/strimma/data/meal/MealTimeSlotTest.kt
git commit -m "Add MealTimeSlot enum with time-of-day classification"
```

---

## Task 3: CarbSizeBucket Enum

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/meal/CarbSizeBucket.kt`

- [ ] **Step 1: Create CarbSizeBucket**

Create `app/src/main/java/com/psjostrom/strimma/data/meal/CarbSizeBucket.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

enum class CarbSizeBucket(val label: String) {
    SMALL("< 20g"),
    MEDIUM("20–50g"),
    LARGE("> 50g");

    companion object {
        private const val SMALL_THRESHOLD = 20.0
        private const val LARGE_THRESHOLD = 50.0

        fun fromGrams(grams: Double): CarbSizeBucket = when {
            grams < SMALL_THRESHOLD -> SMALL
            grams > LARGE_THRESHOLD -> LARGE
            else -> MEDIUM
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/meal/CarbSizeBucket.kt
git commit -m "Add CarbSizeBucket enum"
```

---

## Task 4: MealPostprandialResult Data Class

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/meal/MealPostprandialResult.kt`

- [ ] **Step 1: Create the data class**

Create `app/src/main/java/com/psjostrom/strimma/data/meal/MealPostprandialResult.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading

data class MealPostprandialResult(
    val mealTime: Long,
    val carbGrams: Double,
    val baselineMgdl: Double,
    val peakMgdl: Double,
    val excursionMgdl: Double,        // peak - baseline
    val timeToPeakMinutes: Int,
    val recoveryMinutes: Int?,         // null if not recovered within window
    val tirPercent: Double,
    val iAucMgdlMin: Double,           // incremental AUC above baseline (mg/dL·min)
    val iobAtMeal: Double,
    val windowMinutes: Int,            // actual window used (180-240)
    val readings: List<GlucoseReading> // for sparkline rendering
)

enum class TirRating { GOOD, MODERATE, POOR }

fun MealPostprandialResult.tirRating(): TirRating = when {
    tirPercent >= 80.0 -> TirRating.GOOD
    tirPercent >= 50.0 -> TirRating.MODERATE
    else -> TirRating.POOR
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/meal/MealPostprandialResult.kt
git commit -m "Add MealPostprandialResult data class"
```

---

## Task 5: MealAnalyzer — Core Logic

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/meal/MealAnalyzer.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/data/meal/MealAnalyzerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/psjostrom/strimma/data/meal/MealAnalyzerTest.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MealAnalyzerTest {

    private val analyzer = MealAnalyzer()

    private val baseTime = Instant.parse("2026-03-27T12:00:00Z").toEpochMilli()
    private val bgLow = 72.0
    private val bgHigh = 180.0

    private fun minutes(n: Long) = Duration.ofMinutes(n).toMillis()

    private fun reading(minutesFromMeal: Long, sgv: Int) = GlucoseReading(
        ts = baseTime + minutes(minutesFromMeal),
        sgv = sgv, direction = "Flat", delta = null, pushed = 1
    )

    private fun carbTreatment(
        minutesFromBase: Long = 0,
        carbs: Double = 40.0,
        id: String = "meal-1"
    ) = Treatment(
        id = id, createdAt = baseTime + minutes(minutesFromBase),
        eventType = "carb", insulin = null, carbs = carbs,
        basalRate = null, duration = null, enteredBy = "test",
        fetchedAt = baseTime
    )

    /** Stable readings: pre-meal at 108, spike to 180 at 45min, recover to 110 by 150min */
    private fun spikeAndRecoverReadings(): List<GlucoseReading> {
        val readings = mutableListOf<GlucoseReading>()
        // Pre-meal: 15 min of readings at 108
        for (m in -15L..0L) readings.add(reading(m, 108))
        // Rise: 0 to 45 min, 108 → 180
        for (m in 1L..45L) readings.add(reading(m, 108 + ((180 - 108) * m / 45).toInt()))
        // Fall: 45 to 150 min, 180 → 110
        for (m in 46L..150L) readings.add(reading(m, 180 - ((180 - 110) * (m - 45) / 105).toInt()))
        // Stable: 150 to 200 min at 110
        for (m in 151L..200L) readings.add(reading(m, 110))
        return readings
    }

    @Test
    fun `baseline computed from 15 min pre-meal readings`() {
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )
        assertNotNull(result)
        assertEquals(108.0, result!!.baselineMgdl, 1.0)
    }

    @Test
    fun `peak and excursion computed correctly`() {
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertEquals(180.0, result.peakMgdl, 1.0)
        assertEquals(72.0, result.excursionMgdl, 2.0) // 180 - 108
    }

    @Test
    fun `time to peak in minutes`() {
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertEquals(45, result.timeToPeakMinutes)
    }

    @Test
    fun `recovery time detected when BG returns to baseline`() {
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertNotNull(result.recoveryMinutes)
        // BG crosses baseline (~108) around 145-150 min
        assertTrue(result.recoveryMinutes!! in 140..155)
    }

    @Test
    fun `recovery null when BG stays elevated`() {
        // Readings that spike and stay high
        val readings = mutableListOf<GlucoseReading>()
        for (m in -15L..0L) readings.add(reading(m, 108))
        for (m in 1L..180L) readings.add(reading(m, 200))

        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = readings,
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertNull(result.recoveryMinutes)
    }

    @Test
    fun `window cut short by next meal`() {
        val nextMealTime = baseTime + minutes(90) // Next meal at 90 min

        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = nextMealTime, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertEquals(90, result.windowMinutes)
    }

    @Test
    fun `default window is 180 minutes`() {
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertEquals(180, result.windowMinutes)
    }

    @Test
    fun `window extends to 240 if not recovered at 180`() {
        // Readings that are still elevated at 180 min
        val readings = mutableListOf<GlucoseReading>()
        for (m in -15L..0L) readings.add(reading(m, 100))
        for (m in 1L..240L) {
            val sgv = if (m < 60) 100 + (m * 1.5).toInt() // rise
            else if (m < 200) 190 - ((m - 60) * 0.5).toInt() // slow fall
            else 100 // recovered at ~200
            readings.add(reading(m, sgv))
        }

        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = readings,
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertEquals(240, result.windowMinutes)
    }

    @Test
    fun `TIR computed within window`() {
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        // All readings 108-180, bgLow=72, bgHigh=180, so 100% in range
        assertEquals(100.0, result.tirPercent, 1.0)
    }

    @Test
    fun `iAUC is positive for spike above baseline`() {
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = spikeAndRecoverReadings(),
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )!!
        assertTrue(result.iAucMgdlMin > 0)
    }

    @Test
    fun `returns null with insufficient readings`() {
        val readings = listOf(reading(0, 108)) // Only 1 reading
        val result = analyzer.analyze(
            meal = carbTreatment(),
            readings = readings,
            bgLow = bgLow, bgHigh = bgHigh,
            nextMealTime = null, allTreatments = emptyList(), tauMinutes = 55.0
        )
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.meal.MealAnalyzerTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MealAnalyzer**

Create `app/src/main/java/com/psjostrom/strimma/data/meal/MealAnalyzer.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.Treatment

class MealAnalyzer {

    companion object {
        private const val DEFAULT_WINDOW_MINUTES = 180
        private const val EXTENDED_WINDOW_MINUTES = 240
        private const val PRE_MEAL_WINDOW_MINUTES = 15
        private const val MIN_READINGS = 5
        private const val MS_PER_MINUTE = 60_000L
        private const val PERCENT = 100.0
        private const val MIN_PRE_READINGS = 3
    }

    fun analyze(
        meal: Treatment,
        readings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        nextMealTime: Long?,
        allTreatments: List<Treatment>,
        tauMinutes: Double
    ): MealPostprandialResult? {
        val mealTime = meal.createdAt
        val carbGrams = meal.carbs ?: return null

        // Compute baseline from 15 min pre-meal
        val preStart = mealTime - PRE_MEAL_WINDOW_MINUTES * MS_PER_MINUTE
        val preReadings = readings.filter { it.ts in preStart until mealTime }.sortedBy { it.ts }
        val baseline = computeBaseline(preReadings, readings, mealTime) ?: return null

        // Determine window end
        val defaultEnd = mealTime + DEFAULT_WINDOW_MINUTES * MS_PER_MINUTE
        val maxEnd = mealTime + EXTENDED_WINDOW_MINUTES * MS_PER_MINUTE
        val nextMealEnd = nextMealTime ?: Long.MAX_VALUE

        // Get readings in the max possible window
        val postReadings = readings
            .filter { it.ts in mealTime..minOf(maxEnd, nextMealEnd) }
            .sortedBy { it.ts }

        if (postReadings.size < MIN_READINGS) return null

        // Decide actual window: extend to 240 if not recovered at 180
        val windowEnd: Long
        val windowMinutes: Int
        if (nextMealEnd <= defaultEnd) {
            windowEnd = nextMealEnd
            windowMinutes = ((nextMealEnd - mealTime) / MS_PER_MINUTE).toInt()
        } else {
            val readingsAt3h = postReadings.filter { it.ts <= defaultEnd }
            val lastAt3h = readingsAt3h.lastOrNull()
            val recovered = lastAt3h != null && lastAt3h.sgv <= baseline + 5
            if (recovered || nextMealEnd <= maxEnd) {
                windowEnd = minOf(defaultEnd, nextMealEnd)
                windowMinutes = DEFAULT_WINDOW_MINUTES
            } else {
                windowEnd = minOf(maxEnd, nextMealEnd)
                windowMinutes = ((windowEnd - mealTime) / MS_PER_MINUTE).toInt()
            }
        }

        val windowReadings = postReadings.filter { it.ts <= windowEnd }
        if (windowReadings.size < MIN_READINGS) return null

        // Peak
        val peakReading = windowReadings.maxByOrNull { it.sgv } ?: return null
        val peakMgdl = peakReading.sgv.toDouble()
        val timeToPeak = ((peakReading.ts - mealTime) / MS_PER_MINUTE).toInt()

        // Excursion
        val excursion = (peakMgdl - baseline).coerceAtLeast(0.0)

        // Recovery: first reading after peak that drops to baseline (±5 mg/dL)
        val recoveryReading = windowReadings
            .filter { it.ts > peakReading.ts }
            .firstOrNull { it.sgv <= baseline + 5 }
        val recoveryMinutes = recoveryReading?.let {
            ((it.ts - mealTime) / MS_PER_MINUTE).toInt()
        }

        // TIR within window
        val inRange = windowReadings.count { it.sgv >= bgLow && it.sgv <= bgHigh }
        val tir = inRange.toDouble() / windowReadings.size * PERCENT

        // iAUC (incremental AUC above baseline, trapezoidal)
        val iAuc = computeIAuc(windowReadings, baseline)

        // IOB at meal time
        val iob = IOBComputer.computeIOB(allTreatments, mealTime, tauMinutes)

        return MealPostprandialResult(
            mealTime = mealTime,
            carbGrams = carbGrams,
            baselineMgdl = baseline,
            peakMgdl = peakMgdl,
            excursionMgdl = excursion,
            timeToPeakMinutes = timeToPeak,
            recoveryMinutes = recoveryMinutes,
            tirPercent = tir,
            iAucMgdlMin = iAuc,
            iobAtMeal = iob,
            windowMinutes = windowMinutes,
            readings = windowReadings
        )
    }

    private fun computeBaseline(
        preReadings: List<GlucoseReading>,
        allReadings: List<GlucoseReading>,
        mealTime: Long
    ): Double? {
        if (preReadings.size >= MIN_PRE_READINGS) {
            return preReadings.map { it.sgv.toDouble() }.average()
        }
        // Fallback: closest reading before meal
        return allReadings
            .filter { it.ts < mealTime }
            .maxByOrNull { it.ts }
            ?.sgv?.toDouble()
    }

    private fun computeIAuc(readings: List<GlucoseReading>, baseline: Double): Double {
        if (readings.size < 2) return 0.0
        var auc = 0.0
        for (i in 0 until readings.size - 1) {
            val dt = (readings[i + 1].ts - readings[i].ts).toDouble() / MS_PER_MINUTE
            val h1 = (readings[i].sgv - baseline).coerceAtLeast(0.0)
            val h2 = (readings[i + 1].sgv - baseline).coerceAtLeast(0.0)
            auc += (h1 + h2) / 2.0 * dt
        }
        return auc
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.meal.MealAnalyzerTest"`
Expected: PASS — all 10 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/meal/MealAnalyzer.kt \
       app/src/test/java/com/psjostrom/strimma/data/meal/MealAnalyzerTest.kt
git commit -m "Add MealAnalyzer with per-meal postprandial metric computation"
```

---

## Task 6: MealStatsCalculator — Grouping & Aggregates

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/meal/MealStatsCalculator.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/data/meal/MealStatsCalculatorTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/psjostrom/strimma/data/meal/MealStatsCalculatorTest.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MealStatsCalculatorTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    private fun resultAt(hour: Int, carbs: Double, tir: Double, excursion: Double, recovery: Int?) =
        MealPostprandialResult(
            mealTime = Instant.parse("2026-03-27T00:00:00Z")
                .atZone(ZoneId.of("UTC"))
                .withHour(hour)
                .withZoneSameLocal(zone)
                .toInstant().toEpochMilli(),
            carbGrams = carbs,
            baselineMgdl = 108.0,
            peakMgdl = 108.0 + excursion,
            excursionMgdl = excursion,
            timeToPeakMinutes = 45,
            recoveryMinutes = recovery,
            tirPercent = tir,
            iAucMgdlMin = 500.0,
            iobAtMeal = 1.5,
            windowMinutes = 180,
            readings = emptyList()
        )

    @Test
    fun `groups by time slot`() {
        val results = listOf(
            resultAt(7, 30.0, 90.0, 40.0, 120),   // breakfast
            resultAt(8, 25.0, 85.0, 35.0, 110),   // breakfast
            resultAt(12, 45.0, 70.0, 60.0, 150),  // lunch
            resultAt(19, 50.0, 55.0, 80.0, null),  // dinner
        )
        val grouped = MealStatsCalculator.groupByTimeSlot(results, zone)
        assertEquals(3, grouped.size)
        assertEquals(2, grouped[MealTimeSlot.BREAKFAST]?.size)
        assertEquals(1, grouped[MealTimeSlot.LUNCH]?.size)
        assertEquals(1, grouped[MealTimeSlot.DINNER]?.size)
    }

    @Test
    fun `aggregate computes averages`() {
        val results = listOf(
            resultAt(7, 30.0, 90.0, 40.0, 120),
            resultAt(8, 25.0, 80.0, 50.0, 100),
        )
        val agg = MealStatsCalculator.aggregate(results)
        assertEquals(2, agg.mealCount)
        assertEquals(85.0, agg.avgTirPercent, 0.1)
        assertEquals(45.0, agg.avgExcursionMgdl, 0.1)
        assertEquals(110, agg.avgRecoveryMinutes)
    }

    @Test
    fun `aggregate recovery null when some meals have no recovery`() {
        val results = listOf(
            resultAt(7, 30.0, 90.0, 40.0, 120),
            resultAt(8, 25.0, 80.0, 50.0, null),
        )
        val agg = MealStatsCalculator.aggregate(results)
        assertEquals(120, agg.avgRecoveryMinutes) // average of non-null only
    }

    @Test
    fun `groups by carb size`() {
        val results = listOf(
            resultAt(7, 10.0, 90.0, 20.0, 60),    // small
            resultAt(8, 35.0, 75.0, 50.0, 120),   // medium
            resultAt(9, 65.0, 55.0, 80.0, null),   // large
        )
        val grouped = MealStatsCalculator.groupByCarbSize(results)
        assertEquals(1, grouped[CarbSizeBucket.SMALL]?.size)
        assertEquals(1, grouped[CarbSizeBucket.MEDIUM]?.size)
        assertEquals(1, grouped[CarbSizeBucket.LARGE]?.size)
    }

    @Test
    fun `empty results produce empty aggregates`() {
        val agg = MealStatsCalculator.aggregate(emptyList())
        assertEquals(0, agg.mealCount)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.meal.MealStatsCalculatorTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MealStatsCalculator**

Create `app/src/main/java/com/psjostrom/strimma/data/meal/MealStatsCalculator.kt`:

```kotlin
package com.psjostrom.strimma.data.meal

import java.time.ZoneId

data class MealAggregateStats(
    val mealCount: Int,
    val avgTirPercent: Double,
    val avgExcursionMgdl: Double,
    val avgRecoveryMinutes: Int?
)

object MealStatsCalculator {

    fun groupByTimeSlot(
        results: List<MealPostprandialResult>,
        zone: ZoneId
    ): Map<MealTimeSlot, List<MealPostprandialResult>> =
        results.groupBy { MealTimeSlot.fromTimestamp(it.mealTime, zone) }

    fun groupByCarbSize(
        results: List<MealPostprandialResult>
    ): Map<CarbSizeBucket, List<MealPostprandialResult>> =
        results.groupBy { CarbSizeBucket.fromGrams(it.carbGrams) }

    fun aggregate(results: List<MealPostprandialResult>): MealAggregateStats {
        if (results.isEmpty()) return MealAggregateStats(0, 0.0, 0.0, null)

        val recoveries = results.mapNotNull { it.recoveryMinutes }

        return MealAggregateStats(
            mealCount = results.size,
            avgTirPercent = results.map { it.tirPercent }.average(),
            avgExcursionMgdl = results.map { it.excursionMgdl }.average(),
            avgRecoveryMinutes = if (recoveries.isEmpty()) null else recoveries.average().toInt()
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.meal.MealStatsCalculatorTest"`
Expected: PASS — all 5 tests

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/meal/MealStatsCalculator.kt \
       app/src/test/java/com/psjostrom/strimma/data/meal/MealStatsCalculatorTest.kt
git commit -m "Add MealStatsCalculator with time-slot and carb-size grouping"
```

---

## Task 7: MealStatsTab UI — Aggregate Header + Meal Cards

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/ui/MealStatsTab.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/StatsScreen.kt`

This is the largest task. It wires everything together: fetches data, computes analysis, renders the tab.

- [ ] **Step 1: Create MealStatsTab composable**

Create `app/src/main/java/com/psjostrom/strimma/ui/MealStatsTab.kt`. This file contains:
- `MealStatsTab` — top-level composable with time-slot chips, aggregate cards, meal list
- `MealAggregateHeader` — summary cards (avg TIR, avg excursion, avg recovery) with carb-size breakdown
- `MealCard` — collapsed/expanded per-meal card
- `MealSparkline` — Canvas composable with 5 layers

```kotlin
package com.psjostrom.strimma.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.meal.*
import com.psjostrom.strimma.ui.theme.*
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val ALL_SLOTS = listOf(null) + MealTimeSlot.entries
private val analyzer = MealAnalyzer()

@Composable
fun MealStatsTab(
    onLoadReadings: suspend (Int) -> List<GlucoseReading>,
    onLoadCarbTreatments: suspend (Long, Long) -> List<Treatment>,
    onLoadAllTreatments: suspend (Long) -> List<Treatment>,
    periods: List<Pair<Int, String>>,
    selectedPeriod: Int,
    onPeriodChange: (Int) -> Unit,
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit,
    tauMinutes: Double
) {
    val zone = remember { ZoneId.systemDefault() }
    var selectedSlot by remember { mutableStateOf<MealTimeSlot?>(null) }

    val results by produceState<List<MealPostprandialResult>>(emptyList(), selectedPeriod) {
        val (hours, _) = periods[selectedPeriod]
        val now = System.currentTimeMillis()
        val start = now - hours * 3600_000L
        val readings = onLoadReadings(hours)
        val carbTreatments = onLoadCarbTreatments(start, now)
        val allTreatments = onLoadAllTreatments(start)

        value = carbTreatments.mapIndexedNotNull { i, meal ->
            val nextMealTime = carbTreatments.getOrNull(i + 1)?.createdAt
            analyzer.analyze(
                meal = meal, readings = readings,
                bgLow = bgLow.toDouble(), bgHigh = bgHigh.toDouble(),
                nextMealTime = nextMealTime,
                allTreatments = allTreatments, tauMinutes = tauMinutes
            )
        }
    }

    // Period selector
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        periods.forEachIndexed { index, (_, label) ->
            SegmentedButton(
                selected = selectedPeriod == index,
                onClick = { onPeriodChange(index) },
                shape = SegmentedButtonDefaults.itemShape(index, periods.size)
            ) { Text(label) }
        }
    }

    // Time slot chips
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ALL_SLOTS.forEach { slot ->
            FilterChip(
                selected = selectedSlot == slot,
                onClick = { selectedSlot = slot },
                label = { Text(slot?.label ?: "All", fontSize = 12.sp) }
            )
        }
    }

    val filtered = remember(results, selectedSlot) {
        if (selectedSlot == null) results
        else results.filter { MealTimeSlot.fromTimestamp(it.mealTime, zone) == selectedSlot }
    }

    if (filtered.isEmpty()) {
        Text(
            "No meal data",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 32.dp)
        )
    } else {
        // Aggregate header
        val aggregate = remember(filtered) { MealStatsCalculator.aggregate(filtered) }
        val carbGroups = remember(filtered) { MealStatsCalculator.groupByCarbSize(filtered) }
        MealAggregateHeader(aggregate, carbGroups, glucoseUnit, bgLow, bgHigh)

        // Meal cards (newest first)
        filtered.sortedByDescending { it.mealTime }.forEach { result ->
            MealCard(result, glucoseUnit, bgLow, bgHigh)
        }
    }
}

@Composable
private fun MealAggregateHeader(
    stats: MealAggregateStats,
    carbGroups: Map<CarbSizeBucket, List<MealPostprandialResult>>,
    glucoseUnit: GlucoseUnit,
    bgLow: Float,
    bgHigh: Float
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${stats.mealCount} meals",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCell("Avg TIR", "%.0f%%".format(stats.avgTirPercent),
                    tirColor(stats.avgTirPercent))
                StatCell("Avg Excursion", "+%s".format(glucoseUnit.format(stats.avgExcursionMgdl)),
                    excursionColor(stats.avgExcursionMgdl, bgHigh.toDouble()))
                stats.avgRecoveryMinutes?.let {
                    StatCell("Avg Recovery", "${it} min", MaterialTheme.colorScheme.onSurface)
                }
            }
            if (carbGroups.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text("BY CARB SIZE",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CarbSizeBucket.entries.forEach { bucket ->
                        val group = carbGroups[bucket] ?: return@forEach
                        val agg = MealStatsCalculator.aggregate(group)
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(bucket.label, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("(${agg.mealCount})", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("+%s".format(glucoseUnit.format(agg.avgExcursionMgdl)),
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = excursionColor(agg.avgExcursionMgdl, bgHigh.toDouble()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun MealCard(
    result: MealPostprandialResult,
    glucoseUnit: GlucoseUnit,
    bgLow: Float,
    bgHigh: Float
) {
    var expanded by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val rating = result.tirRating()
                    Text("■ ", color = ratingColor(rating), fontSize = 14.sp)
                    Text("%.0fg carbs".format(result.carbGrams),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(" · ${timeFormat.format(Date(result.mealTime))}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = RoundedCornerShape(100),
                    color = ratingBgColor(result.tirRating())
                ) {
                    Text("%.0f%% TIR".format(result.tirPercent),
                        fontSize = 12.sp,
                        color = ratingColor(result.tirRating()),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }

            // Pre → Peak
            Text(
                "Pre: %s → Peak: %s %s".format(
                    glucoseUnit.format(result.baselineMgdl),
                    glucoseUnit.format(result.peakMgdl),
                    glucoseUnit.shortLabel
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Sparkline
            Spacer(Modifier.height(8.dp))
            MealSparkline(
                readings = result.readings,
                baselineMgdl = result.baselineMgdl,
                bgLow = bgLow.toDouble(),
                bgHigh = bgHigh.toDouble(),
                height = if (expanded) 80.dp else 40.dp,
                showPeakLabel = expanded,
                peakMgdl = result.peakMgdl,
                glucoseUnit = glucoseUnit
            )

            // Expanded metrics
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricCell("Peak", glucoseUnit.format(result.peakMgdl), glucoseUnit.shortLabel)
                        MetricCell("Excursion", "+${glucoseUnit.format(result.excursionMgdl)}", glucoseUnit.shortLabel)
                        MetricCell("Time to peak", "${result.timeToPeakMinutes}", "min")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val recoveryText = result.recoveryMinutes?.toString() ?: ">${result.windowMinutes}"
                        MetricCell("Recovery", recoveryText, "min")
                        MetricCell("IOB", "%.1f".format(result.iobAtMeal), "U")
                        MetricCell("iAUC", "%.0f".format(
                            if (glucoseUnit == GlucoseUnit.MMOL) result.iAucMgdlMin / GlucoseUnit.MGDL_FACTOR
                            else result.iAucMgdlMin
                        ), "")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Baseline) {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            if (unit.isNotEmpty()) {
                Text(" $unit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MealSparkline(
    readings: List<GlucoseReading>,
    baselineMgdl: Double,
    bgLow: Double,
    bgHigh: Double,
    height: androidx.compose.ui.unit.Dp,
    showPeakLabel: Boolean,
    peakMgdl: Double,
    glucoseUnit: GlucoseUnit
) {
    if (readings.size < 2) return

    val inRangeColor = InRange.copy(alpha = 0.07f)
    val thresholdColor = AboveHigh.copy(alpha = 0.3f)
    val baselineColor = Color(0xFF6A5F80).copy(alpha = 0.5f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val sorted = readings.sortedBy { it.ts }
        val minTs = sorted.first().ts.toFloat()
        val maxTs = sorted.last().ts.toFloat()
        val allSgv = sorted.map { it.sgv.toDouble() } + bgLow + bgHigh + baselineMgdl
        val yMin = allSgv.min() - 10.0
        val yMax = allSgv.max() + 10.0

        fun xFor(ts: Long) = ((ts - minTs) / (maxTs - minTs)) * size.width
        fun yFor(mgdl: Double) = ((yMax - mgdl) / (yMax - yMin)).toFloat() * size.height

        // Layer 1: In-range zone band
        val highY = yFor(bgHigh)
        val lowY = yFor(bgLow)
        drawRect(inRangeColor, Offset(0f, highY), Size(size.width, lowY - highY))

        // Layer 2: Threshold lines
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
        drawLine(thresholdColor, Offset(0f, highY), Offset(size.width, highY),
            pathEffect = dashEffect)
        drawLine(thresholdColor, Offset(0f, lowY), Offset(size.width, lowY),
            pathEffect = dashEffect)

        // Layer 3: Excursion fill
        val baseY = yFor(baselineMgdl)
        for (i in 0 until sorted.size - 1) {
            val x1 = xFor(sorted[i].ts)
            val x2 = xFor(sorted[i + 1].ts)
            val y1 = yFor(sorted[i].sgv.toDouble()).coerceAtMost(baseY)
            val y2 = yFor(sorted[i + 1].sgv.toDouble()).coerceAtMost(baseY)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
                lineTo(x2, baseY)
                lineTo(x1, baseY)
                close()
            }
            drawPath(path, AboveHigh.copy(alpha = 0.15f), style = Fill)
        }

        // Layer 4: BG curve with zone-colored dots
        for (i in 0 until sorted.size - 1) {
            val color = composeColorFor(sorted[i].sgv.toDouble(), bgLow, bgHigh)
            drawLine(color, Offset(xFor(sorted[i].ts), yFor(sorted[i].sgv.toDouble())),
                Offset(xFor(sorted[i + 1].ts), yFor(sorted[i + 1].sgv.toDouble())),
                strokeWidth = 2f)
        }
        sorted.forEach { r ->
            val color = composeColorFor(r.sgv.toDouble(), bgLow, bgHigh)
            drawCircle(color, 3f, Offset(xFor(r.ts), yFor(r.sgv.toDouble())))
        }

        // Layer 5: Baseline
        drawLine(baselineColor, Offset(0f, baseY), Offset(size.width, baseY),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
    }
}

// Color helpers

private fun composeColorFor(mgdl: Double, bgLow: Double, bgHigh: Double): Color = when {
    mgdl < 54.0 || mgdl > 234.0 -> BelowLow
    mgdl < bgLow -> BelowLow
    mgdl > bgHigh -> AboveHigh
    else -> InRange
}

@Composable
private fun ratingColor(rating: TirRating): Color = when (rating) {
    TirRating.GOOD -> Color(0xFF4ADE80)
    TirRating.MODERATE -> AboveHigh
    TirRating.POOR -> BelowLow
}

@Composable
private fun ratingBgColor(rating: TirRating): Color = when (rating) {
    TirRating.GOOD -> Color(0xFF1A3A2A)
    TirRating.MODERATE -> Color(0xFF35280E)
    TirRating.POOR -> Color(0xFF351525)
}

private fun tirColor(tir: Double): Color = when {
    tir >= 80.0 -> Color(0xFF4ADE80)
    tir >= 50.0 -> AboveHigh
    else -> BelowLow
}

private fun excursionColor(excursion: Double, bgHigh: Double): Color = when {
    excursion > bgHigh * 0.5 -> BelowLow
    excursion > bgHigh * 0.25 -> AboveHigh
    else -> InRange
}
```

- [ ] **Step 2: Wire MealStatsTab into StatsScreen**

In `StatsScreen.kt`, make these changes:

Add the tab constant:
```kotlin
private const val TAB_METRICS = 0
private const val TAB_AGP = 1
private const val TAB_MEALS = 2
```

Update `StatsScreen` signature to add the new callbacks and `tauMinutes`:
```kotlin
fun StatsScreen(
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    hbA1cUnit: HbA1cUnit = HbA1cUnit.MMOL_MOL,
    onLoadReadings: suspend (Int) -> List<GlucoseReading>,
    onLoadCarbTreatments: suspend (Long, Long) -> List<Treatment>,
    onLoadAllTreatments: suspend (Long) -> List<Treatment>,
    tauMinutes: Double,
    onExportCsv: suspend (Int) -> String,
    onBack: () -> Unit
)
```

Update `SingleChoiceSegmentedButtonRow` to have 3 buttons:
```kotlin
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    SegmentedButton(
        selected = selectedTab == TAB_METRICS,
        onClick = { selectedTab = TAB_METRICS },
        shape = SegmentedButtonDefaults.itemShape(0, 3)
    ) { Text(stringResource(R.string.stats_tab_metrics)) }
    SegmentedButton(
        selected = selectedTab == TAB_AGP,
        onClick = { selectedTab = TAB_AGP },
        shape = SegmentedButtonDefaults.itemShape(1, 3)
    ) { Text(stringResource(R.string.stats_tab_agp)) }
    SegmentedButton(
        selected = selectedTab == TAB_MEALS,
        onClick = { selectedTab = TAB_MEALS },
        shape = SegmentedButtonDefaults.itemShape(2, 3)
    ) { Text("Meals") }
}
```

Add the tab case in `when (selectedTab)`:
```kotlin
TAB_MEALS -> MealStatsTab(
    onLoadReadings = onLoadReadings,
    onLoadCarbTreatments = onLoadCarbTreatments,
    onLoadAllTreatments = onLoadAllTreatments,
    periods = periods,
    selectedPeriod = selectedPeriod,
    onPeriodChange = { selectedPeriod = it },
    bgLow = bgLow,
    bgHigh = bgHigh,
    glucoseUnit = glucoseUnit,
    tauMinutes = tauMinutes
)
```

- [ ] **Step 3: Update the caller of StatsScreen**

Find where `StatsScreen` is called (in `MainScreen.kt` or the nav host) and pass the new parameters. The `onLoadCarbTreatments` callback uses `TreatmentDao.carbsInRange()`, `onLoadAllTreatments` uses `TreatmentDao.allSince()`, and `tauMinutes` comes from `SettingsRepository`.

Search for the call site:
```bash
grep -rn "StatsScreen(" app/src/main/java/ --include="*.kt" | grep -v "^app/src/main/java/com/psjostrom/strimma/ui/StatsScreen.kt"
```

Add the new lambdas at the call site, threading `treatmentDao` and `settings` through as needed.

- [ ] **Step 4: Verify build and run**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MealStatsTab.kt \
       app/src/main/java/com/psjostrom/strimma/ui/StatsScreen.kt
git commit -m "Add Meals tab to Stats screen with aggregate header and per-meal cards"
```

---

## Task 8: Integration Test & Polish

**Files:**
- All files from previous tasks

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: Run lint**

Run: `./gradlew lintDebug`
Expected: No new errors (may have baseline warnings)

- [ ] **Step 3: Fix any lint or compilation issues**

Address issues found in steps 1-2.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "Fix lint and integration issues for meal analysis"
```

---

## Summary

| Task | What | Tests |
|------|------|-------|
| 1 | Treatment retention 48h→14d + DAO query | Build check |
| 2 | MealTimeSlot enum | 10 tests |
| 3 | CarbSizeBucket enum | Build check |
| 4 | MealPostprandialResult data class | Build check |
| 5 | MealAnalyzer core logic | 10 tests |
| 6 | MealStatsCalculator grouping | 5 tests |
| 7 | MealStatsTab UI + StatsScreen wiring | Build + visual |
| 8 | Integration test & polish | Full suite + lint |
