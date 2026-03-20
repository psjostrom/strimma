# Local Web Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Nightscout-compatible local HTTP server to Strimma so Garmin watchfaces and other apps can fetch BG data directly from the phone.

**Architecture:** Ktor Server CIO embedded in StrimmaService, managed via settings flow. Routes in a `webserver/` package with per-endpoint handler files. Auth via Ktor plugin checking source address + SHA-1 api-secret header.

**Tech Stack:** Kotlin, Ktor Server 3.0.3 (CIO engine), Hilt, Room, kotlinx.serialization

**Spec:** `docs/specs/2026-03-20-local-web-server-design.md`

---

## File Structure

```
New files:
  app/src/main/java/com/psjostrom/strimma/webserver/LocalWebServer.kt
  app/src/main/java/com/psjostrom/strimma/webserver/AuthPlugin.kt
  app/src/main/java/com/psjostrom/strimma/webserver/SgvRoute.kt
  app/src/main/java/com/psjostrom/strimma/webserver/StatusRoute.kt
  app/src/main/java/com/psjostrom/strimma/webserver/TreatmentsRoute.kt (buildTreatmentsJson)
  app/src/test/java/com/psjostrom/strimma/webserver/AuthPluginTest.kt
  app/src/test/java/com/psjostrom/strimma/webserver/SgvRouteTest.kt
  app/src/test/java/com/psjostrom/strimma/webserver/StatusRouteTest.kt
  app/src/test/java/com/psjostrom/strimma/webserver/TreatmentsRouteTest.kt

Modified files:
  gradle/libs.versions.toml                         — add ktor-server dependencies
  app/build.gradle.kts                               — add ktor-server implementations
  app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt — add webServerEnabled, webServerSecret
  app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt  — start/stop server
  app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt        — expose web server settings
  app/src/main/java/com/psjostrom/strimma/ui/settings/DataSettings.kt — add web server UI
  app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt         — wire web server settings
```

---

### Task 1: Add Ktor Server dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add server libraries to version catalog**

In `gradle/libs.versions.toml`, add to `[libraries]` section (ktor version is already `3.0.3`):

```toml
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
```

Also add a test dependency:

```toml
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
```

- [ ] **Step 2: Add to build.gradle.kts**

After the existing ktor-client lines (~line 123-126), add:

```kotlin
implementation(libs.ktor.server.core)
implementation(libs.ktor.server.cio)
implementation(libs.ktor.server.content.negotiation)
```

In the test dependencies section, add:

```kotlin
testImplementation(libs.ktor.server.test.host)
```

- [ ] **Step 3: Sync and verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
feat: add Ktor Server dependencies
```

---

### Task 2: Add web server settings

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt`

- [ ] **Step 1: Add settings keys and flows**

Add to the `companion object` keys:

```kotlin
private val KEY_WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
private const val KEY_WEB_SERVER_SECRET = "web_server_secret"
```

Add flows and accessors after the existing `customDIA` block:

```kotlin
val webServerEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_WEB_SERVER_ENABLED] ?: false }
suspend fun setWebServerEnabled(enabled: Boolean) { dataStore.edit { it[KEY_WEB_SERVER_ENABLED] = enabled } }

fun getWebServerSecret(): String = encryptedPrefs.getString(KEY_WEB_SERVER_SECRET, "") ?: ""
fun setWebServerSecret(secret: String) {
    encryptedPrefs.edit().putString(KEY_WEB_SERVER_SECRET, secret).apply()
}
```

- [ ] **Step 2: Add to export/import**

In `exportToJson()`, add to the `settings` JSONObject:

```kotlin
put("web_server_enabled", prefs[KEY_WEB_SERVER_ENABLED] ?: false)
```

In `exportToJson()`, add to the `secrets` JSONObject:

```kotlin
put("web_server_secret", getWebServerSecret())
```

In `importFromJson()`, add to the `dataStore.edit` block:

```kotlin
if (settings.has("web_server_enabled")) prefs[KEY_WEB_SERVER_ENABLED] = settings.getBoolean("web_server_enabled")
```

In `importFromJson()`, add to the secrets handling:

```kotlin
if (secrets.has("web_server_secret")) setWebServerSecret(secrets.getString("web_server_secret"))
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
feat: add web server settings to SettingsRepository
```

---

