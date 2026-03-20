package com.psjostrom.strimma.webserver

import com.psjostrom.strimma.data.Treatment
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TreatmentsRouteTest {

    private fun treatment(
        id: String, createdAt: Long, eventType: String,
        insulin: Double? = null, carbs: Double? = null, enteredBy: String? = null
    ) = Treatment(
        id = id, createdAt = createdAt, eventType = eventType,
        insulin = insulin, carbs = carbs, basalRate = null, duration = null,
        enteredBy = enteredBy, fetchedAt = System.currentTimeMillis()
    )

    @Test
    fun `builds treatments JSON array`() {
        val treatments = listOf(
            treatment("abc", 1715263007650L, "Meal Bolus", insulin = 2.5, carbs = 45.0, enteredBy = "CamAPS")
        )
        val json = buildTreatmentsJson(treatments)
        val arr = JSONArray(json)
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals("abc", obj.getString("_id"))
        assertEquals("Meal Bolus", obj.getString("eventType"))
        assertEquals(2.5, obj.getDouble("insulin"), 0.01)
        assertEquals(45.0, obj.getDouble("carbs"), 0.01)
        assertEquals("CamAPS", obj.getString("enteredBy"))
        assertTrue(obj.getString("created_at").contains("T"))
        assertTrue(obj.getString("created_at").endsWith("Z"))
    }

    @Test
    fun `empty treatments produces empty array`() {
        val json = buildTreatmentsJson(emptyList())
        assertEquals("[]", json)
    }

    @Test
    fun `null fields are omitted`() {
        val treatments = listOf(
            treatment("abc", 1715263007650L, "Correction Bolus", insulin = 1.0)
        )
        val json = buildTreatmentsJson(treatments)
        val obj = JSONArray(json).getJSONObject(0)
        assertFalse(obj.has("carbs"))
        assertFalse(obj.has("enteredBy"))
    }
}
