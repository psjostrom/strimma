package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.GlucoseReading
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SgvRouteTest {

    private fun reading(ts: Long, sgv: Int, mmol: Double, direction: String, deltaMmol: Double?) =
        GlucoseReading(ts = ts, sgv = sgv, mmol = mmol, direction = direction, deltaMmol = deltaMmol, pushed = 1)

    @Test
    fun `builds JSON array with required fields`() {
        val readings = listOf(reading(1000L, 168, 9.3, "FortyFiveUp", 0.1))
        val json = buildSgvJson(readings, briefMode = false, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        val arr = JSONArray(json)
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals(1000L, obj.getLong("date"))
        assertEquals(168, obj.getInt("sgv"))
        assertEquals("FortyFiveUp", obj.getString("direction"))
        assertTrue(obj.has("delta"))
        assertEquals("sgv", obj.getString("type"))
        assertEquals("Strimma", obj.getString("device"))
    }

    @Test
    fun `brief mode omits verbose fields`() {
        val readings = listOf(reading(1000L, 168, 9.3, "Flat", 0.0))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        val obj = JSONArray(json).getJSONObject(0)
        assertFalse(obj.has("_id"))
        assertFalse(obj.has("device"))
        assertFalse(obj.has("type"))
        assertFalse(obj.has("dateString"))
        assertFalse(obj.has("sysTime"))
        assertTrue(obj.has("date"))
        assertTrue(obj.has("sgv"))
        assertTrue(obj.has("direction"))
    }

    @Test
    fun `first entry has units_hint`() {
        val readings = listOf(reading(2000L, 100, 5.5, "Flat", 0.0), reading(1000L, 90, 5.0, "Flat", -0.1))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mgdl", iob = null, stepsResult = null, heartResult = null)
        val arr = JSONArray(json)
        assertEquals("mgdl", arr.getJSONObject(0).getString("units_hint"))
        assertFalse(arr.getJSONObject(1).has("units_hint"))
    }

    @Test
    fun `first entry includes iob when provided`() {
        val readings = listOf(reading(1000L, 168, 9.3, "Flat", 0.0))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = 2.5, stepsResult = null, heartResult = null)
        assertEquals(2.5, JSONArray(json).getJSONObject(0).getDouble("iob"), 0.01)
    }

    @Test
    fun `first entry includes steps and heart results when provided`() {
        val readings = listOf(reading(1000L, 168, 9.3, "Flat", 0.0))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = 200, heartResult = 200)
        val obj = JSONArray(json).getJSONObject(0)
        assertEquals(200, obj.getInt("steps_result"))
        assertEquals(200, obj.getInt("heart_result"))
    }

    @Test
    fun `delta converted to mgdl`() {
        val readings = listOf(reading(1000L, 168, 9.3, "FortyFiveUp", 0.5))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        assertEquals(9.0, JSONArray(json).getJSONObject(0).getDouble("delta"), 0.2)
    }

    @Test
    fun `null delta produces zero`() {
        val readings = listOf(reading(1000L, 168, 9.3, "NONE", null))
        val json = buildSgvJson(readings, briefMode = true, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        assertEquals(0.0, JSONArray(json).getJSONObject(0).getDouble("delta"), 0.001)
    }

    @Test
    fun `empty readings produces empty array`() {
        val json = buildSgvJson(emptyList(), briefMode = false, unitsHint = "mmol", iob = null, stepsResult = null, heartResult = null)
        assertEquals("[]", json)
    }
}
