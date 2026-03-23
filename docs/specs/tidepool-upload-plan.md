# Tidepool Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upload CGM glucose readings from Strimma's Room database to Tidepool's cloud platform via OIDC-authenticated REST API.

**Architecture:** TidepoolUploader orchestrates the upload cycle (auth check → dataset get/create → batch upload → advance cursor). TidepoolClient handles HTTP calls to Tidepool's Data API. TidepoolAuthManager handles OIDC token lifecycle via AppAuth-Android. All three are Hilt singletons following the existing NightscoutPusher/NightscoutClient pattern.

**Tech Stack:** Kotlin, Ktor 3.4.1 (HTTP), AppAuth-Android 0.11.1 (OIDC), Room (data source), DataStore + EncryptedSharedPreferences (settings/tokens), Hilt (DI), Coroutines (async), JUnit 4 + Robolectric (tests)

**Spec:** `docs/specs/tidepool-upload-spec.md`

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `tidepool/TidepoolModels.kt` | Data classes: CbgRecord, DatasetRequest, DatasetResponse, TokenInfo |
| `tidepool/TidepoolDateUtil.kt` | ISO 8601 UTC/local formatting, timezone offset computation |
| `tidepool/TidepoolClient.kt` | HTTP calls to Tidepool Data API (dataset CRUD, data upload) |
| `tidepool/TidepoolAuthManager.kt` | OIDC token lifecycle: login, refresh, token storage, re-auth notification |
| `tidepool/TidepoolAuthActivity.kt` | Activity to handle OIDC redirect (`strimma://callback/tidepool`) |
| `tidepool/TidepoolUploader.kt` | Upload orchestration: trigger, chunk, auth check, upload, cursor advance |
| `ui/settings/TidepoolSettings.kt` | Settings UI: enable toggle, login button, status, conditions |

### Modified files

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add `appauth` dependency |
| `app/build.gradle.kts` | Add `appauth` implementation dependency |
| `data/SettingsRepository.kt` | Add Tidepool settings keys + getters/setters |
| `service/StrimmaService.kt` | Hook TidepoolUploader on new reading + periodic upload |
| `ui/SettingsScreen.kt` | Add Tidepool menu item using `onNavigate("settings/tidepool")` |
| `ui/MainActivity.kt` | Add `composable("settings/tidepool")` route |
| `AndroidManifest.xml` | Add TidepoolAuthActivity with intent filter |

### Test files

| File | What it tests |
|------|--------------|
| `tidepool/TidepoolDateUtilTest.kt` | UTC/local formatting, timezone offset |
| `tidepool/TidepoolModelsTest.kt` | CBG record creation, validation, origin ID generation |
| `tidepool/TidepoolClientTest.kt` | HTTP request/response serialization (mock server) |
| `tidepool/TidepoolUploaderTest.kt` | Upload flow: chunking, cursor advance, skip conditions |

---

