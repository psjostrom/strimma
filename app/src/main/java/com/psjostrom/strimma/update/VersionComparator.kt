package com.psjostrom.strimma.update

object VersionComparator {

    /**
     * Parses a version string like "0.9.1" or "v0.9.1" into a list of ints [0, 9, 1].
     * Returns empty list for unparseable input.
     */
    fun parse(version: String): List<Int> {
        val cleaned = version.removePrefix("v").trim()
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * Returns true if [current] is older than [other].
     * Compares major.minor.patch left-to-right. Missing segments treated as 0.
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
        return false
    }
}
