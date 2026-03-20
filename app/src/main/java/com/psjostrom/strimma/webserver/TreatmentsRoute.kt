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
