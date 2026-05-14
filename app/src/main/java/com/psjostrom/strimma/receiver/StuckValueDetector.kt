package com.psjostrom.strimma.receiver

import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Per-package detector for stuck-value reposts during sensor glitches.
 *
 * Source CGM apps (notably CamAPS) sometimes alternate "---" notifications with reposts of
 * the last-known glucose value when the sensor stops delivering. Each repost carries a fresh
 * `notification.when`, so timestamp-based defenses don't help; bucket dedup in the pipeline
 * doesn't catch them either because consecutive reposts land in different sample buckets.
 *
 * The detector uses the actual differentiator between a real plateau and a stuck repost:
 * a "---" notification observed within a recent suspicion window. After [recordNoValue] is
 * called, [isStuck] returns true for any subsequent same-value reading until either the
 * suspicion expires or a different value arrives via [recordAccept] (sensor recovered).
 *
 * **Clock model:** the in-memory window comparison uses [SystemClock.elapsedRealtime] so it
 * is immune to wall-clock changes (NTP corrections, manual time changes). For persistence
 * across listener rebinds — which Android does routinely on boot, app update, and memory
 * pressure — the wall-clock timestamp is also written to [prefs]. On load, a persisted
 * timestamp older than [PERSISTENCE_MAX_AGE_MS] (or in the future) is discarded.
 *
 * **State persistence rationale:** `NotificationListenerService` instance state is wiped on
 * every rebind. Without persistence the detector would be empty exactly when it matters
 * most — the rebind itself often happens during a sensor disconnect (app updates, memory
 * pressure correlate with degraded states). The first stuck-value repost after a rebind
 * would always slip through. SharedPreferences keeps the detector aware across the gap.
 */
internal class StuckValueDetector(
    private val prefs: SharedPreferences,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private data class State(val lastSgv: Int?, val noValueAtElapsed: Long?)

    private val states = mutableMapOf<String, State>()

    /** Records a "---" notification — arms the suspicion window for [packageName]. */
    fun recordNoValue(packageName: String) {
        val state = stateFor(packageName).copy(noValueAtElapsed = elapsed())
        states[packageName] = state
        prefs.edit().putLong(noValueKey(packageName), now()).apply()
    }

    /**
     * Records that a parsed [sgv] was forwarded for [packageName]. Clears the suspicion
     * window — accepting a fresh reading is the definition of "the sensor recovered."
     */
    fun recordAccept(packageName: String, sgv: Int) {
        states[packageName] = State(lastSgv = sgv, noValueAtElapsed = null)
        prefs.edit()
            .putInt(sgvKey(packageName), sgv)
            .remove(noValueKey(packageName))
            .apply()
    }

    /**
     * Returns true when [sgv] for [packageName] looks like a stuck repost — the value
     * matches the last forwarded SGV AND a "---" notification was observed within
     * [windowMs] elapsed time.
     */
    fun isStuck(packageName: String, sgv: Int, windowMs: Long): Boolean {
        val state = stateFor(packageName)
        if (state.lastSgv != sgv) return false
        val noValueAt = state.noValueAtElapsed ?: return false
        val age = elapsed() - noValueAt
        return age in 0..windowMs
    }

    private fun stateFor(packageName: String): State =
        states.getOrPut(packageName) { loadFromPrefs(packageName) }

    private fun loadFromPrefs(packageName: String): State {
        val sgv = prefs.getInt(sgvKey(packageName), SGV_NOT_PRESENT).takeIf { it != SGV_NOT_PRESENT }
        val noValueAtWall = prefs.getLong(noValueKey(packageName), 0L).takeIf { it > 0 }
        // Bridge persisted wall-clock to in-memory elapsed time. A persisted entry older than
        // PERSISTENCE_MAX_AGE_MS (or stamped in the future from a backwards clock jump) is
        // discarded — better to lose one suspicion window than to honor a stale one.
        val noValueAtElapsed = noValueAtWall?.let { wall ->
            val age = now() - wall
            if (age in 0..PERSISTENCE_MAX_AGE_MS) elapsed() - age else null
        }
        return State(lastSgv = sgv, noValueAtElapsed = noValueAtElapsed)
    }

    private fun sgvKey(packageName: String) = "stuck_sgv_$packageName"
    private fun noValueKey(packageName: String) = "stuck_nva_wall_$packageName"

    companion object {
        // Persisted no-value entries older than this on load are discarded as stale.
        private const val PERSISTENCE_MAX_AGE_MS = 30L * 60 * 1000

        // Sentinel for "no SGV ever forwarded for this package" — distinct from any valid mg/dL.
        private const val SGV_NOT_PRESENT = -1
    }
}
