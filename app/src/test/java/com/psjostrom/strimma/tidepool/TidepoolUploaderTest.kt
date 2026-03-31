package com.psjostrom.strimma.tidepool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
