package com.psjostrom.strimma.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibreLinkUpClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes glucose item with ValueInMgPerDl`() {
        val itemJson = """
            {
                "FactoryTimestamp": "3/31/2026 6:13:25 AM",
                "Timestamp": "3/31/2026 8:13:25 AM",
                "type": 1,
                "ValueInMgPerDl": 70,
                "TrendArrow": 3,
                "TrendMessage": null,
                "MeasurementColor": 1,
                "GlucoseUnits": 0,
                "Value": 3.9,
                "isHigh": false,
                "isLow": false
            }
        """.trimIndent()

        val item = json.decodeFromString<LluGlucoseItem>(itemJson)
        assertEquals(70, item.valueInMgPerDl)
        assertEquals(3, item.trendArrow)
        assertEquals("3/31/2026 6:13:25 AM", item.factoryTimestamp)
        assertFalse(item.isHigh)
        assertFalse(item.isLow)
    }

    @Test
    fun `deserializes connections response with new API format`() {
        val responseJson = """
            {
                "status": 0,
                "data": [{
                    "id": "abc-123",
                    "patientId": "patient-456",
                    "country": "SE",
                    "status": 2,
                    "firstName": "Test",
                    "lastName": "User",
                    "targetLow": 70,
                    "targetHigh": 180,
                    "glucoseMeasurement": {
                        "FactoryTimestamp": "3/31/2026 6:13:25 AM",
                        "Timestamp": "3/31/2026 8:13:25 AM",
                        "type": 1,
                        "ValueInMgPerDl": 120,
                        "TrendArrow": 4,
                        "TrendMessage": null,
                        "MeasurementColor": 1,
                        "GlucoseUnits": 0,
                        "Value": 6.7,
                        "isHigh": false,
                        "isLow": false
                    },
                    "glucoseItem": {
                        "FactoryTimestamp": "3/31/2026 6:13:25 AM",
                        "Timestamp": "3/31/2026 8:13:25 AM",
                        "type": 1,
                        "ValueInMgPerDl": 120,
                        "TrendArrow": 4,
                        "Value": 6.7,
                        "isHigh": false,
                        "isLow": false,
                        "MeasurementColor": 1
                    },
                    "patientDevice": {
                        "did": "device-789",
                        "dtid": 40068,
                        "v": "3.6.6"
                    },
                    "created": 1774897016
                }],
                "ticket": {
                    "token": "jwt-token-here",
                    "expires": 1790489642,
                    "duration": 15552000000
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<LluConnectionsResponse>(responseJson)
        assertEquals(0, response.status)
        assertEquals(1, response.data.size)

        val connection = response.data[0]
        assertEquals("patient-456", connection.patientId)
        assertEquals("Test", connection.firstName)

        val measurement = connection.glucoseMeasurement!!
        assertEquals(120, measurement.valueInMgPerDl)
        assertEquals(4, measurement.trendArrow)
    }

    @Test
    fun `deserializes graph response`() {
        val responseJson = """
            {
                "status": 0,
                "data": {
                    "connection": {
                        "patientId": "patient-456",
                        "firstName": "Test",
                        "lastName": "User",
                        "glucoseMeasurement": {
                            "FactoryTimestamp": "3/31/2026 6:13:25 AM",
                            "Timestamp": "3/31/2026 8:13:25 AM",
                            "ValueInMgPerDl": 95,
                            "TrendArrow": 3,
                            "Value": 5.3,
                            "isHigh": false,
                            "isLow": false,
                            "MeasurementColor": 1
                        }
                    },
                    "graphData": [
                        {
                            "FactoryTimestamp": "3/30/2026 6:52:24 PM",
                            "Timestamp": "3/30/2026 8:52:24 PM",
                            "ValueInMgPerDl": 110,
                            "TrendArrow": 3,
                            "Value": 6.1,
                            "isHigh": false,
                            "isLow": false,
                            "MeasurementColor": 1
                        },
                        {
                            "FactoryTimestamp": "3/30/2026 6:57:25 PM",
                            "Timestamp": "3/30/2026 8:57:25 PM",
                            "ValueInMgPerDl": 55,
                            "TrendArrow": 4,
                            "Value": 3.1,
                            "isHigh": false,
                            "isLow": true,
                            "MeasurementColor": 2
                        }
                    ],
                    "activeSensors": [{"sensor": {"sn": "ABC123"}}]
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<LluGraphResponse>(responseJson)
        val data = response.data
        assertEquals(2, data.graphData.size)
        assertEquals(110, data.graphData[0].valueInMgPerDl)
        assertEquals(55, data.graphData[1].valueInMgPerDl)
        assertEquals(true, data.graphData[1].isLow)
        assertEquals(95, data.connection.glucoseMeasurement!!.valueInMgPerDl)
    }

    @Test
    fun `deserializes login response with redirect`() {
        val responseJson = """
            {"status": 0, "data": {"authTicket": {"token": "", "expires": 0}, "user": {"id": ""}, "redirect": true, "region": "eu"}}
        """.trimIndent()

        val response = json.decodeFromString<LluLoginResponse>(responseJson)
        assertEquals(true, response.data.redirect)
        assertEquals("eu", response.data.region)
    }
}
