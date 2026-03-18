package com.psjostrom.strimma.data

enum class GlucoseSource(val label: String, val description: String) {
    COMPANION("Companion Mode", "Parse notifications from CGM apps"),
    XDRIP_BROADCAST("xDrip Broadcast", "Receive xDrip-compatible BG broadcasts"),
    NIGHTSCOUT_FOLLOWER("Nightscout Follower", "Follow a remote Nightscout server")
}
