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
}

interface ReadingUploader {
    fun onNewReading()
}
