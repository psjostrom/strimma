package com.psjostrom.strimma.data

enum class InsulinType(val label: String, val tauMinutes: Double) {
    FIASP("Fiasp", 55.0),
    LYUMJEV("Lyumjev", 50.0),
    NOVORAPID("NovoRapid / Humalog", 75.0),
    CUSTOM("Custom", 55.0);
}
