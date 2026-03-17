package com.psjostrom.strimma.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.network.SpringaPusher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AidexReceiver(
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val pusher: SpringaPusher,
    private val onNewReading: (GlucoseReading) -> Unit
) : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.microtechmd.cgms.aidex.action.BgEstimate"
        private const val EXTRA_TIME = "com.microtechmd.cgms.aidex.Time"
        private const val EXTRA_BG_TYPE = "com.microtechmd.cgms.aidex.BgType"
        private const val EXTRA_BG_VALUE = "com.microtechmd.cgms.aidex.BgValue"
        private const val EXTRA_SENSOR_ID = "com.microtechmd.cgms.aidex.SensorId"
        private const val MG_DL_TO_MMOL = 18.0182
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val extras = intent.extras ?: return
        val timestamp = extras.getLong(EXTRA_TIME, 0L)
        val bgType = extras.getString(EXTRA_BG_TYPE) ?: return
        val bgValue = extras.getDouble(EXTRA_BG_VALUE, 0.0)

        if (timestamp == 0L || bgValue <= 0.0) return

        val sgvMgdl: Double = if (bgType == "mmol/l") bgValue * MG_DL_TO_MMOL else bgValue
        val mmol: Double = if (bgType == "mmol/l") bgValue else bgValue / MG_DL_TO_MMOL

        scope.launch {
            // Deduplicate
            val existing = dao.lastN(1)
            if (existing.isNotEmpty() && existing[0].ts == timestamp) return@launch

            // Get recent readings for direction computation
            val recentReadings = dao.since(timestamp - 15 * 60 * 1000)

            // Build a temporary reading for direction computation
            val tempReading = GlucoseReading(
                ts = timestamp,
                sgv = sgvMgdl.toInt(),
                mmol = Math.round(mmol * 10.0) / 10.0,
                direction = "NONE",
                deltaMmol = null,
                pushed = 0
            )

            val (direction, deltaMmol) = directionComputer.compute(recentReadings, tempReading)

            val reading = tempReading.copy(
                direction = direction.name,
                deltaMmol = deltaMmol?.let { Math.round(it * 10.0) / 10.0 }
            )

            dao.insert(reading)
            pusher.pushReading(reading)
            onNewReading(reading)
        }
    }
}
