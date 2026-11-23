package com.psjostrom.strimma.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.withIndex

/** How long connection settings must stay unchanged before a follower or a sync restarts. */
internal const val CONNECTION_SETTLE_MS = 3_000L

/**
 * Restarts a follower or treatment sync when its connection settings change. The settings
 * screens save the URL and credential fields on every keystroke, so a change runs [start]
 * only after [connection] has stopped changing for
 * [CONNECTION_SETTLE_MS]. Without the wait, typing a LibreLinkUp password would log in to
 * Abbott once per character. The first value runs [start] at once.
 *
 * [onChange] runs as soon as a later value arrives, before the wait. Treatment sync stops
 * there, because it reads the URL on every poll and must not poll a half-typed URL.
 */
internal suspend fun runPerConnection(
    connection: Flow<Any>,
    onChange: () -> Unit = {},
    start: suspend () -> Unit,
) {
    connection.withIndex().collectLatest { (index, _) ->
        if (index > 0) {
            onChange()
            delay(CONNECTION_SETTLE_MS)
        }
        start()
    }
}