### Task 3: Auth plugin

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/webserver/AuthPlugin.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/webserver/AuthPluginTest.kt`

- [ ] **Step 1: Write auth tests**

```kotlin
package com.psjostrom.strimma.webserver

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class AuthPluginTest {

    private fun hashSecret(secret: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `loopback IPv4 skips auth`() {
        assertTrue(isLoopback("127.0.0.1"))
    }

    @Test
    fun `loopback IPv6 skips auth`() {
        assertTrue(isLoopback("::1"))
        assertTrue(isLoopback("0:0:0:0:0:0:0:1"))
    }

    @Test
    fun `non-loopback requires auth`() {
        assertFalse(isLoopback("192.168.1.10"))
        assertFalse(isLoopback("10.0.0.1"))
    }

    @Test
    fun `valid secret matches`() {
        val secret = "my-test-secret"
        val hashed = hashSecret(secret)
        assertTrue(checkApiSecret(hashed, secret))
    }

    @Test
    fun `wrong secret rejected`() {
        val hashed = hashSecret("wrong-secret")
        assertFalse(checkApiSecret(hashed, "my-secret"))
    }

}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.webserver.AuthPluginTest"`
Expected: FAIL — functions not found

- [ ] **Step 3: Implement auth functions**

```kotlin
package com.psjostrom.strimma.webserver

import java.net.InetAddress
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)

fun isLoopback(remoteHost: String): Boolean {
    return try {
        InetAddress.getByName(remoteHost).isLoopbackAddress
    } catch (_: Exception) {
        false
    }
}

fun checkApiSecret(headerValue: String, serverSecret: String): Boolean {
    val expected = MessageDigest.getInstance("SHA-1")
        .digest(serverSecret.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return headerValue == expected
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.webserver.AuthPluginTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```
feat: add web server auth functions with tests
```

---

### Task 4: SGV route (JSON builder)

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/webserver/SgvRoute.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/webserver/SgvRouteTest.kt`

- [ ] **Step 1: Write SGV JSON builder tests**

Test the pure function that converts `GlucoseReading` list to Nightscout-compatible JSON. Do not test Ktor routing here — just the data transformation.

```kotlin
package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.GlucoseReading
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SgvRouteTest {

    private fun reading(ts: Long, sgv: Int, mmol: Double, direction: String, deltaMmol: Double?) =
        GlucoseReading(ts = ts, sgv = sgv, mmol = mmol, direction = direction, deltaMmol = deltaMmol, pushed = 1)

    @Test
    fun `builds JSON array with required fields`() {
        val readings = listOf(
            reading(1000L, 168, 9.3, "FortyFiveUp", 0.1)
        )
        val json = buildSgvJson(readings, briefMode = false, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        val arr = JSONArray(json)
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals(1000L, obj.getLong("date"))
        assertEquals(168, obj.getInt("sgv"))
        assertEquals("FortyFiveUp", obj.getString("direction"))
        assertTrue(obj.has("delta"))
        // Non-brief includes _id, device, type
        assertEquals("sgv", obj.getString("type"))
        assertEquals("Strimma", obj.getString("device"))
    }

    @Test
    fun `brief mode omits verbose fields`() {
        val readings = listOf(
            reading(1000L, 168, 9.3, "Flat", 0.0)
        )
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        val obj = JSONArray(json).getJSONObject(0)
        assertFalse(obj.has("_id"))
        assertFalse(obj.has("device"))
        assertFalse(obj.has("type"))
        assertFalse(obj.has("dateString"))
        assertFalse(obj.has("sysTime"))
        // Required fields still present
        assertTrue(obj.has("date"))
        assertTrue(obj.has("sgv"))
        assertTrue(obj.has("direction"))
    }

    @Test
    fun `first entry has units_hint`() {
        val readings = listOf(
            reading(2000L, 100, 5.5, "Flat", 0.0),
            reading(1000L, 90, 5.0, "Flat", -0.1)
        )
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mgdl", iob = null, stepsResult = null, heartResult = null)
        val arr = JSONArray(json)
        assertEquals("mgdl", arr.getJSONObject(0).getString("units_hint"))
        assertFalse(arr.getJSONObject(1).has("units_hint"))
    }

    @Test
    fun `first entry includes iob when provided`() {
        val readings = listOf(reading(1000L, 168, 9.3, "Flat", 0.0))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = 2.5, stepsResult = null, heartResult = null)
        val obj = JSONArray(json).getJSONObject(0)
        assertEquals(2.5, obj.getDouble("iob"), 0.01)
    }

    @Test
    fun `first entry includes steps and heart results when provided`() {
        val readings = listOf(reading(1000L, 168, 9.3, "Flat", 0.0))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = 200, heartResult = 200)
        val obj = JSONArray(json).getJSONObject(0)
        assertEquals(200, obj.getInt("steps_result"))
        assertEquals(200, obj.getInt("heart_result"))
    }

    @Test
    fun `delta converted to mgdl`() {
        val readings = listOf(reading(1000L, 168, 9.3, "FortyFiveUp", 0.5))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        val obj = JSONArray(json).getJSONObject(0)
        // 0.5 mmol * 18.0182 = ~9.0
        val delta = obj.getDouble("delta")
        assertEquals(9.0, delta, 0.2)
    }

    @Test
    fun `null delta produces zero`() {
        val readings = listOf(reading(1000L, 168, 9.3, "NONE", null))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        val obj = JSONArray(json).getJSONObject(0)
        assertEquals(0.0, obj.getDouble("delta"), 0.001)
    }

    @Test
    fun `empty readings produces empty array`() {
        val json = buildSgvJson(emptyList(), briefMode = false, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        assertEquals("[]", json)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.webserver.SgvRouteTest"`
Expected: FAIL

- [ ] **Step 3: Implement buildSgvJson**

```kotlin
package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun buildSgvJson(
    readings: List<GlucoseReading>,
    briefMode: Boolean,
    unitsHint: String,
    iob: Double?,
    stepsResult: Int?,
    heartResult: Int?
): String {
    val arr = JSONArray()
    for ((index, reading) in readings.withIndex()) {
        val obj = JSONObject()
        obj.put("date", reading.ts)
        obj.put("sgv", reading.sgv)
        obj.put("delta", (reading.deltaMmol ?: 0.0) * GlucoseUnit.MGDL_FACTOR)
        obj.put("direction", reading.direction)

        if (!briefMode) {
            obj.put("_id", reading.ts.toString())
            obj.put("device", "Strimma")
            obj.put("dateString", ISO_FORMATTER.format(Instant.ofEpochMilli(reading.ts)))
            obj.put("sysTime", ISO_FORMATTER.format(Instant.ofEpochMilli(reading.ts)))
            obj.put("type", "sgv")
        }

        if (index == 0) {
            obj.put("units_hint", unitsHint)
            iob?.let { obj.put("iob", it) }
            stepsResult?.let { obj.put("steps_result", it) }
            heartResult?.let { obj.put("heart_result", it) }
        }

        arr.put(obj)
    }
    return arr.toString()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.webserver.SgvRouteTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```
feat: add SGV JSON builder with tests
```

---

### Task 5: Status and Treatments JSON builders

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/webserver/StatusRoute.kt`
- Create: `app/src/main/java/com/psjostrom/strimma/webserver/TreatmentsRoute.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/webserver/StatusRouteTest.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/webserver/TreatmentsRouteTest.kt`

- [ ] **Step 1: Write status JSON test**

```kotlin
package com.psjostrom.strimma.webserver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StatusRouteTest {

    @Test
    fun `builds status JSON with mmol thresholds converted to mgdl`() {
        // bgLow=4.0 mmol -> 72 mg/dL, bgHigh=10.0 mmol -> 180 mg/dL
        val json = buildStatusJson(unitsHint = "mmol", bgLowMmol = 4.0f, bgHighMmol = 10.0f)
        val root = JSONObject(json)
        val settings = root.getJSONObject("settings")
        assertEquals("mmol", settings.getString("units"))
        val thresholds = settings.getJSONObject("thresholds")
        assertEquals(72, thresholds.getInt("bgLow"))
        assertEquals(180, thresholds.getInt("bgHigh"))
    }

    @Test
    fun `builds status JSON with mgdl units`() {
        val json = buildStatusJson(unitsHint = "mgdl", bgLowMmol = 3.9f, bgHighMmol = 10.0f)
        val root = JSONObject(json)
        assertEquals("mgdl", root.getJSONObject("settings").getString("units"))
    }
}
```

- [ ] **Step 2: Write treatments JSON test**

```kotlin
package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.Treatment
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TreatmentsRouteTest {

    private fun treatment(
        id: String, createdAt: Long, eventType: String,
        insulin: Double? = null, carbs: Double? = null, enteredBy: String? = null
    ) = Treatment(
        id = id, createdAt = createdAt, eventType = eventType,
        insulin = insulin, carbs = carbs, basalRate = null, duration = null,
        enteredBy = enteredBy, fetchedAt = System.currentTimeMillis()
    )

    @Test
    fun `builds treatments JSON array`() {
        val treatments = listOf(
            treatment("abc", 1715263007650L, "Meal Bolus", insulin = 2.5, carbs = 45.0, enteredBy = "CamAPS")
        )
        val json = buildTreatmentsJson(treatments)
        val arr = JSONArray(json)
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals("abc", obj.getString("_id"))
        assertEquals("Meal Bolus", obj.getString("eventType"))
        assertEquals(2.5, obj.getDouble("insulin"), 0.01)
        assertEquals(45.0, obj.getDouble("carbs"), 0.01)
        assertEquals("CamAPS", obj.getString("enteredBy"))
        // created_at should be ISO string
        assertTrue(obj.getString("created_at").contains("T"))
        assertTrue(obj.getString("created_at").endsWith("Z"))
    }

    @Test
    fun `empty treatments produces empty array`() {
        val json = buildTreatmentsJson(emptyList())
        assertEquals("[]", json)
    }

    @Test
    fun `null fields are omitted`() {
        val treatments = listOf(
            treatment("abc", 1715263007650L, "Correction Bolus", insulin = 1.0)
        )
        val json = buildTreatmentsJson(treatments)
        val obj = JSONArray(json).getJSONObject(0)
        assertFalse(obj.has("carbs"))
        assertFalse(obj.has("enteredBy"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.webserver.StatusRouteTest" --tests "com.psjostrom.strimma.webserver.TreatmentsRouteTest"`
Expected: FAIL

- [ ] **Step 4: Implement status JSON builder**

```kotlin
package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.GlucoseUnit
import org.json.JSONObject

fun buildStatusJson(unitsHint: String, bgLowMmol: Float, bgHighMmol: Float): String {
    return JSONObject().apply {
        put("settings", JSONObject().apply {
            put("units", unitsHint)
            put("thresholds", JSONObject().apply {
                put("bgLow", (bgLowMmol * GlucoseUnit.MGDL_FACTOR).toInt())
                put("bgHigh", (bgHighMmol * GlucoseUnit.MGDL_FACTOR).toInt())
            })
        })
    }.toString()
}
```

- [ ] **Step 5: Implement treatments JSON builder**

```kotlin
package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.Treatment
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun buildTreatmentsJson(treatments: List<Treatment>): String {
    val arr = JSONArray()
    for (treatment in treatments) {
        val obj = JSONObject()
        obj.put("_id", treatment.id)
        obj.put("created_at", ISO_FORMATTER.format(Instant.ofEpochMilli(treatment.createdAt)))
        obj.put("eventType", treatment.eventType)
        treatment.insulin?.let { obj.put("insulin", it) }
        treatment.carbs?.let { obj.put("carbs", it) }
        treatment.enteredBy?.let { obj.put("enteredBy", it) }
        arr.put(obj)
    }
    return arr.toString()
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.webserver.StatusRouteTest" --tests "com.psjostrom.strimma.webserver.TreatmentsRouteTest"`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```
feat: add Status and Treatments JSON builders with tests
```

---

### Task 6: LocalWebServer — Ktor server with routing

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/webserver/LocalWebServer.kt`

This is the Ktor server that wires everything together. Not unit-tested in isolation — integration testing happens via real HTTP in Task 8.

- [ ] **Step 1: Implement LocalWebServer**

```kotlin
package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.receiver.DebugLog
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalWebServer @Inject constructor(
    private val dao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val settings: SettingsRepository
) {
    companion object {
        const val PORT = 17580
    }

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.header("Access-Control-Allow-Origin", "*")
                val remoteHost = call.request.local.remoteHost
                if (!isLoopback(remoteHost)) {
                    val secret = settings.getWebServerSecret()
                    if (secret.isBlank()) {
                        call.respondText("Authentication required", status = HttpStatusCode.Forbidden)
                        return@intercept
                    }
                    val apiSecret = call.request.header("api-secret")
                    if (apiSecret == null || !checkApiSecret(apiSecret, secret)) {
                        call.respondText("Authentication failed", status = HttpStatusCode.Forbidden)
                        return@intercept
                    }
                }
            }
            routing {
                sgvRoutes(dao, treatmentDao, settings)
                statusRoutes(settings)
                treatmentRoutes(treatmentDao)
                healthRoutes()
            }
        }.start(wait = false)
        DebugLog.log("Web server started on port $PORT")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        DebugLog.log("Web server stopped")
    }
}

