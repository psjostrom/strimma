package com.psjostrom.strimma.data

enum class GlucoseSource(val label: String, val description: String) {
    CAMAPS_NOTIFICATION("CamAPS FX", "Parse CamAPS FX notifications"),
    ANY_CGM_NOTIFICATION("Any CGM App", "Parse notifications from known CGM apps"),
    XDRIP_BROADCAST("xDrip Broadcast", "Receive xDrip-compatible BG broadcasts")
}
