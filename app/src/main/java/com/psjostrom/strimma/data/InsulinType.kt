package com.psjostrom.strimma.data

import androidx.annotation.StringRes
import com.psjostrom.strimma.R

enum class InsulinType(@StringRes val labelRes: Int, val tauMinutes: Double) {
    FIASP(R.string.settings_treatments_fiasp, 55.0),
    LYUMJEV(R.string.settings_treatments_lyumjev, 50.0),
    NOVORAPID(R.string.settings_treatments_novorapid, 75.0),
    CUSTOM(R.string.settings_treatments_custom, 55.0);
}