private fun Routing.sgvRoutes(dao: ReadingDao, treatmentDao: TreatmentDao, settings: SettingsRepository) {
    suspend fun handleSgv(call: ApplicationCall) {
        val count = (call.request.queryParameters["count"]?.toIntOrNull() ?: 24).coerceIn(1, 1000)
        val briefMode = call.request.queryParameters["brief_mode"]?.uppercase() == "Y"
            || call.request.queryParameters["brief_mode"] == "true"
        val stepsParam = call.request.queryParameters["steps"]?.toIntOrNull()
        val heartParam = call.request.queryParameters["heart"]?.toIntOrNull()

        val readings = dao.lastN(count)
        val unit = settings.glucoseUnit.first()
        val unitsHint = if (unit == GlucoseUnit.MMOL) "mmol" else "mgdl"

        val iob = if (settings.treatmentsSyncEnabled.first()) {
            val insulinType = settings.insulinType.first()
            val customDIA = settings.customDIA.first()
            val tau = IOBComputer.tauForInsulinType(insulinType, customDIA)
            val lookbackMs = (5.0 * tau * 60_000).toLong()
            val treatments = treatmentDao.insulinSince(System.currentTimeMillis() - lookbackMs)
            IOBComputer.computeIOB(treatments, System.currentTimeMillis(), tau)
        } else null

        val json = buildSgvJson(
            readings = readings,
            briefMode = briefMode,
            unitsHint = unitsHint,
            iob = iob,
            stepsResult = stepsParam?.let { 200 },
            heartResult = heartParam?.let { 200 }
        )
        call.respondText(json, ContentType.Application.Json)
    }

    get("/sgv.json") { handleSgv(call) }
    get("/api/v1/entries/sgv.json") { handleSgv(call) }
}

