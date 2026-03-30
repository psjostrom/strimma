package com.psjostrom.strimma.data

enum class Direction(val arrow: String) {
    DoubleDown("⇊"),
    SingleDown("↓"),
    FortyFiveDown("↘"),
    Flat("→"),
    FortyFiveUp("↗"),
    SingleUp("↑"),
    DoubleUp("⇈"),
    NONE("?");

    companion object {
        fun parse(name: String): Direction =
            try { valueOf(name) } catch (_: Exception) { NONE }
    }
}
