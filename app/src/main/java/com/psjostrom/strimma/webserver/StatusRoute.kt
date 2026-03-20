package com.psjostrom.strimma.webserver

import org.json.JSONObject

fun buildStatusJson(unitsHint: String, bgLow: Float, bgHigh: Float): String {
    return JSONObject().apply {
        put("settings", JSONObject().apply {
            put("units", unitsHint)
            put("thresholds", JSONObject().apply {
                put("bgLow", bgLow.toInt())
                put("bgHigh", bgHigh.toInt())
            })
        })
    }.toString()
}