private fun Routing.statusRoutes(settings: SettingsRepository) {
    suspend fun handleStatus(call: ApplicationCall) {
        val unit = settings.glucoseUnit.first()
        val unitsHint = if (unit == GlucoseUnit.MMOL) "mmol" else "mgdl"
        val bgLow = settings.bgLow.first()
        val bgHigh = settings.bgHigh.first()
        val json = buildStatusJson(unitsHint, bgLow, bgHigh)
        call.respondText(json, ContentType.Application.Json)
    }

    get("/status.json") { handleStatus(call) }
}

private fun Routing.treatmentRoutes(treatmentDao: TreatmentDao) {
    suspend fun handleTreatments(call: ApplicationCall) {
        val count = (call.request.queryParameters["count"]?.toIntOrNull() ?: 24).coerceIn(1, 100)
        val since = System.currentTimeMillis() - 48 * 3600_000L
        val treatments = treatmentDao.allSince(since).take(count)
        val json = buildTreatmentsJson(treatments)
        call.respondText(json, ContentType.Application.Json)
    }

    get("/treatments.json") { handleTreatments(call) }
    get("/api/v1/treatments.json") { handleTreatments(call) }
}

private fun Routing.healthRoutes() {
    get("/heart/set/{bpm}/{accuracy}") {
        call.respondText("OK")
    }
    get("/steps/set/{value}") {
        call.respondText("OK")
    }
}
```

- [ ] **Step 2: Add `allSince` query to TreatmentDao**

In `app/src/main/java/com/psjostrom/strimma/data/TreatmentDao.kt`, add:

```kotlin
@Query("SELECT * FROM treatments WHERE createdAt >= :timestamp ORDER BY createdAt DESC")
suspend fun allSince(timestamp: Long): List<Treatment>
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
feat: add LocalWebServer with Ktor routing and all endpoints
```

---

### Task 7: Wire into StrimmaService

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt`

