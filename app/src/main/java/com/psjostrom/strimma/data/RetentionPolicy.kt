package com.psjostrom.strimma.data

import androidx.annotation.StringRes
import com.psjostrom.strimma.R

/**
 * User-controlled local retention for glucose readings, treatments, and exercise
 * sessions. The Story view scrolls month-by-month over arbitrarily old history,
 * so the default is [INDEFINITE] — any cap silently destroys data the view needs
 * to render past months. Users who care about disk usage or device-local privacy
 * can opt in to a finite window; the floor is 3 months so that "this month +
 * the previous full month" always remains intact.
 *
 * `days == null` means "do not prune".
 */
enum class RetentionPolicy(@StringRes val labelRes: Int, val days: Long?) {
    THREE_MONTHS(R.string.settings_retention_3_months, 90L),
    SIX_MONTHS(R.string.settings_retention_6_months, 180L),
    ONE_YEAR(R.string.settings_retention_1_year, 365L),
    FIVE_YEARS(R.string.settings_retention_5_years, 1825L),
    INDEFINITE(R.string.settings_retention_indefinite, null);

    /**
     * True if `this` keeps strictly less data than [other] — i.e. switching from
     * [other] to `this` would delete some history. Used by the Settings UI to
     * gate the change behind a confirmation dialog: a tighter selection silently
     * triggers an irreversible delete on the next retention tick (≤24h), so the
     * UI surfaces it as the destructive action it is.
     *
     * INDEFINITE is treated as the largest possible window — anything finite
     * is tighter than INDEFINITE.
     */
    fun isTighterThan(other: RetentionPolicy): Boolean {
        val ours = days ?: Long.MAX_VALUE
        val theirs = other.days ?: Long.MAX_VALUE
        return ours < theirs
    }
}
