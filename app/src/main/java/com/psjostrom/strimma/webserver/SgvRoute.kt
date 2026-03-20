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