- [ ] **Step 1: Add server injection and lifecycle**

Add to the injected fields:

```kotlin
@Inject lateinit var localWebServer: LocalWebServer
```

Add a `StateFlow` and a job for the web server in the `lateinit` block:

```kotlin
private lateinit var webServerEnabled: StateFlow<Boolean>
private var webServerJob: Job? = null
```

In `onCreate()`, after the existing treatment sync lifecycle block, add:

```kotlin
webServerEnabled = settings.webServerEnabled.stateIn(scope, SharingStarted.Eagerly, false)

scope.launch {
    settings.webServerEnabled.collect { enabled ->
        if (enabled) {
            localWebServer.start()
        } else {
            localWebServer.stop()
        }
    }
}
```

In `onDestroy()`, before `scope.cancel()`, add:

```kotlin
localWebServer.stop()
```

- [ ] **Step 2: Add import**

```kotlin
import com.psjostrom.strimma.webserver.LocalWebServer
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
feat: wire LocalWebServer lifecycle into StrimmaService
```

---

### Task 8: Settings UI

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/settings/DataSettings.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Add ViewModel properties**

In `MainViewModel.kt`, after the `bgBroadcastEnabled` block, add:

```kotlin
val webServerEnabled: StateFlow<Boolean> = settings.webServerEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
fun setWebServerEnabled(enabled: Boolean) = viewModelScope.launch { settings.setWebServerEnabled(enabled) }

val webServerSecret: String get() = settings.getWebServerSecret()
fun setWebServerSecret(secret: String) = settings.setWebServerSecret(secret)
```

