package com.psjostrom.strimma.network

import com.psjostrom.strimma.receiver.DebugLog
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_LOG_LENGTH = 80

/**
 * Wraps an HTTP/network call with the project's standard catch policy:
 *
 *  - `CancellationException` propagates so structured concurrency stays intact.
 *  - Any other `Exception` is logged (with class name and message) and converted
 *    to a return value via [onError].
 *
 * The broad catch is mandatory, not a smell. Ktor's CIO engine surfaces
 * airplane-mode DNS failures as `UnresolvedAddressException`, which extends
 * `IllegalArgumentException` rather than `IOException` — a narrower catch lets
 * these escape the foreground-service scope and crash the process.
 *
 * The exception class name is included in the log so a real programming bug
 * (NPE, ISE, IAE) is distinguishable from a transient network failure when
 * triaging from `DebugLog`.
 *
 * Use [onError] to return a default (`{ false }`, `{ null }`,
 * `{ Result.failure(it) }`) or to rethrow (`{ throw it }`).
 */
internal suspend inline fun <T> withNetworkBoundary(
    label: String,
    onError: (Exception) -> T,
    block: () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (
    @Suppress("TooGenericExceptionCaught")
    e: Exception
) {
    DebugLog.log("$label error [${e.javaClass.simpleName}]: ${e.message?.take(MAX_LOG_LENGTH)}")
    onError(e)
}
