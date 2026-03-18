# Nightscout Follower Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a NIGHTSCOUT_FOLLOWER glucose source that polls a remote Nightscout server, stores readings locally, and displays them through the existing Strimma pipeline.

**Architecture:** NightscoutFollower is a Hilt-injected singleton that owns a coroutine-based polling loop. It fetches entries via NightscoutClient.fetchEntries(), computes direction/delta locally via DirectionComputer, inserts into Room, and notifies StrimmaService via a callback. Backfill (7 days) runs on first connect, then incremental polling at a configurable interval.

**Tech Stack:** Kotlin, Ktor Client, Room, Hilt, Coroutines/Flow, Jetpack Compose, kotlinx.serialization

**Spec:** `docs/specs/2026-03-18-nightscout-follower-mode.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `data/GlucoseSource.kt` | Modify | Add `NIGHTSCOUT_FOLLOWER` enum value |
| `data/SettingsRepository.kt` | Modify | Add follower URL, secret, poll interval settings |
| `network/NightscoutClient.kt` | Modify | Add `fetchEntries()` and `NightscoutEntryResponse` |
| `network/NightscoutFollower.kt` | Create | Polling loop, backfill, direction computation, status |
| `service/StrimmaService.kt` | Modify | Source switching for follower, `onNewReading` callback |
| `ui/MainViewModel.kt` | Modify | Expose follower status |
| `ui/MainScreen.kt` | Modify | Show follower connection status in BgHeader |
| `ui/SettingsScreen.kt` | Modify | Add Following section, hide/show Nightscout section |
| `test/.../network/NightscoutClientFetchTest.kt` | Create | Tests for fetchEntries() |
| `test/.../network/NightscoutFollowerTest.kt` | Create | Tests for polling, backfill, filtering |

All source paths relative to `app/src/main/java/com/psjostrom/strimma/`.
All test paths relative to `app/src/test/java/com/psjostrom/strimma/`.

---

### Task 1: Add NIGHTSCOUT_FOLLOWER to GlucoseSource enum

**Files:**
- Modify: `data/GlucoseSource.kt`

- [ ] **Step 1: Add the new enum value**

```kotlin
enum class GlucoseSource(val label: String, val description: String) {
    COMPANION("Companion Mode", "Parse notifications from CGM apps"),
    XDRIP_BROADCAST("xDrip Broadcast", "Receive xDrip-compatible BG broadcasts"),
    NIGHTSCOUT_FOLLOWER("Nightscout Follower", "Follow a remote Nightscout server")
}
```

- [ ] **Step 2: Build to verify no compile errors**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/GlucoseSource.kt
git commit -m "Add NIGHTSCOUT_FOLLOWER to GlucoseSource enum"
```

---

### Task 2: Add follower settings to SettingsRepository

**Files:**
- Modify: `data/SettingsRepository.kt`

- [ ] **Step 1: Add DataStore keys in companion object**

Add after `KEY_GLUCOSE_SOURCE_SYNC`:

```kotlin
private val KEY_FOLLOWER_URL = stringPreferencesKey("follower_url")
private val KEY_FOLLOWER_POLL_SECONDS = intPreferencesKey("follower_poll_seconds")
private const val KEY_FOLLOWER_SECRET = "follower_secret"
```

- [ ] **Step 2: Add Flow properties and setters for follower URL and poll interval**

Add after the `glucoseSource` / `setGlucoseSource` / `getGlucoseSourceSync` block:

```kotlin
val followerUrl: Flow<String> = dataStore.data.map { it[KEY_FOLLOWER_URL] ?: "" }
suspend fun setFollowerUrl(url: String) { dataStore.edit { it[KEY_FOLLOWER_URL] = url } }

val followerPollSeconds: Flow<Int> = dataStore.data.map { it[KEY_FOLLOWER_POLL_SECONDS] ?: 60 }
suspend fun setFollowerPollSeconds(seconds: Int) { dataStore.edit { it[KEY_FOLLOWER_POLL_SECONDS] = seconds } }

fun getFollowerSecret(): String = encryptedPrefs.getString(KEY_FOLLOWER_SECRET, "") ?: ""
fun setFollowerSecret(secret: String) {
    encryptedPrefs.edit().putString(KEY_FOLLOWER_SECRET, secret).apply()
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt
git commit -m "Add follower URL, secret, and poll interval settings"
```

