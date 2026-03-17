package com.psjostrom.strimma.receiver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple in-memory debug log visible in the UI.
 * Keeps last 50 entries. No persistence — cleared on process death.
 */
object DebugLog {

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(context: Context? = null, message: String) {
        val ts = sdf.format(Date())
        val entry = "$ts $message"
        _entries.value = (_entries.value + entry).takeLast(50)
        android.util.Log.d("StrimmaDebug", entry)
    }
}
