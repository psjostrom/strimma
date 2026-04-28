package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.GlucoseReading

/**
 * Side-effect contracts the [ReadingPipeline] invokes after a reading is stored. Defined as
 * narrow interfaces so the pipeline can be exercised in tests with hand-written fakes
 * instead of full [com.psjostrom.strimma.network.NightscoutPusher] /
 * [com.psjostrom.strimma.tidepool.TidepoolUploader] instances and their transitive
 * dependencies (HTTP client, settings store, alert manager, …).
 */
interface ReadingPusher {
    fun pushReading(reading: GlucoseReading)

    /**
     * Cancel any in-flight push for [ts]. Called when a stored row is being deleted
     * and replaced by a same-bucket value change, so the superseded value never
     * reaches the upstream server. No-op if no push is in flight or has already
     * completed.
     */
    fun cancelPushFor(ts: Long)
}

interface ReadingUploader {
    fun onNewReading()
}
