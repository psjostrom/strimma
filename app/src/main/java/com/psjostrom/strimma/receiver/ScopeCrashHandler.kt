package com.psjostrom.strimma.receiver

import kotlinx.coroutines.CoroutineExceptionHandler

private const val SCOPE_CRASH_LOG_LENGTH = 80

/**
 * Coroutine exception handler factory for service-owned scopes. Logs uncaught
 * throwables to [DebugLog] instead of letting them propagate to the JVM /
 * Android default uncaught handler — which on Main = process death and a
 * foreground-service restart loop.
 *
 * Use as a context element on every scope a service owns:
 * ```
 * CoroutineScope(SupervisorJob() + dispatcher + scopeCrashHandler("Service"))
 * ```
 *
 * The leaf catches in network / DB code remain the primary mechanism — this
 * is the belt against future code added without one.
 */
internal fun scopeCrashHandler(scopeName: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        DebugLog.log("$scopeName scope uncaught: ${t.javaClass.simpleName}: ${t.message?.take(SCOPE_CRASH_LOG_LENGTH)}")
    }
