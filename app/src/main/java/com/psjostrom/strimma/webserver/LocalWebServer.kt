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
