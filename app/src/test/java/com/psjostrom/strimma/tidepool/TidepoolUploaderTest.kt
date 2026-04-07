package com.psjostrom.strimma.tidepool

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TidepoolUploaderTest {
    @Test
    fun `computeChunkEnd caps at 7 days from start`() {
        val start = 1774375800000L
        val now = start + 14 * 86_400_000L
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertEquals(start + 7 * 86_400_000L, end)
    }

    @Test
    fun `computeChunkEnd uses now minus buffer when within 7 days`() {
        val start = 1774375800000L
        val now = start + 3600_000L
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertEquals(now - TidepoolUploader.UPLOAD_BUFFER_MS, end)
    }

    @Test
    fun `computeChunkEnd returns value at or below start when now is too close`() {
        val start = 1774375800000L
        val now = start + 60_000L
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertTrue(end <= start)
    }

    @Test
    fun `clampLastUploadEnd clamps zero to 2 months ago`() {
        val now = System.currentTimeMillis()
        val result = TidepoolUploader.clampLastUploadEnd(0L, now)
        val twoMonthsAgo = now - 60L * 86_400_000L
        assertTrue(result >= twoMonthsAgo - 1000)
    }

    @Test
    fun `stop then uploadPending does not throw (scope is recreated)`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context))
        val uploader = TidepoolUploader(context, TidepoolClient(), TidepoolAuthManager(context, settings), db.readingDao(), settings)

        uploader.stop()
        // Should not throw — scope is recreated after stop()
        uploader.uploadPending()
        uploader.onNewReading()

        uploader.stop()
        db.close()
    }
}
