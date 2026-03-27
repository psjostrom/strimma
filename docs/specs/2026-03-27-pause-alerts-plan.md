# Pause Alerts by Category — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pause all low or all high alerts for 1h/1.5h/2h, with quick access from the main screen and alert settings.

**Architecture:** `AlertCategory` enum + pause/check methods on existing `AlertManager` (SharedPreferences-backed). `MainViewModel` exposes pause state as `StateFlow`. New `PauseAlertsSheet` composable shared between MainScreen and AlertsSettings. Pause indicator pills in `BgHeader`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, SharedPreferences (existing `snoozePrefs`), Hilt, JUnit 4 + Robolectric

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `notification/AlertManager.kt` | Modify | Add `AlertCategory` enum, `pauseCategory()`, `cancelPause()`, `isPaused()`, `pauseExpiryMs()`. Gate `checkReading()`/`checkPredictive()` on pause state. |
| `ui/MainViewModel.kt` | Modify | Add pause state flows + `pauseAlerts()`/`cancelPause()` methods |
| `ui/components/PauseAlertsSheet.kt` | Create | `ModalBottomSheet` with Low/High rows, duration chips, active state + cancel |
| `ui/MainScreen.kt` | Modify | Add bell-slash icon to top bar, active pause pills in `BgHeader`, wire sheet |
| `ui/settings/AlertsSettings.kt` | Modify | Add active pause card + "Pause alerts" button at top |
| `ui/MainActivity.kt` | Modify | Pass pause state + callbacks to `MainScreen` and `AlertsSettings` |
| `res/values/strings.xml` | Modify | Add pause-related strings |
| `test/.../AlertManagerPauseTest.kt` | Create | Unit tests for pause logic |
| `test/.../PauseAlertsSheetTest.kt` | Create | Compose UI tests for the bottom sheet |

---