- [ ] **Step 2: Update DataSettings composable**

Add parameters to the `DataSettings` function signature:

```kotlin
fun DataSettings(
    bgBroadcastEnabled: Boolean,
    onBgBroadcastEnabledChange: (Boolean) -> Unit,
    webServerEnabled: Boolean,
    webServerSecret: String,
    onWebServerEnabledChange: (Boolean) -> Unit,
    onWebServerSecretChange: (String) -> Unit,
    onStats: () -> Unit,
    // ... rest unchanged
```

Add a web server section in the Integration group, after the BG Broadcast row:

```kotlin
HorizontalDivider(color = outlineVar)
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
        Text("Local Web Server", color = onBg, fontSize = 14.sp)
        Text(
            "Serve BG data on port 17580 for watchfaces and apps",
            color = outline,
            fontSize = 12.sp
        )
    }
    Switch(checked = webServerEnabled, onCheckedChange = onWebServerEnabledChange)
}
if (webServerEnabled) {
    var secretText by remember { mutableStateOf(webServerSecret) }
    LaunchedEffect(webServerSecret) {
        if (webServerSecret != secretText) secretText = webServerSecret
    }
    OutlinedTextField(
        value = secretText,
        onValueChange = {
            secretText = it
            onWebServerSecretChange(it)
        },
        label = { Text("API Secret") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
```

- [ ] **Step 3: Wire in MainActivity**

In the `composable("settings/data")` block in `MainActivity.kt`, add state collection and pass to `DataSettings`:

Before the `DataSettings(` call, add:

```kotlin
val webServerEnabled by viewModel.webServerEnabled.collectAsState()
```

Add to the `DataSettings(` call:

```kotlin
webServerEnabled = webServerEnabled,
webServerSecret = viewModel.webServerSecret,
onWebServerEnabledChange = viewModel::setWebServerEnabled,
onWebServerSecretChange = viewModel::setWebServerSecret,
```

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```
feat: add web server settings UI in Data settings
```

---

### Task 9: Run all tests, verify, final commit

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: ALL PASS

- [ ] **Step 2: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification checklist**

Install on device, then verify:
1. Settings > Data > Integration shows "Local Web Server" toggle
2. Toggle on — server starts (check debug log for "Web server started on port 17580")
3. From laptop on same WiFi: `curl http://<phone-ip>:17580/sgv.json` returns 403 (no secret)
4. Set a secret in settings, then: `curl -H "api-secret: <sha1-of-secret>" http://<phone-ip>:17580/sgv.json` returns JSON array
5. `curl http://127.0.0.1:17580/sgv.json` from an app on the phone works without auth
6. `/status.json` returns thresholds
7. `/treatments.json` returns treatments (if sync enabled)
8. `/heart/set/72/1` returns "OK"
9. `/steps/set/5000` returns "OK"
10. Toggle off — server stops (check debug log)

- [ ] **Step 4: Commit if any fixes needed**

```
fix: [describe what was fixed]
```
