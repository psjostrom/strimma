package com.psjostrom.strimma.data

import com.psjostrom.strimma.receiver.XdripBroadcastReceiver
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorIntervalsTest {

    private val oneMin = 60_000L
    private val threeMin = 180_000L
    private val fiveMin = 300_000L

    @Test
    fun `eversense packages report 5-min sample period`() {
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.senseonics.androidapp"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.senseonics.gen12androidapp"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.senseonics.eversense365.us"))
    }

    @Test
    fun `dexcom g6 g7 stelo report 5-min sample period`() {
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.dexcom.g6"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.dexcom.g7"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.dexcom.stelo"))
    }

    @Test
    fun `medtronic packages report 5-min sample period`() {
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.medtronic.diabetes.guardian"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.medtronic.diabetes.simplera.eu"))
    }

    @Test
    fun `aidex packages report 5-min sample period`() {
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.microtech.aidexx"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.microtech.aidexx.mgdl"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.microtech.aidexx.linxneo.mmoll"))
    }

    @Test
    fun `diabox reports 1-min sample period`() {
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.outshineiot.diabox"))
    }

    @Test
    fun `freestyle libre 3 reports 1-min sample period`() {
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.freestylelibre3.app"))
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.freestylelibre3.app.de"))
    }

    @Test
    fun `sensor-agnostic middleware falls back to 1-min default`() {
        // CamAPS FX (Libre or Dexcom), Juggluco (any), xDrip+ (any), AAPS, etc. are
        // intentionally NOT in the table — they relay arbitrary sensors, so the package
        // name does not determine the sample period. Defaulting to 1 min avoids dropping
        // any real reading from the fastest-cadence sensor they might be paired with.
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.camdiab.fx_alert.mgdl"))
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.camdiab.fx_alert.mmoll"))
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("tk.glucodata"))
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.eveningoutpost.dexdrip"))
    }

    @Test
    fun `xdrip broadcast tag falls back to 1-min default`() {
        // xDrip-style broadcasts originate from sensor-agnostic middleware (Juggluco,
        // AAPS, GlucoDataHandler), so the broadcast tag is not in the intervals table.
        // The constant lives on XdripBroadcastReceiver where it's emitted, not on
        // SensorIntervals — verify the default fallback is what we expect.
        assertEquals(oneMin, SensorIntervals.samplePeriodMs(XdripBroadcastReceiver.SOURCE_TAG))
    }

    @Test
    fun `ottai packages report 5-min sample period`() {
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.ottai.seas"))
        assertEquals(fiveMin, SensorIntervals.samplePeriodMs("com.ottai.tag"))
    }

    @Test
    fun `sinocare ican family reports 3-min sample period`() {
        assertEquals(threeMin, SensorIntervals.samplePeriodMs("com.sinocare.cgm.ce"))
        assertEquals(threeMin, SensorIntervals.samplePeriodMs("com.sinocare.ican.health.ce"))
        assertEquals(threeMin, SensorIntervals.samplePeriodMs("com.sinocare.ican.health.ru"))
    }

    @Test
    fun `suswel reports 1-min sample period`() {
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.suswel.ai"))
    }

    @Test
    fun `glucotech falls back to 1-min default - no published spec`() {
        // Cadence unverified; xDrip+ catalogues the package but doesn't document the
        // cadence, and the Play Store listing 404s. Pre-PR behavior (1-min default) is
        // the conservative choice until the manufacturer publishes a spec.
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.glucotech.app.android"))
    }

    @Test
    fun `unknown package falls back to 1-min default`() {
        assertEquals(oneMin, SensorIntervals.samplePeriodMs("com.unknown.app"))
    }

    @Test
    fun `null source falls back to 1-min default`() {
        assertEquals(oneMin, SensorIntervals.samplePeriodMs(null))
    }

}
