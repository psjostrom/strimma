package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.calendar.MetabolicProfile

object CategoryStatsCalculator {

    private const val MIN_SESSIONS = 3
    private const val MS_PER_MINUTE = 60_000.0

    fun computeByCategory(
        data: List<Pair<StoredExerciseSession, ExerciseBGContext>>,
        bgLowMgdl: Double
    ): List<CategoryStats> {
        val withEntry = data.filter { it.second.entryBG != null }

        return withEntry
            .groupBy { ExerciseCategory.fromHCType(it.first.type) }
            .filter { it.value.size >= MIN_SESSIONS }
            .map { (category, sessions) ->
                buildStats(category, null, sessions, bgLowMgdl)
            }
            .sortedByDescending { it.sessionCount }
    }

    fun computeByProfile(
        data: List<Pair<StoredExerciseSession, ExerciseBGContext>>,
        bgLowMgdl: Double,
        maxHR: Int?
    ): List<CategoryStats> {
        val withEntry = data.filter { it.second.entryBG != null }

        return withEntry
            .groupBy { resolveProfile(it.first, it.second, maxHR) }
            .filter { it.value.size >= MIN_SESSIONS }
            .map { (profile, sessions) ->
                // Use OTHER as placeholder category for profile view
                buildStats(ExerciseCategory.OTHER, profile, sessions, bgLowMgdl)
            }
            .sortedByDescending { it.sessionCount }
    }

    fun resolveProfile(
        session: StoredExerciseSession,
        context: ExerciseBGContext,
        maxHR: Int?
    ): MetabolicProfile {
        val category = ExerciseCategory.fromHCType(session.type)
        if (category == ExerciseCategory.STRENGTH || category == ExerciseCategory.CLIMBING) {
            return MetabolicProfile.RESISTANCE
        }
        if (maxHR != null && maxHR > 0 && context.avgHR != null && context.avgHR > 0) {
            val fraction = context.avgHR.toDouble() / maxHR
            if (fraction >= 0.80) return MetabolicProfile.HIGH_INTENSITY
        }
        return category.defaultMetabolicProfile
    }

    private fun buildStats(
        category: ExerciseCategory,
        profile: MetabolicProfile?,
        sessions: List<Pair<StoredExerciseSession, ExerciseBGContext>>,
        bgLowMgdl: Double
    ): CategoryStats {
        val contexts = sessions.map { it.second }
        val entryBGs = contexts.mapNotNull { it.entryBG }
        val minBGs = contexts.mapNotNull { it.minBG }
        val dropRates = contexts.flatMap { it.dropPer10Min }
        val durations = sessions.map {
            ((it.first.endTime - it.first.startTime) / MS_PER_MINUTE).toInt()
        }
        val postNadirs = contexts.mapNotNull { it.lowestBG }
        val postHighests = contexts.mapNotNull { it.highestBG }

        val hypoCount = contexts.count { ctx ->
            ctx.minBG != null && ctx.minBG < bgLowMgdl
        }
        val postHypoCount = contexts.count { it.postExerciseHypo }

        val bandGroups = sessions
            .filter { it.second.entryBG != null }
            .groupBy { BGBand.fromBG(it.second.entryBG!!, bgLowMgdl) }

        val statsByBand = bandGroups
            .filter { it.value.size >= MIN_SESSIONS }
            .mapValues { (_, bandSessions) ->
                val bc = bandSessions.map { it.second }
                BandStats(
                    sessionCount = bc.size,
                    avgMinBG = bc.mapNotNull { it.minBG }.let { if (it.isEmpty()) 0.0 else it.average() },
                    avgDropRate = bc.flatMap { it.dropPer10Min }.let { if (it.isEmpty()) 0.0 else it.average() },
                    hypoRate = bc.count { c ->
                        c.minBG != null && c.minBG < bgLowMgdl
                    }.toDouble() / bc.size,
                    avgPostNadir = bc.mapNotNull { it.lowestBG }.let { if (it.isEmpty()) null else it.average() }
                )
            }

        return CategoryStats(
            category = category,
            metabolicProfile = profile,
            sessionCount = sessions.size,
            avgEntryBG = if (entryBGs.isEmpty()) 0.0 else entryBGs.average(),
            avgMinBG = if (minBGs.isEmpty()) 0.0 else minBGs.average(),
            avgDropRate = if (dropRates.isEmpty()) 0.0 else dropRates.average(),
            avgDurationMin = if (durations.isEmpty()) 0 else durations.average().toInt(),
            hypoCount = hypoCount,
            hypoRate = if (sessions.isEmpty()) 0.0 else hypoCount.toDouble() / sessions.size,
            avgPostNadir = if (postNadirs.isEmpty()) null else postNadirs.average(),
            avgPostHighest = if (postHighests.isEmpty()) null else postHighests.average(),
            postHypoCount = postHypoCount,
            statsByEntryBand = statsByBand
        )
    }
}
