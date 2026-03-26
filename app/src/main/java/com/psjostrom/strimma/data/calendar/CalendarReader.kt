package com.psjostrom.strimma.data.calendar

import android.content.Context
import android.provider.CalendarContract
import com.psjostrom.strimma.receiver.DebugLog
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarInfo(val id: Long, val displayName: String)

@Singleton
class CalendarReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun getCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        val calendars = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, null, null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
                )
                while (cursor.moveToNext()) {
                    calendars.add(CalendarInfo(cursor.getLong(idIdx), cursor.getString(nameIdx)))
                }
            }
        } catch (e: SecurityException) {
            DebugLog.log("Calendar access denied: ${e.message}")
        }
        calendars
    }

    suspend fun getNextWorkout(calendarId: Long, lookaheadMs: Long): WorkoutEvent? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val end = now + lookaheadMs
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            )
            val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.DTSTART} > ? AND " +
                "${CalendarContract.Events.DTSTART} <= ?"
            val args = arrayOf(calendarId.toString(), now.toString(), end.toString())
            try {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection, selection, args,
                    "${CalendarContract.Events.DTSTART} ASC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val title = cursor.getString(
                            cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                        ) ?: ""
                        val startTime = cursor.getLong(
                            cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                        )
                        val endTime = cursor.getLong(
                            cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                        )
                        return@withContext WorkoutEvent(
                            title, startTime, endTime,
                            WorkoutCategory.fromTitle(title), calendarId
                        )
                    }
                }
            } catch (e: SecurityException) {
                DebugLog.log("Calendar access denied: ${e.message}")
            }
            null
        }
}
