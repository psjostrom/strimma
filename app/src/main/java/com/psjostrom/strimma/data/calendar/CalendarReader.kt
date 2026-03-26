package com.psjostrom.strimma.data.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.psjostrom.strimma.receiver.DebugLog
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
                    val cal = CalendarInfo(cursor.getLong(idIdx), cursor.getString(nameIdx))
                    calendars.add(cal)
                    DebugLog.log("Calendar: id=${cal.id} name=${cal.displayName}")
                }
            }
        } catch (e: SecurityException) {
            DebugLog.log("Calendar access denied: ${e.message}")
        }
        DebugLog.log("CalendarReader: found ${calendars.size} calendars")
        calendars
    }

    suspend fun getUpcomingWorkouts(calendarId: Long, lookaheadMs: Long): List<WorkoutEvent> =
        withContext(Dispatchers.IO) {
            val events = mutableListOf<WorkoutEvent>()
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
                    val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                    val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                    val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(titleIdx) ?: ""
                        val startTime = cursor.getLong(startIdx)
                        val endTime = cursor.getLong(endIdx)
                        events.add(
                            WorkoutEvent(title, startTime, endTime, WorkoutCategory.fromTitle(title), calendarId)
                        )
                    }
                }
            } catch (e: SecurityException) {
                DebugLog.log("Calendar access denied: ${e.message}")
            }
            events
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

    fun requestCalendarSync() {
        val projection = arrayOf(
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        val accounts = mutableSetOf<Account>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val typeIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
                while (cursor.moveToNext()) {
                    accounts.add(Account(cursor.getString(nameIdx), cursor.getString(typeIdx)))
                }
            }
        } catch (e: SecurityException) {
            DebugLog.log("Calendar sync request failed: ${e.message}")
            return
        }

        for (account in accounts) {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
        }
        DebugLog.log("CalendarReader: requested sync for ${accounts.size} account(s)")
    }

    fun observeCalendars(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(
            CalendarContract.Calendars.CONTENT_URI, true, observer
        )
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }
}
