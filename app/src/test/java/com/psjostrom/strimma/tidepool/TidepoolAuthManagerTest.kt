package com.psjostrom.strimma.tidepool

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import net.openid.appauth.connectivity.ConnectionBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
class TidepoolAuthManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Answers every token request with [status] and [body] from a local HTTP server. */
    private fun tokenServer(status: Int, body: String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun authManager(server: HttpServer, settings: SettingsRepository): TidepoolAuthManager {
        val port = server.address.port
        val toLocalServer = ConnectionBuilder { uri ->
            URL("http://127.0.0.1:$port${uri.path}").openConnection() as HttpURLConnection
        }
        return TidepoolAuthManager(context, settings, toLocalServer)
    }

    /** AppAuth delivers the token response on the main looper, so the test idles it while it waits. */
    private fun refresh(authManager: TidepoolAuthManager): String? {
        val result = CompletableFuture.supplyAsync { runBlocking { authManager.getValidAccessToken() } }
        val deadline = System.currentTimeMillis() + 10_000
        while (!result.isDone) {
            check(System.currentTimeMillis() < deadline) { "AppAuth did not answer the token request" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return result.get()
    }

    private fun settingsWithRefreshToken(): SettingsRepository =
        SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore()).apply {
            setTidepoolRefreshToken("refresh-token")
        }

    @Test
    fun `a rejected refresh token logs the user out`() {
        val server = tokenServer(400, """{"error":"invalid_grant","error_description":"Invalid refresh token"}""")
        try {
            val settings = settingsWithRefreshToken()
            val authManager = authManager(server, settings)

            assertNull(refresh(authManager))
            assertFalse(authManager.isLoggedIn())
            assertFalse(authManager.loggedIn.value)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a server error during refresh keeps the user logged in`() {
        val server = tokenServer(503, """{"error":"temporarily_unavailable"}""")
        try {
            val settings = settingsWithRefreshToken()
            val authManager = authManager(server, settings)

            assertNull(refresh(authManager))
            assertTrue(authManager.isLoggedIn())
            assertTrue(authManager.loggedIn.value)
        } finally {
            server.stop(0)
        }
    }
}
