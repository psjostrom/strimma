package com.psjostrom.strimma.testutil

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract

class FakeCalendarProvider : ContentProvider() {

    private val events = mutableListOf<ContentValues>()

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        values?.let { events.add(ContentValues(it)) }
        return uri
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cols = projection ?: arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_ID
        )
        val cursor = MatrixCursor(cols)

        val filtered = if (selection != null && selectionArgs != null) {
            filterEvents(selection, selectionArgs)
        } else {
            events
        }

        val sorted = if (sortOrder != null && sortOrder.contains("dtstart", ignoreCase = true)) {
            filtered.sortedBy { it.getAsLong(CalendarContract.Events.DTSTART) ?: 0L }
        } else {
            filtered
        }

        for (event in sorted) {
            val row = cols.map { col -> event.get(col) }
            cursor.addRow(row)
        }
        return cursor
    }

    private fun filterEvents(selection: String, args: Array<String>): List<ContentValues> {
        // Parse the CalendarReader query pattern:
        // "calendar_id = ? AND dtstart > ? AND dtstart <= ?"
        if (args.size >= 3 && selection.contains("calendar_id") && selection.contains("dtstart")) {
            val calId = args[0].toLongOrNull() ?: return emptyList()
            val after = args[1].toLongOrNull() ?: return emptyList()
            val before = args[2].toLongOrNull() ?: return emptyList()
            return events.filter { cv ->
                val evCalId = cv.getAsLong(CalendarContract.Events.CALENDAR_ID) ?: return@filter false
                val dtstart = cv.getAsLong(CalendarContract.Events.DTSTART) ?: return@filter false
                evCalId == calId && dtstart > after && dtstart <= before
            }
        }
        return events
    }

    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?): Int = 0
    override fun delete(uri: Uri, sel: String?, args: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}
