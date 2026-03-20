package com.psjostrom.strimma.webserver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatusRouteTest {

    @Test
    fun `builds status JSON with mmol thresholds converted to mgdl`() {
        val json = buildStatusJson(unitsHint = "mmol", bgLowMmol = 4.0f, bgHighMmol = 10.0f)
        val root = JSONObject(json)
        val settings = root.getJSONObject("settings")
        assertEquals("mmol", settings.getString("units"))
        val thresholds = settings.getJSONObject("thresholds")
        assertEquals(72, thresholds.getInt("bgLow"))
        assertEquals(180, thresholds.getInt("bgHigh"))
    }

    @Test
    fun `builds status JSON with mgdl units`() {
        val json = buildStatusJson(unitsHint = "mgdl", bgLowMmol = 3.9f, bgHighMmol = 10.0f)
        val root = JSONObject(json)
        assertEquals("mgdl", root.getJSONObject("settings").getString("units"))
    }
}
