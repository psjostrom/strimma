package com.psjostrom.strimma.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.ExecutorService

/**
 * Advances virtual time and waits for async executors to complete.
 * Room dispatches to [roomExecutor] (drained via submit/get).
 * DataStore and Ktor's MockEngine dispatch to Dispatchers.IO (brief sleep between rounds).
 * Uses advanceTimeBy (not advanceUntilIdle) to avoid hanging on a follower's infinite polling loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settleFollower(roomExecutor: ExecutorService) {
    repeat(10) {
        advanceTimeBy(100)
        runCurrent()
        roomExecutor.submit {}.get()
        Thread.sleep(10)
    }
}