### Task 1: AlertCategory enum + pause methods on AlertManager

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/notification/AlertManager.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/notification/AlertManagerPauseTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/psjostrom/strimma/notification/AlertManagerPauseTest.kt`:

```kotlin
package com.psjostrom.strimma.notification

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AlertManagerPauseTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `pauseCategory stores expiry timestamp`() {
        val now = System.currentTimeMillis()
        val durationMs = 3600_000L // 1h
        AlertManager.pauseCategory(prefs, AlertCategory.HIGH, durationMs)

        val expiry = prefs.getLong(AlertCategory.HIGH.prefsKey, 0L)
        assertTrue(expiry >= now + durationMs - 100)
        assertTrue(expiry <= now + durationMs + 100)
    }

    @Test
    fun `isPaused returns true when pause is active`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3600_000L)
        assertTrue(AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
    }

    @Test
    fun `isPaused returns false when no pause set`() {
        assertFalse(AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH))
    }

    @Test
    fun `isPaused returns false and clears expired pause`() {
        // Set pause that expired 1 second ago
        prefs.edit().putLong(AlertCategory.LOW.prefsKey, System.currentTimeMillis() - 1000).apply()
        assertFalse(AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
        assertEquals(0L, prefs.getLong(AlertCategory.LOW.prefsKey, 0L))
    }

    @Test
    fun `cancelPause removes the key`() {
        AlertManager.pauseCategory(prefs, AlertCategory.HIGH, 3600_000L)
        AlertManager.cancelPause(prefs, AlertCategory.HIGH)
        assertFalse(AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH))
    }

    @Test
    fun `pauseExpiryMs returns expiry when active`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3600_000L)
        val expiry = AlertManager.pauseExpiryMs(prefs, AlertCategory.LOW)
        assertNotNull(expiry)
        assertTrue(expiry!! > System.currentTimeMillis())
    }

    @Test
    fun `pauseExpiryMs returns null when not paused`() {
        assertNull(AlertManager.pauseExpiryMs(prefs, AlertCategory.HIGH))
    }

    @Test
    fun `low and high pauses are independent`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3600_000L)
        assertTrue(AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
        assertFalse(AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.notification.AlertManagerPauseTest" --info`
Expected: Compilation failure — `AlertCategory` and static methods don't exist yet.

- [ ] **Step 3: Create AlertCategory enum and implement pause methods**

Add to `AlertManager.kt`, inside the `companion object` block, after the existing constants:

```kotlin
enum AlertCategory(val prefsKey: String) {
    LOW("pause_low"),
    HIGH("pause_high")
}
```

Wait — the enum should be top-level so it's accessible without importing the companion. Add it **before** the `AlertManager` class definition:

```kotlin
enum class AlertCategory(val prefsKey: String) {
    LOW("pause_low"),
    HIGH("pause_high")
}
```

Add these static methods inside `AlertManager.companion`:

```kotlin
fun pauseCategory(prefs: SharedPreferences, category: AlertCategory, durationMs: Long) {
    prefs.edit().putLong(category.prefsKey, System.currentTimeMillis() + durationMs).apply()
    DebugLog.log("Alert category ${category.name} paused for ${durationMs / 60_000}min")
}

fun cancelPause(prefs: SharedPreferences, category: AlertCategory) {
    prefs.edit().remove(category.prefsKey).apply()
    DebugLog.log("Alert category ${category.name} pause cancelled")
}

fun isCategoryPaused(prefs: SharedPreferences, category: AlertCategory): Boolean {
    val until = prefs.getLong(category.prefsKey, 0L)
    if (until == 0L) return false
    if (System.currentTimeMillis() >= until) {
        prefs.edit().remove(category.prefsKey).apply()
        return false
    }
    return true
}

fun pauseExpiryMs(prefs: SharedPreferences, category: AlertCategory): Long? {
    val until = prefs.getLong(category.prefsKey, 0L)
    if (until == 0L) return null
    if (System.currentTimeMillis() >= until) {
        prefs.edit().remove(category.prefsKey).apply()
        return null
    }
    return until
}
```

Add `import android.content.SharedPreferences` if not already present (it's used internally via `snoozePrefs` but may not be imported at the top level — check).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.notification.AlertManagerPauseTest" --info`
Expected: All 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/notification/AlertManager.kt \
       app/src/test/java/com/psjostrom/strimma/notification/AlertManagerPauseTest.kt
git commit -m "feat: add AlertCategory enum and pause methods to AlertManager"
```

---

### Task 2: Gate checkReading/checkPredictive on category pause

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/notification/AlertManager.kt`

- [ ] **Step 1: Add instance-level convenience methods**

Add these instance methods to `AlertManager` (below the existing `snooze()` method):

```kotlin
fun pauseAlertCategory(category: AlertCategory, durationMs: Long) {
    pauseCategory(snoozePrefs, category, durationMs)
    // Cancel any active notifications in this category
    when (category) {
        AlertCategory.LOW -> {
            notificationManager.cancel(ALERT_URGENT_LOW_ID)
            notificationManager.cancel(ALERT_LOW_ID)
            notificationManager.cancel(ALERT_LOW_SOON_ID)
        }
        AlertCategory.HIGH -> {
            notificationManager.cancel(ALERT_URGENT_HIGH_ID)
            notificationManager.cancel(ALERT_HIGH_ID)
            notificationManager.cancel(ALERT_HIGH_SOON_ID)
        }
    }
}

fun cancelAlertPause(category: AlertCategory) {
    cancelPause(snoozePrefs, category)
}

fun isAlertCategoryPaused(category: AlertCategory): Boolean =
    isCategoryPaused(snoozePrefs, category)

fun alertPauseExpiryMs(category: AlertCategory): Long? =
    pauseExpiryMs(snoozePrefs, category)
```

- [ ] **Step 2: Gate low alerts in checkReading**

In `checkReading()`, wrap the low alert block (lines ~204-222) with a pause check. Replace:

```kotlin
// --- Lows (urgent takes priority) ---
if (urgentLowEnabled && mgdl <= urgentLowThreshold) {
```

With:

```kotlin
// --- Lows (urgent takes priority) ---
val lowPaused = isCategoryPaused(snoozePrefs, AlertCategory.LOW)
if (lowPaused) {
    notificationManager.cancel(ALERT_URGENT_LOW_ID)
    notificationManager.cancel(ALERT_LOW_ID)
} else if (urgentLowEnabled && mgdl <= urgentLowThreshold) {
```

And at the end of the low block, change the `else` to `else if (!lowPaused)`:

The full low block becomes:

```kotlin
val lowPaused = isCategoryPaused(snoozePrefs, AlertCategory.LOW)
if (lowPaused) {
    notificationManager.cancel(ALERT_URGENT_LOW_ID)
    notificationManager.cancel(ALERT_LOW_ID)
} else if (urgentLowEnabled && mgdl <= urgentLowThreshold) {
    alreadyLow = true
    if (!isSnoozed(ALERT_URGENT_LOW_ID, now)) {
        val title = context.getString(R.string.alert_urgent_low_title)
        fireAlert(ALERT_URGENT_LOW_ID, CHANNEL_URGENT_LOW, title, unit.formatWithUnit(mgdl))
        notificationManager.cancel(ALERT_LOW_ID)
    }
} else if (lowEnabled && mgdl < lowThreshold) {
    alreadyLow = true
    if (!isSnoozed(ALERT_LOW_ID, now)) {
        fireAlert(ALERT_LOW_ID, CHANNEL_LOW, context.getString(R.string.alert_low_title), unit.formatWithUnit(mgdl))
    }
    notificationManager.cancel(ALERT_URGENT_LOW_ID)
    clearSnooze(ALERT_URGENT_LOW_ID)
} else {
    notificationManager.cancel(ALERT_LOW_ID)
    notificationManager.cancel(ALERT_URGENT_LOW_ID)
}
```

- [ ] **Step 3: Gate high alerts in checkReading**

Same pattern for the high block:

```kotlin
val highPaused = isCategoryPaused(snoozePrefs, AlertCategory.HIGH)
if (highPaused) {
    notificationManager.cancel(ALERT_URGENT_HIGH_ID)
    notificationManager.cancel(ALERT_HIGH_ID)
} else if (urgentHighEnabled && mgdl >= urgentHighThreshold) {
    alreadyHigh = true
    if (!isSnoozed(ALERT_URGENT_HIGH_ID, now)) {
        val title = context.getString(R.string.alert_urgent_high_title)
        fireAlert(ALERT_URGENT_HIGH_ID, CHANNEL_URGENT_HIGH, title, unit.formatWithUnit(mgdl))
        notificationManager.cancel(ALERT_HIGH_ID)
    }
} else if (highEnabled && mgdl > highThreshold) {
    alreadyHigh = true
    if (!isSnoozed(ALERT_HIGH_ID, now)) {
        fireAlert(ALERT_HIGH_ID, CHANNEL_HIGH, context.getString(R.string.alert_high_title), unit.formatWithUnit(mgdl))
    }
    notificationManager.cancel(ALERT_URGENT_HIGH_ID)
    clearSnooze(ALERT_URGENT_HIGH_ID)
} else {
    notificationManager.cancel(ALERT_HIGH_ID)
    notificationManager.cancel(ALERT_URGENT_HIGH_ID)
}
```

- [ ] **Step 4: Gate predictive alerts in checkPredictive**

In `checkPredictive()`, add pause checks. After the existing `lowSoonEnabled`/`highSoonEnabled` reads:

```kotlin
val lowPaused = isCategoryPaused(snoozePrefs, AlertCategory.LOW)
val highPaused = isCategoryPaused(snoozePrefs, AlertCategory.HIGH)
```

Then in the low-soon condition, add `&& !lowPaused`:

```kotlin
if (lowSoonEnabled && !lowPaused && !alreadyLow && crossing?.type == CrossingType.LOW
```

And high-soon:

```kotlin
if (highSoonEnabled && !highPaused && !alreadyHigh && crossing?.type == CrossingType.HIGH
```

When paused, also cancel any existing predictive notifications. Add after the pause checks:

```kotlin
if (lowPaused) notificationManager.cancel(ALERT_LOW_SOON_ID)
if (highPaused) notificationManager.cancel(ALERT_HIGH_SOON_ID)
```

- [ ] **Step 5: Run all tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: All tests pass (existing + new pause tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/notification/AlertManager.kt
git commit -m "feat: gate alert firing on category pause state"
```

---

### Task 3: ViewModel pause state + string resources

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add string resources**

Add to `app/src/main/res/values/strings.xml` in the alerts section:

```xml
<!-- Alert pause -->
<string name="pause_alerts">Pause alerts</string>
<string name="pause_low_alerts">Low alerts</string>
<string name="pause_high_alerts">High alerts</string>
<string name="pause_duration_1h">1h</string>
<string name="pause_duration_1_5h">1.5h</string>
<string name="pause_duration_2h">2h</string>
<string name="pause_active_minutes">Paused · %1$d min left</string>
<string name="pause_active_hours_minutes">Paused · %1$dh %2$dm left</string>
<string name="pause_cancel">Cancel</string>
<string name="pause_low_active">Low alerts paused · %1$s</string>
<string name="pause_high_active">High alerts paused · %1$s</string>
```

- [ ] **Step 2: Add pause state and methods to MainViewModel**

Add imports to `MainViewModel.kt`:

```kotlin
import com.psjostrom.strimma.notification.AlertCategory
```

Add these state flows after the existing alert settings block (after line ~169):

```kotlin
// Alert pause state
private val _pauseLowExpiryMs = MutableStateFlow<Long?>(null)
val pauseLowExpiryMs: StateFlow<Long?> = _pauseLowExpiryMs

private val _pauseHighExpiryMs = MutableStateFlow<Long?>(null)
val pauseHighExpiryMs: StateFlow<Long?> = _pauseHighExpiryMs
```

Add a polling coroutine in the `init` block (inside the existing `init {}`, after the calendar polling `launch`):

```kotlin
viewModelScope.launch {
    while (currentCoroutineContext().isActive) {
        _pauseLowExpiryMs.value = alertManager.alertPauseExpiryMs(AlertCategory.LOW)
        _pauseHighExpiryMs.value = alertManager.alertPauseExpiryMs(AlertCategory.HIGH)
        delay(10_000) // Update countdown every 10 seconds
    }
}
```

Add methods after the existing alert setters (after line ~189):

```kotlin
fun pauseAlerts(category: AlertCategory, durationMs: Long) {
    alertManager.pauseAlertCategory(category, durationMs)
    // Immediately update UI state
    when (category) {
        AlertCategory.LOW -> _pauseLowExpiryMs.value = alertManager.alertPauseExpiryMs(AlertCategory.LOW)
        AlertCategory.HIGH -> _pauseHighExpiryMs.value = alertManager.alertPauseExpiryMs(AlertCategory.HIGH)
    }
}

fun cancelAlertPause(category: AlertCategory) {
    alertManager.cancelAlertPause(category)
    when (category) {
        AlertCategory.LOW -> _pauseLowExpiryMs.value = null
        AlertCategory.HIGH -> _pauseHighExpiryMs.value = null
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt \
       app/src/main/res/values/strings.xml
git commit -m "feat: add pause state flows and string resources"
```

---

### Task 4: PauseAlertsSheet composable

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/ui/components/PauseAlertsSheet.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/ui/components/PauseAlertsSheetTest.kt`

- [ ] **Step 1: Write tests**

Create `app/src/test/java/com/psjostrom/strimma/ui/components/PauseAlertsSheetTest.kt`:

```kotlin
package com.psjostrom.strimma.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.notification.AlertCategory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class PauseAlertsSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows both category rows when no pause active`() {
        composeRule.setContent {
            PauseAlertsSheetContent(
                pauseLowExpiryMs = null,
                pauseHighExpiryMs = null,
                onPause = { _, _ -> },
                onCancel = {}
            )
        }
        composeRule.onNodeWithText("Low alerts").assertExists()
        composeRule.onNodeWithText("High alerts").assertExists()
        // Duration chips visible
        composeRule.onNodeWithText("1h").assertExists()
    }

    @Test
    fun `tapping duration chip calls onPause with correct category and duration`() {
        var pausedCategory: AlertCategory? = null
        var pausedDuration: Long? = null

        composeRule.setContent {
            PauseAlertsSheetContent(
                pauseLowExpiryMs = null,
                pauseHighExpiryMs = null,
                onPause = { cat, dur ->
                    pausedCategory = cat
                    pausedDuration = dur
                },
                onCancel = {}
            )
        }
        // There are two "1h" chips — the first is for Low
        composeRule.onAllNodes(
            androidx.compose.ui.test.hasText("1h")
        )[0].performClick()

        assertEquals(AlertCategory.LOW, pausedCategory)
        assertEquals(3600_000L, pausedDuration)
    }

    @Test
    fun `shows cancel button when pause is active`() {
        val futureExpiry = System.currentTimeMillis() + 1800_000L // 30 min from now

        composeRule.setContent {
            PauseAlertsSheetContent(
                pauseLowExpiryMs = null,
                pauseHighExpiryMs = futureExpiry,
                onPause = { _, _ -> },
                onCancel = {}
            )
        }
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `cancel button calls onCancel with correct category`() {
        var cancelledCategory: AlertCategory? = null
        val futureExpiry = System.currentTimeMillis() + 1800_000L

        composeRule.setContent {
            PauseAlertsSheetContent(
                pauseLowExpiryMs = futureExpiry,
                pauseHighExpiryMs = null,
                onPause = { _, _ -> },
                onCancel = { cancelledCategory = it }
            )
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(AlertCategory.LOW, cancelledCategory)
    }
}
```

Note: We test `PauseAlertsSheetContent` (the inner composable) directly, not the `ModalBottomSheet` wrapper, since Robolectric can't test sheets reliably.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.ui.components.PauseAlertsSheetTest" --info`
Expected: Compilation failure — `PauseAlertsSheetContent` doesn't exist.

- [ ] **Step 3: Implement PauseAlertsSheet**

Create `app/src/main/java/com/psjostrom/strimma/ui/components/PauseAlertsSheet.kt`:

```kotlin
package com.psjostrom.strimma.ui.components

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
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import kotlinx.coroutines.delay

private val DURATIONS = listOf(
    3_600_000L to R.string.pause_duration_1h,
    5_400_000L to R.string.pause_duration_1_5h,
    7_200_000L to R.string.pause_duration_2h
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseAlertsSheet(
    pauseLowExpiryMs: Long?,
    pauseHighExpiryMs: Long?,
    onPause: (AlertCategory, Long) -> Unit,
    onCancel: (AlertCategory) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        PauseAlertsSheetContent(
            pauseLowExpiryMs = pauseLowExpiryMs,
            pauseHighExpiryMs = pauseHighExpiryMs,
            onPause = { cat, dur ->
                onPause(cat, dur)
                onDismiss()
            },
            onCancel = onCancel
        )
    }
}

@Composable
fun PauseAlertsSheetContent(
    pauseLowExpiryMs: Long?,
    pauseHighExpiryMs: Long?,
    onPause: (AlertCategory, Long) -> Unit,
    onCancel: (AlertCategory) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.pause_alerts),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        PauseCategoryRow(
            label = stringResource(R.string.pause_low_alerts),
            color = BelowLow,
            expiryMs = pauseLowExpiryMs,
            category = AlertCategory.LOW,
            onPause = onPause,
            onCancel = onCancel
        )

        Spacer(modifier = Modifier.height(16.dp))

        PauseCategoryRow(
            label = stringResource(R.string.pause_high_alerts),
            color = AboveHigh,
            expiryMs = pauseHighExpiryMs,
            category = AlertCategory.HIGH,
            onPause = onPause,
            onCancel = onCancel
        )
    }
}

@Composable
private fun PauseCategoryRow(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    expiryMs: Long?,
    category: AlertCategory,
    onPause: (AlertCategory, Long) -> Unit,
    onCancel: (AlertCategory) -> Unit
) {
    Column {
        Text(
            text = label,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (expiryMs != null && expiryMs > System.currentTimeMillis()) {
            // Active pause — show countdown + cancel
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                var remainingText by remember { mutableStateOf("") }
                LaunchedEffect(expiryMs) {
                    while (true) {
                        val remaining = expiryMs - System.currentTimeMillis()
                        if (remaining <= 0) break
                        val totalMin = (remaining / 60_000).toInt()
                        val hours = totalMin / 60
                        val min = totalMin % 60
                        remainingText = if (hours > 0) "${hours}h ${min}m left" else "${min}m left"
                        delay(10_000)
                    }
                }
                Text(
                    text = "Paused · $remainingText",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onCancel(category) }) {
                    Text(stringResource(R.string.pause_cancel))
                }
            }
        } else {
            // Not paused — show duration chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DURATIONS.forEach { (durationMs, labelRes) ->
                    FilledTonalButton(
                        onClick = { onPause(category, durationMs) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(labelRes), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.ui.components.PauseAlertsSheetTest" --info`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/components/PauseAlertsSheet.kt \
       app/src/test/java/com/psjostrom/strimma/ui/components/PauseAlertsSheetTest.kt
git commit -m "feat: add PauseAlertsSheet composable with tests"
```

---

### Task 5: Wire pause into MainScreen (top bar icon + pills)

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt`

- [ ] **Step 1: Add pause parameters to MainScreen**

Add these parameters to the `MainScreen` composable signature (after `onExerciseClick`):

```kotlin
pauseLowExpiryMs: Long? = null,
pauseHighExpiryMs: Long? = null,
onPauseAlerts: (AlertCategory, Long) -> Unit = { _, _ -> },
onCancelPause: (AlertCategory) -> Unit = {}
```

Add imports at the top:

```kotlin
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.ui.components.PauseAlertsSheet
import androidx.compose.material.icons.outlined.NotificationsOff
```

- [ ] **Step 2: Add bottom sheet state and top bar icon**

Inside `MainScreen`, add sheet state:

```kotlin
var showPauseSheet by remember { mutableStateOf(false) }
```

In the `TopAppBar` `actions`, add the bell-slash icon **before** the exercise icon (so it's leftmost in the action row):

```kotlin
IconButton(onClick = { showPauseSheet = true }) {
    Icon(
        Icons.Outlined.NotificationsOff,
        contentDescription = stringResource(R.string.pause_alerts),
        tint = if (pauseLowExpiryMs != null || pauseHighExpiryMs != null)
            InRange else MaterialTheme.colorScheme.outline
    )
}
```

- [ ] **Step 3: Add bottom sheet rendering**

After the `ExerciseDetailSheet` block (around line 120), add:

```kotlin
if (showPauseSheet) {
    PauseAlertsSheet(
        pauseLowExpiryMs = pauseLowExpiryMs,
        pauseHighExpiryMs = pauseHighExpiryMs,
        onPause = onPauseAlerts,
        onCancel = onCancelPause,
        onDismiss = { showPauseSheet = false }
    )
}
```

- [ ] **Step 4: Add pause indicator pills to BgHeader**

Add `pauseLowExpiryMs` and `pauseHighExpiryMs` parameters to `BgHeader`:

```kotlin
private fun BgHeader(
    reading: GlucoseReading?, bgLow: Float, bgHigh: Float,
    glucoseUnit: GlucoseUnit, crossing: ThresholdCrossing? = null,
    followerStatus: FollowerStatus, iob: Double = 0.0,
    treatments: List<Treatment> = emptyList(),
    iobTauMinutes: Double = 55.0,
    pauseLowExpiryMs: Long? = null,
    pauseHighExpiryMs: Long? = null,
    onPausePillClick: () -> Unit = {}
)
```

After the IOB pill block (after the `if (iob > 0.0)` block, around line 364), add the pause pills:

```kotlin
// Pause indicator pills
@Composable
fun PausePill(expiryMs: Long, label: String, color: androidx.compose.ui.graphics.Color, tintBg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    var remainingText by remember { mutableStateOf("") }
    LaunchedEffect(expiryMs) {
        while (true) {
            val remaining = expiryMs - System.currentTimeMillis()
            if (remaining <= 0) break
            val totalMin = (remaining / 60_000).toInt()
            val hours = totalMin / 60
            val min = totalMin % 60
            remainingText = if (hours > 0) "${hours}h ${min}m" else "${min}m"
            delay(10_000)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(100),
        color = tintBg
    ) {
        Text(
            text = "$label · $remainingText",
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
        )
    }
}
```

Wait — that's defining a composable function inside another composable, which is not idiomatic. Instead, inline the pill rendering directly. After the IOB pill block:

```kotlin
pauseHighExpiryMs?.let { expiry ->
    if (expiry > System.currentTimeMillis()) {
        var remainingText by remember { mutableStateOf("") }
        LaunchedEffect(expiry) {
            while (true) {
                val remaining = expiry - System.currentTimeMillis()
                if (remaining <= 0) break
                val totalMin = (remaining / 60_000).toInt()
                val hours = totalMin / 60
                val min = totalMin % 60
                remainingText = if (hours > 0) "${hours}h ${min}m" else "${min}m"
                delay(10_000)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Surface(
            onClick = onPausePillClick,
            shape = RoundedCornerShape(100),
            color = if (isDark) TintWarning else LightTintWarning
        ) {
            Text(
                text = stringResource(R.string.pause_high_active, remainingText),
                color = AboveHigh,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }
}

pauseLowExpiryMs?.let { expiry ->
    if (expiry > System.currentTimeMillis()) {
        var remainingText by remember { mutableStateOf("") }
        LaunchedEffect(expiry) {
            while (true) {
                val remaining = expiry - System.currentTimeMillis()
                if (remaining <= 0) break
                val totalMin = (remaining / 60_000).toInt()
                val hours = totalMin / 60
                val min = totalMin % 60
                remainingText = if (hours > 0) "${hours}h ${min}m" else "${min}m"
                delay(10_000)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Surface(
            onClick = onPausePillClick,
            shape = RoundedCornerShape(100),
            color = if (isDark) TintDanger else LightTintDanger
        ) {
            Text(
                text = stringResource(R.string.pause_low_active, remainingText),
                color = BelowLow,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }
}
```

Update the `BgHeader` call in `MainScreen` to pass the new params:

```kotlin
BgHeader(
    latestReading, bgLow, bgHigh, glucoseUnit, crossing, followerStatus, iob, treatments, iobTauMinutes,
    pauseLowExpiryMs = pauseLowExpiryMs,
    pauseHighExpiryMs = pauseHighExpiryMs,
    onPausePillClick = { showPauseSheet = true }
)
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt
git commit -m "feat: add pause icon, bottom sheet, and active pause pills to MainScreen"
```

---

### Task 6: Wire pause into MainActivity

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Collect pause state and pass to MainScreen**

In `MainActivity`, inside the `StrimmaTheme` block where other state is collected (around lines 130-151), add:

```kotlin
val pauseLowExpiryMs by viewModel.pauseLowExpiryMs.collectAsState()
val pauseHighExpiryMs by viewModel.pauseHighExpiryMs.collectAsState()
```

In the `MainScreen(...)` call (around line 213), add the new params:

```kotlin
pauseLowExpiryMs = pauseLowExpiryMs,
pauseHighExpiryMs = pauseHighExpiryMs,
onPauseAlerts = viewModel::pauseAlerts,
onCancelPause = viewModel::cancelAlertPause,
```

- [ ] **Step 2: Pass pause state to AlertsSettings**

In the `composable("settings/alerts")` block, add pause params to the `AlertsSettings` call. This requires modifying `AlertsSettings` signature first — we'll do that in the next task. For now, just add the state collection. The `AlertsSettings` composable will accept the new params in Task 7.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "feat: wire pause state from ViewModel to MainScreen"
```

---

### Task 7: Add pause section to AlertsSettings

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/settings/AlertsSettings.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Add pause params to AlertsSettings**

Add parameters to `AlertsSettings`:

```kotlin
pauseLowExpiryMs: Long? = null,
pauseHighExpiryMs: Long? = null,
onPauseAlerts: (AlertCategory, Long) -> Unit = { _, _ -> },
onCancelPause: (AlertCategory) -> Unit = {}
```

Add imports:

```kotlin
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.ui.components.PauseAlertsSheet
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
```

- [ ] **Step 2: Add pause card at top of settings**

Inside the `SettingsScaffold` content, before the existing `SettingsSection`, add:

```kotlin
var showPauseSheet by remember { mutableStateOf(false) }

// Active pauses or pause button
val hasActivePause = (pauseLowExpiryMs != null && pauseLowExpiryMs > System.currentTimeMillis()) ||
    (pauseHighExpiryMs != null && pauseHighExpiryMs > System.currentTimeMillis())

if (hasActivePause) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.pause_alerts),
                style = MaterialTheme.typography.labelSmall,
                color = outline,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            pauseHighExpiryMs?.let { expiry ->
                if (expiry > System.currentTimeMillis()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("High alerts paused", color = AboveHigh, fontSize = 14.sp)
                        TextButton(onClick = { onCancelPause(AlertCategory.HIGH) }) {
                            Text(stringResource(R.string.pause_cancel))
                        }
                    }
                }
            }
            pauseLowExpiryMs?.let { expiry ->
                if (expiry > System.currentTimeMillis()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Low alerts paused", color = BelowLow, fontSize = 14.sp)
                        TextButton(onClick = { onCancelPause(AlertCategory.LOW) }) {
                            Text(stringResource(R.string.pause_cancel))
                        }
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
} else {
    OutlinedButton(
        onClick = { showPauseSheet = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.pause_alerts))
    }
    Spacer(modifier = Modifier.height(12.dp))
}

if (showPauseSheet) {
    PauseAlertsSheet(
        pauseLowExpiryMs = pauseLowExpiryMs,
        pauseHighExpiryMs = pauseHighExpiryMs,
        onPause = onPauseAlerts,
        onCancel = onCancelPause,
        onDismiss = { showPauseSheet = false }
    )
}
```

- [ ] **Step 3: Pass pause params in MainActivity**

In `composable("settings/alerts")` in `MainActivity.kt`, add the new params to the `AlertsSettings` call:

```kotlin
pauseLowExpiryMs = pauseLowExpiryMs,
pauseHighExpiryMs = pauseHighExpiryMs,
onPauseAlerts = viewModel::pauseAlerts,
onCancelPause = viewModel::cancelAlertPause,
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run all tests**

Run: `./gradlew testDebugUnitTest --info`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/AlertsSettings.kt \
       app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "feat: add pause section to AlertsSettings"
```

---

### Task 8: Final verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest --info`
Expected: All tests pass.

- [ ] **Step 2: Build debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Install and verify on device**

Run: `./gradlew installDebug`
Verify: bell-slash icon in top bar, tap opens sheet, duration chips work, pills show countdown, settings show active pauses.
