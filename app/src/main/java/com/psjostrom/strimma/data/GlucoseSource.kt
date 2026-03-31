package com.psjostrom.strimma.data

import androidx.annotation.StringRes
import com.psjostrom.strimma.R

enum class GlucoseSource(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    COMPANION(R.string.settings_source_companion_label, R.string.settings_source_companion_desc),
    XDRIP_BROADCAST(R.string.settings_source_xdrip_label, R.string.settings_source_xdrip_desc),
    NIGHTSCOUT_FOLLOWER(R.string.settings_source_follower_label, R.string.settings_source_follower_desc),
    LIBRELINKUP(R.string.settings_source_llu_label, R.string.settings_source_llu_desc)
}
