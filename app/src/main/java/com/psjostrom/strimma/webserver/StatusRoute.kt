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
