@file:Suppress("WildcardImport") // Ktor relies on extension functions across subpackages

package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.computeCurrentIOB
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.receiver.DebugLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_COUNT = 24
private const val MAX_SGV_COUNT = 1000
private const val MAX_TREATMENT_COUNT = 100
private const val HTTP_OK = 200
private const val TREATMENT_LOOKBACK_HOURS = 48
@Singleton
class LocalWebServer @Inject constructor(
    private val dao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val settings: SettingsRepository
) {
    companion object {
        const val PORT = 17580
        private const val STOP_GRACE_MS = 1000L
        private const val STOP_TIMEOUT_MS = 2000L
    }

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Suppress("TooGenericExceptionCaught") // Ktor can throw various exceptions during bind
    fun start() {
        if (server != null) return
        if (!isPortAvailable()) {
            DebugLog.log("Web server port $PORT already in use, skipping start")
            return
        }
        try {
            server = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
                intercept(ApplicationCallPipeline.Plugins) {
                    val remoteHost = call.request.local.remoteHost
                    if (!isLoopback(remoteHost)) {
                        val secret = settings.getWebServerSecret()
                        if (secret.isBlank()) {
                            call.respondText("Authentication required", status = HttpStatusCode.Forbidden)
                            finish()
                            return@intercept
                        }
                        val apiSecret = call.request.header("api-secret")
                        if (apiSecret == null || !checkApiSecret(apiSecret, secret)) {
                            call.respondText("Authentication failed", status = HttpStatusCode.Forbidden)
                            finish()
                            return@intercept
                        }
                        call.response.header("Access-Control-Allow-Origin", "*")
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
        } catch (e: Exception) {
            DebugLog.log("Web server failed to start: ${e.message}")
            server = null
        }
    }

    fun stop() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
        DebugLog.log("Web server stopped")
    }

    private fun isPortAvailable(): Boolean {
        return try {
            java.net.ServerSocket(PORT).use { true }
        } catch (_: Exception) {
            false
        }
    }
}

private fun Routing.sgvRoutes(dao: ReadingDao, treatmentDao: TreatmentDao, settings: SettingsRepository) {
    suspend fun handleSgv(call: ApplicationCall) {
        val count = (call.request.queryParameters["count"]?.toIntOrNull() ?: DEFAULT_COUNT).coerceIn(1, MAX_SGV_COUNT)
        val briefMode = call.request.queryParameters["brief_mode"]?.uppercase() == "Y"
            || call.request.queryParameters["brief_mode"] == "true"
        val stepsParam = call.request.queryParameters["steps"]?.toIntOrNull()
        val heartParam = call.request.queryParameters["heart"]?.toIntOrNull()

        val readings = dao.lastN(count)
        val unit = settings.glucoseUnit.first()
        val unitsHint = if (unit == GlucoseUnit.MMOL) "mmol" else "mgdl"

        val iob = if (settings.treatmentsSyncEnabled.first()) {
            computeCurrentIOB(
                true, settings.insulinType.first(), settings.customDIA.first(), treatmentDao
            )
        } else null

        val json = buildSgvJson(
            readings = readings,
            briefMode = briefMode,
            unitsHint = unitsHint,
            iob = iob,
            stepsResult = stepsParam?.let { HTTP_OK },
            heartResult = heartParam?.let { HTTP_OK }
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
        val count = (call.request.queryParameters["count"]?.toIntOrNull() ?: DEFAULT_COUNT).coerceIn(1, MAX_TREATMENT_COUNT)
        val since = System.currentTimeMillis() - TREATMENT_LOOKBACK_HOURS * MS_PER_HOUR
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
