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

    private const val MAX_IN_MEMORY_ENTRIES = 50
    private const val LOG_RETENTION_DAYS = 7L
    private const val HOURS_PER_DAY = 24
    private const val MINUTES_PER_HOUR = 60
    private const val SECONDS_PER_MINUTE = 60
    private const val MS_PER_SECOND = 1000L

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
        _entries.value = (_entries.value + entry).takeLast(MAX_IN_MEMORY_ENTRIES)
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
    fun log(@Suppress("UNUSED_PARAMETER") context: android.content.Context? = null, message: String) = log(message)

    private const val MAX_FILE_ENTRIES = 500

    fun readLogFiles(): List<String> {
        val dir = logsDir ?: return emptyList()
        val result = mutableListOf<String>()
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("strimma-") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: return emptyList()

        for (file in files) {
            val lines = file.readLines()
            result.add("--- ${file.name} ---")
            for (line in lines.asReversed()) {
                result.add(line)
                if (result.size >= MAX_FILE_ENTRIES) return result
            }
        }
        return result
    }

    fun currentLogFile(): File? {
        val dir = logsDir ?: return null
        return File(dir, "strimma-${dateFmt.format(Date())}.log").takeIf { it.exists() }
    }

    private fun pruneOldLogs() {
        val dir = logsDir ?: return
        val cutoff = System.currentTimeMillis() - LOG_RETENTION_DAYS * HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MS_PER_SECOND
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
