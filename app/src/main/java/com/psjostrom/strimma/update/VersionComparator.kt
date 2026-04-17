package com.psjostrom.strimma.update

object VersionComparator {

    /**
     * Parses a version string like "1.1.0" or "v1.1.0-rc.2" into a list of ints [1, 1, 0].
     * Pre-release suffixes (everything after the first '-') are stripped.
     * Returns empty list for unparseable input.
     */
    fun parse(version: String): List<Int> {
        val cleaned = version.removePrefix("v").split("-").first().trim()
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(".").mapNotNull { it.toIntOrNull() }
    }

    /** Returns true if the version contains a pre-release suffix (e.g. "-rc.1", "-beta.2"). */
    fun isPreRelease(version: String): Boolean {
        return version.removePrefix("v").contains("-")
    }

    /**
     * Extracts the pre-release sequence number (e.g. 2 from "1.0.0-rc.2").
     * Returns 0 if no number is found or the version is not a pre-release.
     */
    fun preReleaseNumber(version: String): Int {
        val cleaned = version.removePrefix("v")
        val dashIndex = cleaned.indexOf('-')
        if (dashIndex < 0) return 0
        val suffix = cleaned.substring(dashIndex + 1) // "rc.2"
        return suffix.split(".").lastOrNull()?.toIntOrNull() ?: 0
    }

    /**
     * Returns true if [current] is older than [other].
     *
     * Compares major.minor.patch left-to-right. When base versions are equal:
     * - pre-release is older than stable (1.1.0-rc.1 < 1.1.0)
     * - lower RC number is older (1.1.0-rc.1 < 1.1.0-rc.2)
     * - stable is never older than pre-release of the same version
     */
    fun isOlderThan(current: String, other: String): Boolean {
        val a = parse(current)
        val b = parse(other)
        if (a.isEmpty() || b.isEmpty()) return false
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av < bv) return true
            if (av > bv) return false
        }
        // Base versions are equal — check pre-release ordering
        val currentPre = isPreRelease(current)
        val otherPre = isPreRelease(other)
        return when {
            currentPre && !otherPre -> true // rc < stable
            !currentPre && otherPre -> false // stable >= rc
            currentPre && otherPre -> preReleaseNumber(current) < preReleaseNumber(other)
            else -> false // both stable, same version
        }
    }
}
