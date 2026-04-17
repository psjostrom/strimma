package com.psjostrom.strimma.update

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    private fun mockClient(
        releaseJson: String = RELEASE_WITH_APK,
        releaseStatus: HttpStatusCode = HttpStatusCode.OK,
        updateJson: String = """{"min_version": "0.0.0"}""",
        updateStatus: HttpStatusCode = HttpStatusCode.OK
    ): HttpClient = HttpClient(MockEngine { request ->
        when {
            request.url.encodedPath.contains("releases/latest") -> respond(
                content = releaseJson,
                status = releaseStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
            request.url.encodedPath.contains("update.json") ||
                request.url.host == "raw.githubusercontent.com" -> respond(
                content = updateJson,
                status = updateStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
            else -> respond("Not found", HttpStatusCode.NotFound)
        }
    }) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun checker(client: HttpClient): UpdateChecker {
        val c = UpdateChecker()
        c.client = client
        return c
    }

    @Test
    fun `update available when release is newer`() = runTest {
        val c = checker(mockClient())
        c.check()
        val info = c.updateInfo.value
        assertNotNull(info)
        assertEquals("2.0.0", info!!.version)
        assertEquals("https://github.com/test/strimma-2.0.0.apk", info.apkUrl)
        assertFalse(info.isForced)
    }

    @Test
    fun `no update when release is same version`() = runTest {
        val release = release(tag = "v${currentVersion()}")
        val c = checker(mockClient(releaseJson = release))
        c.check()
        assertNull(c.updateInfo.value)
    }

    @Test
    fun `no update when release is older`() = runTest {
        val release = release(tag = "v0.0.1")
        val c = checker(mockClient(releaseJson = release))
        c.check()
        assertNull(c.updateInfo.value)
    }

    @Test
    fun `no update when release has no APK asset`() = runTest {
        val release = """{"tag_name": "v2.0.0", "body": "notes", "assets": [
            {"name": "checksums.txt", "browser_download_url": "https://example.com/checksums.txt"}
        ]}"""
        val c = checker(mockClient(releaseJson = release))
        c.check()
        assertNull(c.updateInfo.value)
    }

    @Test
    fun `forced when current is below min_version`() = runTest {
        val c = checker(mockClient(updateJson = """{"min_version": "99.0.0"}"""))
        c.check()
        val info = c.updateInfo.value
        assertNotNull(info)
        assertTrue(info!!.isForced)
    }

    @Test
    fun `not forced when current meets min_version`() = runTest {
        val c = checker(mockClient(updateJson = """{"min_version": "0.0.1"}"""))
        c.check()
        val info = c.updateInfo.value
        assertNotNull(info)
        assertFalse(info!!.isForced)
    }

    @Test
    fun `not forced when update json fetch fails`() = runTest {
        val c = checker(mockClient(updateStatus = HttpStatusCode.NotFound))
        c.check()
        val info = c.updateInfo.value
        assertNotNull(info)
        assertFalse(info!!.isForced)
    }

    @Test
    fun `changelog from release body`() = runTest {
        val release = release(body = "  Fixed bugs  ")
        val c = checker(mockClient(releaseJson = release))
        c.check()
        assertEquals("Fixed bugs", c.updateInfo.value?.changelog)
    }

    @Test
    fun `null body gives empty changelog`() = runTest {
        val release = """{"tag_name": "v2.0.0", "assets": [
            {"name": "strimma-2.0.0.apk", "browser_download_url": "https://example.com/a.apk"}
        ]}"""
        val c = checker(mockClient(releaseJson = release))
        c.check()
        assertEquals("", c.updateInfo.value?.changelog)
    }

    @Test
    fun `check clears update when no longer outdated`() = runTest {
        val c = checker(mockClient())
        c.check()
        assertNotNull(c.updateInfo.value)

        // Replace client with one returning current version
        c.client = mockClient(releaseJson = release(tag = "v${currentVersion()}"))
        c.check()
        assertNull(c.updateInfo.value)
    }

    @Test
    fun `dismiss sets dismissed flag`() {
        val c = UpdateChecker()
        assertFalse(c.dismissed.value)
        c.dismiss()
        assertTrue(c.dismissed.value)
    }

    @Test
    fun `resetDismissed clears dismissed flag`() {
        val c = UpdateChecker()
        c.dismiss()
        assertTrue(c.dismissed.value)
        c.resetDismissed()
        assertFalse(c.dismissed.value)
    }

    @Test
    fun `manual check shows dialog after previous dismiss`() = runTest {
        val c = checker(mockClient())
        c.check()
        assertNotNull(c.updateInfo.value)

        c.dismiss()
        assertTrue(c.dismissed.value)

        // Simulate manual check: reset dismissed, re-check
        c.resetDismissed()
        c.check()
        assertFalse(c.dismissed.value)
        assertNotNull(c.updateInfo.value)
    }

    @Test
    fun `check survives GitHub API error`() = runTest {
        val c = checker(mockClient(releaseStatus = HttpStatusCode.InternalServerError))
        c.check() // should not throw
        assertNull(c.updateInfo.value)
    }

    @Test
    fun `check survives malformed JSON`() = runTest {
        val c = checker(mockClient(releaseJson = "not json"))
        c.check() // should not throw
        assertNull(c.updateInfo.value)
    }

    @Test
    fun `forced update works with text plain content type`() = runTest {
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.contains("releases/latest") -> respond(
                    content = RELEASE_WITH_APK,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                request.url.host == "raw.githubusercontent.com" -> respond(
                    content = """{"min_version": "99.0.0"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain; charset=utf-8")
                )
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val c = checker(client)
        c.check()
        val info = c.updateInfo.value
        assertNotNull(info)
        assertTrue("Expected forced=true with text/plain update.json", info!!.isForced)
    }

    @Test
    fun `min_version null in JSON means not forced`() = runTest {
        val c = checker(mockClient(updateJson = """{"min_version": null}"""))
        c.check()
        assertNotNull(c.updateInfo.value)
        assertFalse(c.updateInfo.value!!.isForced)
    }

    // --- beta tests ---

    private fun betaMockClient(
        allReleasesJson: String = ALL_RELEASES_WITH_BETA
    ): HttpClient = HttpClient(MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("/releases") -> respond(
                content = allReleasesJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
            else -> respond("Not found", HttpStatusCode.NotFound)
        }
    }) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Test
    fun `checkBeta finds pre-release newer than current`() = runTest {
        val c = checker(betaMockClient())
        c.checkBeta()
        val info = c.betaUpdateInfo.value
        assertNotNull(info)
        assertEquals("2.0.0-rc.1", info!!.version)
        assertFalse(info.isForced)
    }

    @Test
    fun `checkBeta ignores stable releases`() = runTest {
        val allStable = """[
            {"tag_name": "v2.0.0", "prerelease": false, "body": "Stable", "assets": [
                {"name": "strimma-2.0.0.apk", "browser_download_url": "https://example.com/a.apk"}
            ]}
        ]"""
        val c = checker(betaMockClient(allReleasesJson = allStable))
        c.checkBeta()
        assertNull(c.betaUpdateInfo.value)
    }

    @Test
    fun `checkBeta ignores pre-release older than current`() = runTest {
        val oldBeta = """[
            {"tag_name": "v0.0.1-rc.1", "prerelease": true, "body": "Old beta", "assets": [
                {"name": "strimma-0.0.1-rc.1.apk", "browser_download_url": "https://example.com/a.apk"}
            ]}
        ]"""
        val c = checker(betaMockClient(allReleasesJson = oldBeta))
        c.checkBeta()
        assertNull(c.betaUpdateInfo.value)
    }

    @Test
    fun `checkBeta does not affect stable updateInfo`() = runTest {
        val c = checker(betaMockClient())
        c.checkBeta()
        assertNotNull(c.betaUpdateInfo.value)
        assertNull(c.updateInfo.value) // stable check was never run
    }

    // --- helpers ---

    private fun currentVersion(): String = com.psjostrom.strimma.BuildConfig.VERSION_NAME

    private fun release(
        tag: String = "v2.0.0",
        body: String = "Release notes",
        apkUrl: String = "https://github.com/test/strimma-2.0.0.apk"
    ): String = """{"tag_name": "$tag", "body": "$body", "assets": [
        {"name": "strimma-${tag.removePrefix("v")}.apk", "browser_download_url": "$apkUrl"}
    ]}"""

    companion object {
        private const val RELEASE_WITH_APK = """{"tag_name": "v2.0.0", "body": "Release notes", "assets": [
            {"name": "strimma-2.0.0.apk", "browser_download_url": "https://github.com/test/strimma-2.0.0.apk"}
        ]}"""

        private const val ALL_RELEASES_WITH_BETA = """[
            {"tag_name": "v2.0.0-rc.1", "prerelease": true, "body": "Beta notes", "assets": [
                {"name": "strimma-2.0.0-rc.1.apk", "browser_download_url": "https://example.com/beta.apk"}
            ]},
            {"tag_name": "v1.0.1", "prerelease": false, "body": "Stable", "assets": [
                {"name": "strimma-1.0.1.apk", "browser_download_url": "https://example.com/stable.apk"}
            ]}
        ]"""
    }
}