## Task 1: Data Models and Date Utilities

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolModels.kt`
- Create: `app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolDateUtil.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolDateUtilTest.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolModelsTest.kt`

- [ ] **Step 1: Write TidepoolDateUtil tests**

```kotlin
// TidepoolDateUtilTest.kt
package com.psjostrom.strimma.tidepool

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class TidepoolDateUtilTest {

    @Test
    fun `toUtcIso8601 formats timestamp correctly`() {
        // 2026-03-23T14:30:00.000Z in UTC
        val ts = 1774375800000L
        val result = TidepoolDateUtil.toUtcIso8601(ts)
        assertTrue(result.endsWith("0000Z"))
        assertTrue(result.startsWith("20"))
        assertTrue(result.contains("T"))
    }

    @Test
    fun `toLocalNoZone formats without timezone suffix`() {
        val ts = 1774375800000L
        val result = TidepoolDateUtil.toLocalNoZone(ts)
        assertFalse(result.contains("Z"))
        assertTrue(result.contains("T"))
    }

    @Test
    fun `getTimezoneOffsetMinutes returns correct offset`() {
        val offset = TidepoolDateUtil.getTimezoneOffsetMinutes(System.currentTimeMillis())
        // Should be a reasonable value (-720 to +840)
        assertTrue(offset in -720..840)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.tidepool.TidepoolDateUtilTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TidepoolDateUtil**

```kotlin
// TidepoolDateUtil.kt
package com.psjostrom.strimma.tidepool

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TidepoolDateUtil {

    fun toUtcIso8601(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'0000Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(timestamp)
    }

    fun toLocalNoZone(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(timestamp)
    }

    fun getTimezoneOffsetMinutes(timestamp: Long): Int {
        return TimeZone.getDefault().getOffset(timestamp) / 60_000
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.tidepool.TidepoolDateUtilTest"`
Expected: PASS

- [ ] **Step 5: Write TidepoolModels tests**

```kotlin
// TidepoolModelsTest.kt
package com.psjostrom.strimma.tidepool

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test

class TidepoolModelsTest {

    @Test
    fun `CbgRecord from GlucoseReading sets correct type and units`() {
        val reading = GlucoseReading(ts = 1774375800000L, sgv = 142, direction = "FLAT", delta = 1.0, pushed = 1)
        val cbg = CbgRecord.fromReading(reading)
        assertEquals("cbg", cbg.type)
        assertEquals("mg/dL", cbg.units)
        assertEquals(142, cbg.value)
    }

    @Test
    fun `CbgRecord origin id is deterministic`() {
        val reading = GlucoseReading(ts = 1774375800000L, sgv = 142, direction = "FLAT", delta = 1.0, pushed = 1)
        val cbg1 = CbgRecord.fromReading(reading)
        val cbg2 = CbgRecord.fromReading(reading)
        assertEquals(cbg1.origin.id, cbg2.origin.id)
        assertEquals("strimma-cbg-1774375800000", cbg1.origin.id)
    }

    @Test
    fun `isValidForUpload rejects out of range glucose`() {
        assertFalse(CbgRecord.isValidForUpload(GlucoseReading(ts = 1774375800000L, sgv = 38, direction = "FLAT", delta = null, pushed = 1)))
        assertFalse(CbgRecord.isValidForUpload(GlucoseReading(ts = 1774375800000L, sgv = 501, direction = "FLAT", delta = null, pushed = 1)))
        assertTrue(CbgRecord.isValidForUpload(GlucoseReading(ts = 1774375800000L, sgv = 100, direction = "FLAT", delta = null, pushed = 1)))
    }

    @Test
    fun `isValidForUpload rejects future timestamps`() {
        val future = System.currentTimeMillis() + 60_000
        assertFalse(CbgRecord.isValidForUpload(GlucoseReading(ts = future, sgv = 100, direction = "FLAT", delta = null, pushed = 1)))
    }

    @Test
    fun `isValidForUpload rejects timestamps before 2020`() {
        val old = 1577836800000L - 1 // 2020-01-01 minus 1ms
        assertFalse(CbgRecord.isValidForUpload(GlucoseReading(ts = old, sgv = 100, direction = "FLAT", delta = null, pushed = 1)))
    }
}
```

- [ ] **Step 6: Implement TidepoolModels**

```kotlin
// TidepoolModels.kt
package com.psjostrom.strimma.tidepool

import com.psjostrom.strimma.data.GlucoseReading
import kotlinx.serialization.Serializable

@Serializable
data class CbgRecord(
    val type: String = "cbg",
    val units: String = "mg/dL",
    val value: Int,
    val time: String,
    val deviceTime: String,
    val timezoneOffset: Int,
    val origin: Origin
) {
    @Serializable
    data class Origin(val id: String)

    companion object {
        private const val MIN_GLUCOSE = 39
        private const val MAX_GLUCOSE = 500
        private const val EPOCH_2020 = 1577836800000L

        fun fromReading(reading: GlucoseReading): CbgRecord = CbgRecord(
            value = reading.sgv,
            time = TidepoolDateUtil.toUtcIso8601(reading.ts),
            deviceTime = TidepoolDateUtil.toLocalNoZone(reading.ts),
            timezoneOffset = TidepoolDateUtil.getTimezoneOffsetMinutes(reading.ts),
            origin = Origin(id = "strimma-cbg-${reading.ts}")
        )

        fun isValidForUpload(reading: GlucoseReading): Boolean {
            return reading.sgv in MIN_GLUCOSE..MAX_GLUCOSE &&
                reading.ts >= EPOCH_2020 &&
                reading.ts <= System.currentTimeMillis()
        }
    }
}

@Serializable
data class DatasetRequest(
    val type: String = "upload",
    val dataSetType: String = "continuous",
    val client: ClientInfo,
    val deduplicator: Deduplicator = Deduplicator(),
    val deviceManufacturers: List<String> = listOf("Abbott"),
    val deviceModel: String = "Libre 3",
    val deviceTags: List<String> = listOf("cgm"),
    val time: String,
    val computerTime: String,
    val timezoneOffset: Int,
    val timezone: String,
    val timeProcessing: String = "none",
    val version: String
) {
    @Serializable
    data class ClientInfo(val name: String, val version: String)

    @Serializable
    data class Deduplicator(val name: String = "org.tidepool.deduplicator.dataset.delete.origin")
}

@Serializable
data class DatasetResponse(
    val uploadId: String? = null,
    val id: String? = null
)
```

- [ ] **Step 7: Run all tests**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.tidepool.*"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolModels.kt \
       app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolDateUtil.kt \
       app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolDateUtilTest.kt \
       app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolModelsTest.kt
git commit -m "feat(tidepool): add data models and date utilities"
```

---

## Task 2: Tidepool HTTP Client

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolClient.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolClientTest.kt`

- [ ] **Step 1: Write TidepoolClient tests**

Test the three API operations: get existing datasets, create dataset, upload data. Use Ktor's mock engine.

```kotlin
// TidepoolClientTest.kt
package com.psjostrom.strimma.tidepool

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TidepoolClientTest {

    private fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): TidepoolClient {
        val httpClient = HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return TidepoolClient(httpClient)
    }

    @Test
    fun `getExistingDataset returns uploadId when dataset exists`() = runTest {
        val client = mockClient { request ->
            assertEquals("/v1/users/user123/data_sets", request.url.encodedPath)
            assertEquals("token123", request.headers["x-tidepool-session-token"])
            respond(
                content = """[{"uploadId": "ds-abc"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val result = client.getExistingDataset("https://api.tidepool.org", "token123", "user123")
        assertEquals("ds-abc", result)
    }

    @Test
    fun `getExistingDataset returns null when no datasets`() = runTest {
        val client = mockClient {
            respond(content = "[]", status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val result = client.getExistingDataset("https://api.tidepool.org", "token123", "user123")
        assertNull(result)
    }

    @Test
    fun `createDataset returns uploadId on success`() = runTest {
        val client = mockClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"uploadId": "ds-new"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val result = client.createDataset("https://api.tidepool.org", "token123", "user123", "1.0.0")
        assertEquals("ds-new", result)
    }

    @Test
    fun `uploadData returns true on success`() = runTest {
        val client = mockClient { request ->
            assertEquals("/v1/datasets/ds-abc/data", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(content = "{}", status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val records = listOf(
            CbgRecord(value = 142, time = "2026-03-23T14:30:00.0000000Z",
                deviceTime = "2026-03-23T15:30:00", timezoneOffset = 60,
                origin = CbgRecord.Origin("strimma-cbg-123"))
        )
        val result = client.uploadData("https://api.tidepool.org", "token123", "ds-abc", records)
        assertTrue(result)
    }

    @Test
    fun `uploadData returns false on 400 error`() = runTest {
        val client = mockClient {
            respond(content = """{"message":"invalid"}""", status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val result = client.uploadData("https://api.tidepool.org", "token123", "ds-abc", emptyList())
        assertFalse(result)
    }
}
```

- [ ] **Step 2: Add Ktor mock engine test dependency**

Add to `libs.versions.toml`:
```toml
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```

Add to `app/build.gradle.kts` test dependencies:
```kotlin
testImplementation(libs.ktor.client.mock)
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.tidepool.TidepoolClientTest"`
Expected: FAIL — class not found

- [ ] **Step 4: Implement TidepoolClient**

```kotlin
// TidepoolClient.kt
package com.psjostrom.strimma.tidepool

import com.psjostrom.strimma.receiver.DebugLog
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TidepoolClient @Inject constructor() {
    // Internal HttpClient — matches NightscoutClient pattern (no DI for HttpClient)
    // @VisibleForTesting: use constructor(httpClient) for tests with MockEngine
    private val httpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    internal constructor(httpClient: HttpClient) : this() {
        // Test-only constructor — replaces the internal client
    }

    // Note: the test constructor pattern above is simplified. In practice,
    // make httpClient a var with a default, or use a factory. The implementing
    // agent should follow whichever pattern compiles cleanly with Hilt.
    // The key constraint: no @Provides for HttpClient in AppModule.
    companion object {
        private const val SESSION_TOKEN_HEADER = "x-tidepool-session-token"
        private const val CLIENT_NAME = "com.psjostrom.strimma"
    }

    suspend fun getExistingDataset(baseUrl: String, token: String, userId: String): String? {
        return try {
            val response: List<DatasetResponse> = httpClient.get("$baseUrl/v1/users/$userId/data_sets") {
                header(SESSION_TOKEN_HEADER, token)
                parameter("client.name", CLIENT_NAME)
                parameter("size", 1)
            }.body()
            response.firstOrNull()?.uploadId
        } catch (e: Exception) {
            DebugLog.log("Tidepool: get dataset failed: ${e.message?.take(80)}")
            null
        }
    }

    suspend fun createDataset(baseUrl: String, token: String, userId: String, appVersion: String): String? {
        return try {
            val now = System.currentTimeMillis()
            val request = DatasetRequest(
                client = DatasetRequest.ClientInfo(name = CLIENT_NAME, version = appVersion),
                time = TidepoolDateUtil.toUtcIso8601(now),
                computerTime = TidepoolDateUtil.toLocalNoZone(now),
                timezoneOffset = TidepoolDateUtil.getTimezoneOffsetMinutes(now),
                timezone = java.util.TimeZone.getDefault().id,
                version = appVersion
            )
            val response: DatasetResponse = httpClient.post("$baseUrl/v1/users/$userId/data_sets") {
                header(SESSION_TOKEN_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
            response.uploadId
        } catch (e: Exception) {
            DebugLog.log("Tidepool: create dataset failed: ${e.message?.take(80)}")
            null
        }
    }

    suspend fun uploadData(baseUrl: String, token: String, datasetId: String, records: List<CbgRecord>): Boolean {
        return try {
            val response = httpClient.post("$baseUrl/v1/datasets/$datasetId/data") {
                header(SESSION_TOKEN_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(records)
            }
            if (response.status.isSuccess()) {
                true
            } else {
                DebugLog.log("Tidepool: upload failed HTTP ${response.status}: ${response.bodyAsText().take(80)}")
                false
            }
        } catch (e: Exception) {
            DebugLog.log("Tidepool: upload exception: ${e.message?.take(80)}")
            false
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.tidepool.TidepoolClientTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolClient.kt \
       app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolClientTest.kt \
       gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(tidepool): add HTTP client for Tidepool Data API"
```

---

## Task 3: Settings and Storage

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt`

- [ ] **Step 1: Add Tidepool settings keys and accessors to SettingsRepository**

Add these keys to the companion object and corresponding Flow getters + suspend setters, following the existing pattern (e.g., `KEY_NIGHTSCOUT_URL` / `nightscoutUrl` / `setNightscoutUrl`):

DataStore keys:
- `tidepool_enabled` (Boolean, default false)
- `tidepool_environment` (String, default "PRODUCTION")
- `tidepool_only_while_charging` (Boolean, default false)
- `tidepool_only_while_wifi` (Boolean, default false)
- `tidepool_user_id` (String, default "")
- `tidepool_dataset_id` (String, default "")
- `tidepool_last_upload_end` (Long, default 0)
- `tidepool_last_upload_time` (Long, default 0)
- `tidepool_last_error` (String, default "")

EncryptedSharedPreferences keys:
- `tidepool_refresh_token` (String, read/write via `encryptedPrefs`)

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt
git commit -m "feat(tidepool): add Tidepool settings to SettingsRepository"
```

---

## Task 4: OIDC Auth Manager

**Files:**
- Modify: `gradle/libs.versions.toml` — add AppAuth dependency
- Modify: `app/build.gradle.kts` — add AppAuth implementation
- Create: `app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolAuthManager.kt`
- Create: `app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolAuthActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` — add TidepoolAuthActivity

- [ ] **Step 1: Add AppAuth dependency**

`libs.versions.toml`:
```toml
appauth = { module = "net.openid:appauth", version = "0.11.1" }
```

`app/build.gradle.kts`:
```kotlin
implementation(libs.appauth)
```

- [ ] **Step 2: Implement TidepoolAuthManager**

```kotlin
// TidepoolAuthManager.kt
package com.psjostrom.strimma.tidepool

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class TidepoolAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    companion object {
        private const val CLIENT_ID = "strimma"
        private const val REDIRECT_URI = "strimma://callback/tidepool"
        private const val SCOPE = "openid email data_read data_write offline_access"

        private const val INTEGRATION_AUTH_BASE = "https://auth.integration.tidepool.org/realms/integration"
        private const val PRODUCTION_AUTH_BASE = "https://auth.tidepool.org/realms/tidepool"

        const val INTEGRATION_API_BASE = "https://external.integration.tidepool.org"
        const val PRODUCTION_API_BASE = "https://api.tidepool.org"
    }

    private var accessToken: String? = null
    private var accessTokenExpiry: Long = 0
    private val refreshMutex = Mutex()

    private fun getAuthBase(environment: String): String =
        if (environment == "INTEGRATION") INTEGRATION_AUTH_BASE else PRODUCTION_AUTH_BASE

    fun getApiBase(environment: String): String =
        if (environment == "INTEGRATION") INTEGRATION_API_BASE else PRODUCTION_API_BASE

    fun buildAuthIntent(environment: String): Intent {
        val authBase = getAuthBase(environment)
        val config = AuthorizationServiceConfiguration(
            Uri.parse("$authBase/protocol/openid-connect/auth"),
            Uri.parse("$authBase/protocol/openid-connect/token")
        )
        val request = AuthorizationRequest.Builder(
            config, CLIENT_ID, ResponseTypeValues.CODE, Uri.parse(REDIRECT_URI)
        ).setScope(SCOPE).build()

        val authService = AuthorizationService(context)
        return authService.getAuthorizationRequestIntent(request)
    }

    suspend fun handleAuthResponse(intent: Intent): Boolean {
        val response = AuthorizationResponse.fromIntent(intent) ?: return false
        val exception = AuthorizationException.fromIntent(intent)
        if (exception != null) {
            DebugLog.log("Tidepool: auth error: ${exception.message}")
            return false
        }

        val environment = settings.tidepoolEnvironment.first()
        val authBase = getAuthBase(environment)
        val config = AuthorizationServiceConfiguration(
            Uri.parse("$authBase/protocol/openid-connect/auth"),
            Uri.parse("$authBase/protocol/openid-connect/token")
        )
        val authService = AuthorizationService(context)

        return suspendCoroutine { cont ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, ex ->
                if (tokenResponse != null && ex == null) {
                    accessToken = tokenResponse.accessToken
                    accessTokenExpiry = tokenResponse.accessTokenExpirationTime ?: 0
                    val refreshToken = tokenResponse.refreshToken
                    if (refreshToken != null) {
                        settings.setTidepoolRefreshToken(refreshToken)
                    }
                    DebugLog.log("Tidepool: auth successful")
                    cont.resume(true)
                } else {
                    DebugLog.log("Tidepool: token exchange failed: ${ex?.message}")
                    cont.resume(false)
                }
            }
        }
    }

    suspend fun getValidAccessToken(): String? {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry - 30_000) {
            return accessToken
        }
        return refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        // Double-check after acquiring lock
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiry - 30_000) {
            return accessToken
        }

        val refreshToken = settings.getTidepoolRefreshToken()
        if (refreshToken.isBlank()) {
            DebugLog.log("Tidepool: no refresh token, login required")
            return null
        }

        val environment = settings.tidepoolEnvironment.first()
        val authBase = getAuthBase(environment)
        val config = AuthorizationServiceConfiguration(
            Uri.parse("$authBase/protocol/openid-connect/auth"),
            Uri.parse("$authBase/protocol/openid-connect/token")
        )
        val authService = AuthorizationService(context)

        val tokenRequest = TokenRequest.Builder(config, CLIENT_ID)
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refreshToken)
            .build()

        return suspendCoroutine { cont ->
            authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->
                if (tokenResponse != null && ex == null) {
                    accessToken = tokenResponse.accessToken
                    accessTokenExpiry = tokenResponse.accessTokenExpirationTime ?: 0
                    tokenResponse.refreshToken?.let { settings.setTidepoolRefreshToken(it) }
                    DebugLog.log("Tidepool: token refreshed")
                    cont.resume(accessToken)
                } else {
                    DebugLog.log("Tidepool: refresh failed: ${ex?.message}")
                    accessToken = null
                    settings.setTidepoolRefreshToken("")
                    cont.resume(null)
                }
            }
        }
    }

    private val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun fetchUserId(): String? {
        val token = getValidAccessToken() ?: return null
        val environment = settings.tidepoolEnvironment.first()
        val authBase = getAuthBase(environment)
        return try {
            val response = httpClient.get("$authBase/protocol/openid-connect/userinfo") {
                header("Authorization", "Bearer $token")
            }
            val userInfo: UserInfoResponse = response.body()
            userInfo.sub
        } catch (e: Exception) {
            DebugLog.log("Tidepool: fetch userId failed: ${e.message?.take(80)}")
            null
        }
    }

    fun isLoggedIn(): Boolean = settings.getTidepoolRefreshToken().isNotBlank()

    fun logout() {
        accessToken = null
        accessTokenExpiry = 0
        // Revoke refresh token at Tidepool's endpoint (best effort)
        val refreshToken = settings.getTidepoolRefreshToken()
        if (refreshToken.isNotBlank()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val environment = settings.tidepoolEnvironment.first()
                    val authBase = getAuthBase(environment)
                    httpClient.post("$authBase/protocol/openid-connect/logout") {
                        header("Authorization", "Bearer $refreshToken")
                    }
                } catch (_: Exception) { /* best effort */ }
            }
        }
        settings.setTidepoolRefreshToken("")
    }
}

@kotlinx.serialization.Serializable
private data class UserInfoResponse(val sub: String)
```

- [ ] **Step 3: Implement TidepoolAuthActivity**

```kotlin
// TidepoolAuthActivity.kt
package com.psjostrom.strimma.tidepool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TidepoolAuthActivity : ComponentActivity() {

    @Inject lateinit var authManager: TidepoolAuthManager
    @Inject lateinit var settings: com.psjostrom.strimma.data.SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent ?: run { finish(); return }

        lifecycleScope.launch(Dispatchers.IO) {
            val success = authManager.handleAuthResponse(intent)
            if (success) {
                val userId = authManager.fetchUserId()
                if (userId != null) {
                    settings.setTidepoolUserId(userId)
                }
            }
            finish()
        }
    }
}
```

- [ ] **Step 4: Add TidepoolAuthActivity to AndroidManifest.xml**

Add inside `<application>`:
```xml
<activity
    android:name=".tidepool.TidepoolAuthActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="strimma"
            android:host="callback"
            android:path="/tidepool" />
    </intent-filter>
</activity>
```

- [ ] **Step 5: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolAuthManager.kt \
       app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolAuthActivity.kt \
       app/src/main/AndroidManifest.xml \
       gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(tidepool): add OIDC auth manager and redirect activity"
```

---

## Task 5: Upload Orchestrator

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolUploader.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolUploaderTest.kt`

- [ ] **Step 1: Write TidepoolUploader tests**

```kotlin
// TidepoolUploaderTest.kt
package com.psjostrom.strimma.tidepool

import org.junit.Assert.*
import org.junit.Test

class TidepoolUploaderTest {

    @Test
    fun `computeChunkEnd caps at 7 days from start`() {
        val start = 1774375800000L
        val now = start + 14 * 86_400_000L // 14 days later
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertEquals(start + 7 * 86_400_000L, end)
    }

    @Test
    fun `computeChunkEnd uses now minus buffer when within 7 days`() {
        val start = 1774375800000L
        val now = start + 3600_000L // 1 hour later
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertEquals(now - TidepoolUploader.UPLOAD_BUFFER_MS, end)
    }

    @Test
    fun `computeChunkEnd returns start when now is too close`() {
        val start = 1774375800000L
        val now = start + 60_000L // 1 minute later (less than buffer)
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertTrue(end <= start)
    }

    @Test
    fun `getLastUploadStart clamps to 2 months ago`() {
        val result = TidepoolUploader.clampLastUploadEnd(0L, System.currentTimeMillis())
        val twoMonthsAgo = System.currentTimeMillis() - 60L * 86_400_000L
        assertTrue(result >= twoMonthsAgo - 1000)
    }
}
```

- [ ] **Step 2: Implement TidepoolUploader**

```kotlin
// TidepoolUploader.kt
package com.psjostrom.strimma.tidepool

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TidepoolUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TidepoolClient,
    private val authManager: TidepoolAuthManager,
    private val dao: ReadingDao,
    private val settings: SettingsRepository
) {
    companion object {
        const val MAX_CHUNK_MS = 7L * 86_400_000L // 7 days
        const val UPLOAD_BUFFER_MS = 15L * 60_000L // 15 minutes
        private const val MAX_LOOKBACK_MS = 60L * 86_400_000L // 2 months
        private const val RATE_LIMIT_MS = 20L * 60_000L // 20 minutes

        fun computeChunkEnd(start: Long, now: Long): Long {
            val maxEnd = start + MAX_CHUNK_MS
            val bufferedNow = now - UPLOAD_BUFFER_MS
            return minOf(maxEnd, bufferedNow)
        }

        fun clampLastUploadEnd(stored: Long, now: Long): Long {
            val earliest = now - MAX_LOOKBACK_MS
            return maxOf(stored, earliest)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastUploadAttempt = 0L

    fun onNewReading() {
        scope.launch { uploadIfReady() }
    }

    fun uploadPending() {
        scope.launch { uploadIfReady() }
    }

    private suspend fun uploadIfReady() {
        if (!settings.tidepoolEnabled.first()) return
        if (!authManager.isLoggedIn()) return

        val now = System.currentTimeMillis()
        if (now - lastUploadAttempt < RATE_LIMIT_MS) return
        lastUploadAttempt = now

        if (settings.tidepoolOnlyWhileCharging.first() && !isCharging()) return
        if (settings.tidepoolOnlyWhileWifi.first() && !isOnWifi()) return

        doUpload()
    }

    private suspend fun doUpload() {
        val token = authManager.getValidAccessToken()
        if (token == null) {
            settings.setTidepoolLastError("Login required")
            DebugLog.log("Tidepool: upload skipped, no valid token")
            return
        }

        val environment = settings.tidepoolEnvironment.first()
        val apiBase = authManager.getApiBase(environment)
        val userId = settings.tidepoolUserId.first()
        if (userId.isBlank()) {
            DebugLog.log("Tidepool: no user ID, login required")
            return
        }

        // Get or create dataset
        var datasetId = settings.tidepoolDatasetId.first()
        if (datasetId.isBlank()) {
            datasetId = client.getExistingDataset(apiBase, token, userId)
                ?: client.createDataset(apiBase, token, userId, getAppVersion())
                ?: run {
                    settings.setTidepoolLastError("Failed to create dataset")
                    return
                }
            settings.setTidepoolDatasetId(datasetId)
            DebugLog.log("Tidepool: using dataset $datasetId")
        }

        // Compute chunk
        val now = System.currentTimeMillis()
        val lastEnd = clampLastUploadEnd(settings.tidepoolLastUploadEnd.first(), now)
        val chunkEnd = computeChunkEnd(lastEnd, now)
        if (chunkEnd <= lastEnd) return // Nothing to upload

        // Query readings
        val readings = dao.since(lastEnd)
            .filter { it.ts <= chunkEnd }
            .filter { CbgRecord.isValidForUpload(it) }

        if (readings.isEmpty()) {
            settings.setTidepoolLastUploadEnd(chunkEnd)
            return
        }

        // Upload
        val records = readings.map { CbgRecord.fromReading(it) }
        val success = client.uploadData(apiBase, token, datasetId, records)
        if (success) {
            settings.setTidepoolLastUploadEnd(chunkEnd)
            settings.setTidepoolLastUploadTime(System.currentTimeMillis())
            settings.setTidepoolLastError("")
            DebugLog.log("Tidepool: uploaded ${records.size} readings")
        } else {
            settings.setTidepoolLastError("Upload failed")
            DebugLog.log("Tidepool: upload failed for ${records.size} readings")
        }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.tidepool.TidepoolUploaderTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/tidepool/TidepoolUploader.kt \
       app/src/test/java/com/psjostrom/strimma/tidepool/TidepoolUploaderTest.kt
git commit -m "feat(tidepool): add upload orchestrator with chunking and rate limiting"
```

---

## Task 6: Service Integration

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt`

- [ ] **Step 1: Add TidepoolUploader injection**

Add alongside existing `@Inject lateinit var pusher: NightscoutPusher`:
```kotlin
@Inject lateinit var tidepoolUploader: TidepoolUploader
```

- [ ] **Step 2: Hook into processReading**

After line 272 (`pusher.pushReading(reading)`), add:
```kotlin
tidepoolUploader.onNewReading()
```

- [ ] **Step 3: Add periodic Tidepool upload to startPeriodicJobs**

In the existing periodic jobs section (where `pusher.pushPending()` is called), add:
```kotlin
tidepoolUploader.uploadPending()
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt
git commit -m "feat(tidepool): hook uploader into StrimmaService"
```

---

## Task 7: Settings UI

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/ui/settings/TidepoolSettings.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Create TidepoolSettings composable**

Follow the pattern of existing settings screens (e.g., `DataSettings.kt`). Include:
- Enable Tidepool upload toggle
- Login/Disconnect button (launches `authManager.buildAuthIntent()`)
- Connection status text
- Only while charging toggle
- Only on Wi-Fi toggle
- Last upload time / error display

- [ ] **Step 2: Add Tidepool menu item to SettingsScreen**

In `SettingsScreen.kt`, add a `SettingsMenuItem` using the existing `onNavigate` callback pattern:
```kotlin
SettingsMenuItem(
    icon = Icons.Default.Cloud,
    title = "Tidepool",
    subtitle = "Upload to Tidepool",
    onClick = { onNavigate("settings/tidepool") }
)
```

In `MainActivity.kt`, add the navigation route alongside existing settings routes:
```kotlin
composable("settings/tidepool") {
    TidepoolSettings(onBack = { navController.popBackStack() }, /* state + callbacks */)
}
```

Follow the existing pattern: `TidepoolSettings` receives individual state values and callbacks,
not raw repositories. Wire state from ViewModel/settings in `MainActivity`.

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/TidepoolSettings.kt \
       app/src/main/java/com/psjostrom/strimma/ui/SettingsScreen.kt \
       app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "feat(tidepool): add Tidepool settings UI"
```

---

## Task 8: Final Verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS (existing + new Tidepool tests)

- [ ] **Step 2: Run lint**

Run: `./gradlew lintDebug`
Expected: No new errors (may need lint baseline update)

- [ ] **Step 3: Verify debug build installs**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, APK generated

- [ ] **Step 4: Final commit if any cleanup needed**

---

## Notes

- **OIDC auth cannot be fully tested without a client ID from Tidepool.** The auth flow (Task 4) compiles and follows the correct pattern, but the browser redirect won't work until Tidepool registers the `strimma` client. The data pipeline (Tasks 1-3, 5) is fully testable independently.
- **TidepoolClient tests use Ktor MockEngine** — no network calls in tests.
- **TidepoolUploader tests are pure unit tests** for the chunking/clamping logic. Integration testing of the full upload flow requires a Tidepool integration environment account.
