package com.psjostrom.strimma.receiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug log with in-memory live feed and file persistence.
 * In-memory: last 50 entries for live UI.
 * File: one file per day in filesDir/logs/, pruned after 7 days.
 */
object DebugLog {

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var logsDir: File? = null

    fun init(filesDir: File) {
        logsDir = File(filesDir, "logs").also { it.mkdirs() }
        pruneOldLogs()
    }

    fun log(message: String) {
        val ts = sdf.format(Date())
        val entry = "$ts $message"
        _entries.value = (_entries.value + entry).takeLast(50)
        android.util.Log.d("StrimmaDebug", entry)

        logsDir?.let { dir ->
            try {
                val file = File(dir, "strimma-${dateFmt.format(Date())}.log")
                file.appendText("$entry\n")
            } catch (_: Exception) {
                // Don't crash if logging fails
            }
        }
    }

    // Keep for backward compat — callers that pass context
    fun log(context: android.content.Context? = null, message: String) = log(message)

    fun readLogFiles(): List<String> {
        val dir = logsDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("strimma-") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?.flatMap { file ->
                listOf("--- ${file.name} ---") + file.readLines().reversed()
            }
            ?: emptyList()
    }

    fun currentLogFile(): File? {
        val dir = logsDir ?: return null
        return File(dir, "strimma-${dateFmt.format(Date())}.log").takeIf { it.exists() }
    }

    private fun pruneOldLogs() {
        val dir = logsDir ?: return
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
