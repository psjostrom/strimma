package com.psjostrom.strimma.webserver

import org.json.JSONObject

fun buildStatusJson(
    unitsHint: String,
    bgLow: Float,
    bgHigh: Float,
    workoutModeOn: Boolean
): String {
    return JSONObject().apply {
        put("settings", JSONObject().apply {
            put("units", unitsHint)
            put("thresholds", JSONObject().apply {
                put("bgLow", bgLow.toInt())
                put("bgHigh", bgHigh.toInt())
            })
            // Surfaces that the returned thresholds are workout-mode-affected so
            // downstream consumers (Garmin watchfaces) can render an indicator and
            // explain why their normal in-range band shifted mid-run.
            put("workoutModeOn", workoutModeOn)
        })
    }.toString()
}