---

### Task 3: Add fetchEntries() to NightscoutClient

**Files:**
- Modify: `network/NightscoutClient.kt`
- Create: `test/.../network/NightscoutClientFetchTest.kt`

- [ ] **Step 1: Write tests for fetchEntries**

Create `app/src/test/java/com/psjostrom/strimma/network/NightscoutClientFetchTest.kt`:

```kotlin
package com.psjostrom.strimma.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NightscoutClientFetchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse valid sgv entry`() {
        val raw = """[{"sgv": 120, "date": 1710700000000, "type": "sgv"}]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(1, entries.size)
        assertEquals(120, entries[0].sgv)
        assertEquals(1710700000000L, entries[0].date)
        assertEquals("sgv", entries[0].type)
    }

    @Test
    fun `parse entry with unknown fields`() {
        val raw = """[{"sgv": 100, "date": 1710700000000, "type": "sgv", "direction": "Flat", "noise": 1, "rssi": -50}]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(1, entries.size)
        assertEquals(100, entries[0].sgv)
    }

    @Test
    fun `parse entry with null sgv`() {
        val raw = """[{"date": 1710700000000, "type": "cal"}]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(1, entries.size)
        assertNull(entries[0].sgv)
    }

    @Test
    fun `parse empty array`() {
        val raw = """[]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parse mixed entry types`() {
        val raw = """[
            {"sgv": 120, "date": 1710700000000, "type": "sgv"},
            {"date": 1710700100000, "type": "mbg"},
            {"sgv": 130, "date": 1710700200000, "type": "sgv"}
        ]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(3, entries.size)
        assertEquals("sgv", entries[0].type)
        assertEquals("mbg", entries[1].type)
        assertEquals("sgv", entries[2].type)
    }

    @Test
    fun `buildFetchUrl constructs correct query string`() {
        val url = NightscoutClient.buildFetchUrl("https://ns.example.com", since = 1710700000000L, count = 100)
        assertEquals("https://ns.example.com/api/v1/entries.json?find[date][\$gt]=1710700000000&count=100", url)
    }

    @Test
    fun `buildFetchUrl trims trailing slash`() {
        val url = NightscoutClient.buildFetchUrl("https://ns.example.com/", since = 1710700000000L, count = 50)
        assertEquals("https://ns.example.com/api/v1/entries.json?find[date][\$gt]=1710700000000&count=50", url)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.network.NightscoutClientFetchTest" 2>&1 | tail -10`
Expected: FAIL — `NightscoutEntryResponse` and `buildFetchUrl` don't exist yet.

- [ ] **Step 3: Add NightscoutEntryResponse and fetchEntries to NightscoutClient**

Add the response model after existing `NightscoutEntry`:

```kotlin
@Serializable
data class NightscoutEntryResponse(
    val sgv: Int? = null,
    val date: Long? = null,
    val type: String? = null
)
```

Add `buildFetchUrl` as a companion function and `fetchEntries` method to `NightscoutClient`:

```kotlin
companion object {
    fun buildFetchUrl(baseUrl: String, since: Long, count: Int): String {
        return "${baseUrl.trimEnd('/')}/api/v1/entries.json?find[date][\$gt]=$since&count=$count"
    }
}

suspend fun fetchEntries(
    baseUrl: String,
    apiSecret: String,
    since: Long,
    count: Int = 2016
): List<NightscoutEntryResponse>? {
    if (baseUrl.isBlank() || apiSecret.isBlank()) return emptyList()

    val hashedSecret = hashSecret(apiSecret)
    val fullUrl = buildFetchUrl(baseUrl, since, count)

    return try {
        val response = client.get(fullUrl) {
            header("api-secret", hashedSecret)
        }
        if (!response.status.isSuccess()) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                message = "Fetch HTTP ${response.status.value}: $fullUrl"
            )
            null
        } else {
            response.body<List<NightscoutEntryResponse>>()
        }
    } catch (e: Exception) {
        com.psjostrom.strimma.receiver.DebugLog.log(
            message = "Fetch error: ${e.message?.take(80)}"
        )
        null
    }
}
```

Add the required imports at the top of the file:

```kotlin
import io.ktor.client.call.*
import io.ktor.client.request.*
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.network.NightscoutClientFetchTest" 2>&1 | tail -10`
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/NightscoutClient.kt \
       app/src/test/java/com/psjostrom/strimma/network/NightscoutClientFetchTest.kt
git commit -m "Add fetchEntries() to NightscoutClient with response parsing"
```

---

### Task 4: Create NightscoutFollower with FollowerStatus

**Files:**
- Create: `network/NightscoutFollower.kt`
- Create: `test/.../network/NightscoutFollowerTest.kt`

- [ ] **Step 1: Write tests for NightscoutFollower entry processing**

Create `app/src/test/java/com/psjostrom/strimma/network/NightscoutFollowerTest.kt`:

```kotlin
package com.psjostrom.strimma.network

import org.junit.Assert.*
import org.junit.Test

class NightscoutFollowerTest {

    @Test
    fun `filterValidEntries skips non-sgv entries`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 120, date = 1000L, type = "sgv"),
            NightscoutEntryResponse(sgv = null, date = 2000L, type = "mbg"),
            NightscoutEntryResponse(sgv = 130, date = 3000L, type = "cal"),
            NightscoutEntryResponse(sgv = 140, date = 4000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(2, valid.size)
        assertEquals(120, valid[0].sgv)
        assertEquals(140, valid[1].sgv)
    }

    @Test
    fun `filterValidEntries skips entries with null sgv`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = null, date = 1000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 120, date = 2000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(1, valid.size)
        assertEquals(120, valid[0].sgv)
    }

    @Test
    fun `filterValidEntries skips entries with null date`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 120, date = null, type = "sgv"),
            NightscoutEntryResponse(sgv = 130, date = 2000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(1, valid.size)
        assertEquals(130, valid[0].sgv)
    }

    @Test
    fun `filterValidEntries skips out-of-range mmol values`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 10, date = 1000L, type = "sgv"),    // 0.56 mmol - too low
            NightscoutEntryResponse(sgv = 120, date = 2000L, type = "sgv"),   // 6.66 mmol - valid
            NightscoutEntryResponse(sgv = 1000, date = 3000L, type = "sgv")   // 55.5 mmol - too high
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(1, valid.size)
        assertEquals(120, valid[0].sgv)
    }

    @Test
    fun `filterValidEntries returns sorted by date ascending`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 130, date = 3000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 120, date = 1000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 125, date = 2000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(3, valid.size)
        assertEquals(1000L, valid[0].date)
        assertEquals(2000L, valid[1].date)
        assertEquals(3000L, valid[2].date)
    }

    @Test
    fun `filterValidEntries handles empty list`() {
        val valid = NightscoutFollower.filterValidEntries(emptyList())
        assertTrue(valid.isEmpty())
    }

    @Test
    fun `sgvToMmol converts correctly`() {
        assertEquals(5.55, NightscoutFollower.sgvToMmol(100), 0.01)
        assertEquals(11.1, NightscoutFollower.sgvToMmol(200), 0.1)
    }

    @Test
    fun `sgvToMmol rounds to 1 decimal place`() {
        val mmol = NightscoutFollower.sgvToMmol(123) // 6.826... → 6.8
        assertEquals(6.8, mmol, 0.01)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.network.NightscoutFollowerTest" 2>&1 | tail -10`
Expected: FAIL — `NightscoutFollower` doesn't exist.

- [ ] **Step 3: Create NightscoutFollower**

Create `app/src/main/java/com/psjostrom/strimma/network/NightscoutFollower.kt`:

```kotlin
package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed class FollowerStatus {
    object Idle : FollowerStatus()
    object Connecting : FollowerStatus()
    data class Connected(val lastPollTs: Long) : FollowerStatus()
    data class Disconnected(val since: Long) : FollowerStatus()
}

@Singleton
class NightscoutFollower @Inject constructor(
    private val client: NightscoutClient,
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val settings: SettingsRepository
) {
    private val _status = MutableStateFlow<FollowerStatus>(FollowerStatus.Idle)
    val status: StateFlow<FollowerStatus> = _status

    companion object {
        private const val MGDL_CONVERSION = 18.0182
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val FETCH_COUNT = 2016

        fun filterValidEntries(entries: List<NightscoutEntryResponse>): List<NightscoutEntryResponse> {
            return entries
                .filter { it.type == "sgv" && it.sgv != null && it.date != null }
                .filter {
                    val mmol = it.sgv!! / MGDL_CONVERSION
                    mmol >= 1.0 && mmol <= 50.0
                }
                .sortedBy { it.date }
        }

        fun sgvToMmol(sgv: Int): Double {
            val raw = sgv / MGDL_CONVERSION
            return Math.round(raw * 10.0) / 10.0
        }
    }

    fun start(scope: CoroutineScope, onNewReading: suspend (GlucoseReading) -> Unit): Job {
        return scope.launch {
            _status.value = FollowerStatus.Connecting

            val url = settings.followerUrl.first()
            val secret = settings.getFollowerSecret()
            if (url.isBlank() || secret.isBlank()) {
                DebugLog.log(message = "Follower: URL or secret empty")
                _status.value = FollowerStatus.Idle
                return@launch
            }

            // Backfill phase
            backfill(url, secret, onNewReading)

            // Incremental polling phase
            while (isActive) {
                val pollSeconds = settings.followerPollSeconds.first()
                delay(pollSeconds * 1000L)

                val latestTs = dao.latestOnce()?.ts ?: (System.currentTimeMillis() - SEVEN_DAYS_MS)
                val entries = client.fetchEntries(url, secret, since = latestTs)

                if (entries == null) {
                    val now = System.currentTimeMillis()
                    if (_status.value !is FollowerStatus.Disconnected) {
                        _status.value = FollowerStatus.Disconnected(since = now)
                    }
                    DebugLog.log(message = "Follower: poll failed")
                    continue
                }

                val valid = filterValidEntries(entries)
                if (valid.isEmpty()) {
                    _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
                    continue
                }

                for (entry in valid) {
                    val reading = processEntry(entry)
                    if (reading != null) {
                        onNewReading(reading)
                    }
                }

                _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
                DebugLog.log(message = "Follower: ${valid.size} new readings")
            }
        }
    }

    fun stop() {
        _status.value = FollowerStatus.Idle
    }

    private suspend fun backfill(url: String, secret: String, onNewReading: suspend (GlucoseReading) -> Unit) {
        val latestTs = dao.latestOnce()?.ts ?: 0L
        val sevenDaysAgo = System.currentTimeMillis() - SEVEN_DAYS_MS

        if (latestTs >= sevenDaysAgo) {
            _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
            DebugLog.log(message = "Follower: skip backfill, data is recent")
            return
        }

        DebugLog.log(message = "Follower: starting backfill")
        var since = if (latestTs > 0) latestTs else sevenDaysAgo
        var totalInserted = 0
        var lastReading: GlucoseReading? = null

        // Paginated fetch
        while (true) {
            val entries = client.fetchEntries(url, secret, since = since, count = FETCH_COUNT)
            if (entries == null) {
                _status.value = FollowerStatus.Disconnected(since = System.currentTimeMillis())
                DebugLog.log(message = "Follower: backfill fetch failed")
                return
            }

            val valid = filterValidEntries(entries)
            if (valid.isEmpty()) break

            for (entry in valid) {
                val reading = processEntry(entry)
                if (reading != null) {
                    lastReading = reading
                    totalInserted++
                }
            }

            // If we got fewer than requested, we have all entries
            if (entries.size < FETCH_COUNT) break

            // Otherwise paginate from the latest entry we received
            since = valid.last().date!!
        }

        // Trigger UI update only for the most recent reading
        if (lastReading != null) {
            onNewReading(lastReading)
        }

        _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
        DebugLog.log(message = "Follower: backfill complete, $totalInserted readings")
    }

    private suspend fun processEntry(entry: NightscoutEntryResponse): GlucoseReading? {
        val sgv = entry.sgv ?: return null
        val ts = entry.date ?: return null
        val mmol = sgvToMmol(sgv)

        // Check for duplicate
        val existing = dao.lastN(1)
        if (existing.isNotEmpty() && (ts - existing[0].ts) < 3_000) return null

        // Compute direction and delta
        val recentReadings = dao.since(ts - 15 * 60 * 1000)
        val tempReading = GlucoseReading(
            ts = ts, sgv = sgv, mmol = mmol,
            direction = "NONE", deltaMmol = null, pushed = 1
        )
        val (direction, deltaMmol) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            deltaMmol = deltaMmol?.let { Math.round(it * 10.0) / 10.0 }
        )

        dao.insert(reading)
        return reading
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.network.NightscoutFollowerTest" 2>&1 | tail -10`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/NightscoutFollower.kt \
       app/src/test/java/com/psjostrom/strimma/network/NightscoutFollowerTest.kt
git commit -m "Add NightscoutFollower with polling, backfill, and entry filtering"
```

---

### Task 5: Wire NightscoutFollower into StrimmaService

**Files:**
- Modify: `service/StrimmaService.kt`

- [ ] **Step 1: Add NightscoutFollower injection**

Add to the injected fields:

```kotlin
@Inject lateinit var nightscoutFollower: NightscoutFollower
```

Add a field for the follower job:

```kotlin
private var followerJob: Job? = null
```

- [ ] **Step 2: Add follower start/stop methods**

Add after `unregisterXdripReceiver()`:

```kotlin
private fun startFollower() {
    if (followerJob != null) return
    followerJob = nightscoutFollower.start(scope) { reading ->
        updateNotification()
        alertManager.checkReading(reading)
        broadcastBgIfEnabled(reading)
        try {
            val mgr = GlanceAppWidgetManager(this@StrimmaService)
            mgr.getGlanceIds(StrimmaWidget::class.java).forEach { id ->
                StrimmaWidget().update(this@StrimmaService, id)
            }
        } catch (_: Exception) {}
    }
    DebugLog.log("Nightscout follower started")
}

private fun stopFollower() {
    followerJob?.cancel()
    followerJob = null
    nightscoutFollower.stop()
    DebugLog.log("Nightscout follower stopped")
}
```

- [ ] **Step 3: Extend source switching in onCreate**

Replace the existing `glucoseSource.collect` block:

```kotlin
scope.launch {
    settings.glucoseSource.collect { source ->
        // Stop all sources first
        unregisterXdripReceiver()
        stopFollower()

        // Start the selected source
        when (source) {
            GlucoseSource.COMPANION -> {
                // Notification listener is always active via manifest
            }
            GlucoseSource.XDRIP_BROADCAST -> {
                registerXdripReceiver()
            }
            GlucoseSource.NIGHTSCOUT_FOLLOWER -> {
                startFollower()
            }
        }
    }
}
```

Remove the now-redundant `registerXdripReceiverIfNeeded()` call in `onCreate` (the collect block handles initial state).

- [ ] **Step 4: Add cleanup in onDestroy**

In `onDestroy()`, add before `scope.cancel()`:

```kotlin
stopFollower()
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt
git commit -m "Wire NightscoutFollower into StrimmaService source switching"
```

---

### Task 6: Expose follower status in MainViewModel

**Files:**
- Modify: `ui/MainViewModel.kt`

- [ ] **Step 1: Add NightscoutFollower to constructor injection**

Add to constructor:

```kotlin
private val nightscoutFollower: NightscoutFollower
```

(Hilt will inject it since it's a `@Singleton`.)

Also add the import:

```kotlin
import com.psjostrom.strimma.network.FollowerStatus
import com.psjostrom.strimma.network.NightscoutFollower
```

- [ ] **Step 2: Expose follower status as StateFlow**

Add after the `glucoseSource` property:

```kotlin
val followerStatus: StateFlow<FollowerStatus> = nightscoutFollower.status
```

- [ ] **Step 3: Add follower settings state and setters**

Add after the follower status:

```kotlin
val followerUrl: StateFlow<String> = settings.followerUrl
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
fun setFollowerUrl(url: String) = viewModelScope.launch { settings.setFollowerUrl(url) }

val followerSecret: String get() = settings.getFollowerSecret()
fun setFollowerSecret(secret: String) = settings.setFollowerSecret(secret)

val followerPollSeconds: StateFlow<Int> = settings.followerPollSeconds
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
fun setFollowerPollSeconds(seconds: Int) = viewModelScope.launch { settings.setFollowerPollSeconds(seconds) }
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt
git commit -m "Expose follower status and settings in MainViewModel"
```

---

### Task 7: Show follower status in MainScreen

**Files:**
- Modify: `ui/MainScreen.kt`

- [ ] **Step 1: Add follower status parameter to MainScreen**

Add parameters to `MainScreen`:

```kotlin
followerStatus: FollowerStatus = FollowerStatus.Idle,
```

Add the import:

```kotlin
import com.psjostrom.strimma.network.FollowerStatus
```

- [ ] **Step 2: Pass followerStatus to BgHeader**

Update the `BgHeader` call at line 98 to include follower status:

```kotlin
BgHeader(latestReading, bgLow, bgHigh, glucoseUnit, followerStatus)
```

- [ ] **Step 3: Add followerStatus parameter to BgHeader and display it**

Add `followerStatus: FollowerStatus` parameter to `BgHeader`.

After the staleness text (line ~222, the `"$minutesAgo min ago"` Text), add:

```kotlin
if (followerStatus !is FollowerStatus.Idle) {
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = when (followerStatus) {
            is FollowerStatus.Connecting -> "Following \u00b7 connecting\u2026"
            is FollowerStatus.Connected -> {
                val secsAgo = ((System.currentTimeMillis() - followerStatus.lastPollTs) / 1000).toInt()
                if (secsAgo < 60) "Following \u00b7 ${secsAgo}s ago"
                else "Following \u00b7 ${secsAgo / 60}m ago"
            }
            is FollowerStatus.Disconnected -> {
                val minsAgo = ((System.currentTimeMillis() - followerStatus.since) / 60_000).toInt()
                "Following \u00b7 connection lost ${if (minsAgo > 0) "${minsAgo}m" else ""}"
            }
            else -> ""
        },
        color = when (followerStatus) {
            is FollowerStatus.Disconnected -> BelowLow
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        fontSize = 12.sp
    )
}
```

- [ ] **Step 4: Update the MainScreen call site**

Find where `MainScreen` is called (likely in `MainActivity.kt` or a navigation composable) and pass the `followerStatus` from the ViewModel:

```kotlin
val followerStatus by viewModel.followerStatus.collectAsStateWithLifecycle()
// ... pass to MainScreen:
followerStatus = followerStatus,
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt \
       app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "Show follower connection status in MainScreen BgHeader"
```

---

### Task 8: Add Following section to SettingsScreen

**Files:**
- Modify: `ui/SettingsScreen.kt`

- [ ] **Step 1: Add follower settings parameters**

Add parameters to `SettingsScreen`:

```kotlin
followerUrl: String,
followerSecret: String,
followerPollSeconds: Int,
onFollowerUrlChange: (String) -> Unit,
onFollowerSecretChange: (String) -> Unit,
onFollowerPollSecondsChange: (Int) -> Unit,
```

- [ ] **Step 2: Conditionally show Nightscout vs Following section**

After the "Data Source" section, replace the unconditional "Nightscout" section with conditional rendering:

```kotlin
if (glucoseSource != GlucoseSource.NIGHTSCOUT_FOLLOWER) {
    SettingsSection("Nightscout", outline, surfVar) {
        // ... existing Nightscout URL + secret fields (unchanged)
    }
} else {
    SettingsSection("Following", outline, surfVar) {
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
            "How often to check for new readings. Lower values catch updates faster but use more battery. CGM readings typically arrive every 5 minutes.",
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
```

- [ ] **Step 3: Update the SettingsScreen call site**

Pass the new parameters from wherever `SettingsScreen` is called, using ViewModel values:

```kotlin
followerUrl = followerUrl,
followerSecret = viewModel.followerSecret,
followerPollSeconds = followerPollSeconds,
onFollowerUrlChange = viewModel::setFollowerUrl,
onFollowerSecretChange = viewModel::setFollowerSecret,
onFollowerPollSecondsChange = viewModel::setFollowerPollSeconds,
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/SettingsScreen.kt \
       app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "Add Following settings section, hide Nightscout push when in follower mode"
```

---

### Task 9: Run full test suite and verify build

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All tests PASS (existing 77 + new ~15 = ~92 tests)

- [ ] **Step 2: Full build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify test count**

Run: `./gradlew testDebugUnitTest 2>&1 | grep -E "tests? (completed|passed|failed)"`
Expected: 0 failures

---

### Task 10: Final commit with all changes

**Files:** None (git only)

- [ ] **Step 1: Review all changes**

Run: `git log --oneline main..HEAD`
Verify the commit history is clean and tells a coherent story.

- [ ] **Step 2: Verify no uncommitted changes**

Run: `git status`
Expected: clean working tree
