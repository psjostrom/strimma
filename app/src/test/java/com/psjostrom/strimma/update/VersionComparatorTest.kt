package com.psjostrom.strimma.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `parse strips v prefix`() {
        assertEquals(listOf(1, 2, 3), VersionComparator.parse("v1.2.3"))
        assertEquals(listOf(1, 2, 3), VersionComparator.parse("1.2.3"))
    }

    @Test
    fun `parse handles two-segment versions`() {
        assertEquals(listOf(1, 0), VersionComparator.parse("1.0"))
    }

    @Test
    fun `parse returns empty for garbage`() {
        assertEquals(emptyList<Int>(), VersionComparator.parse(""))
        assertEquals(emptyList<Int>(), VersionComparator.parse("   "))
    }

    @Test
    fun `older patch version`() {
        assertTrue(VersionComparator.isOlderThan("0.9.0", "0.9.1"))
    }

    @Test
    fun `older minor version`() {
        assertTrue(VersionComparator.isOlderThan("0.8.5", "0.9.0"))
    }

    @Test
    fun `older major version`() {
        assertTrue(VersionComparator.isOlderThan("0.9.9", "1.0.0"))
    }

    @Test
    fun `same version is not older`() {
        assertFalse(VersionComparator.isOlderThan("1.0.0", "1.0.0"))
    }

    @Test
    fun `newer version is not older`() {
        assertFalse(VersionComparator.isOlderThan("1.0.1", "1.0.0"))
    }

    @Test
    fun `handles v prefix in comparison`() {
        assertTrue(VersionComparator.isOlderThan("v0.9.0", "v0.9.1"))
    }

    @Test
    fun `different segment lengths`() {
        assertTrue(VersionComparator.isOlderThan("1.0", "1.0.1"))
        assertFalse(VersionComparator.isOlderThan("1.0.1", "1.0"))
    }

    @Test
    fun `unparseable returns false`() {
        assertFalse(VersionComparator.isOlderThan("", "1.0.0"))
        assertFalse(VersionComparator.isOlderThan("1.0.0", ""))
    }

    // Pre-release support

    @Test
    fun `parse strips pre-release suffix`() {
        assertEquals(listOf(1, 1, 0), VersionComparator.parse("1.1.0-rc.1"))
        assertEquals(listOf(1, 1, 0), VersionComparator.parse("v1.1.0-beta.2"))
    }

    @Test
    fun `isPreRelease detects rc and beta tags`() {
        assertTrue(VersionComparator.isPreRelease("1.1.0-rc.1"))
        assertTrue(VersionComparator.isPreRelease("v1.1.0-beta.2"))
        assertFalse(VersionComparator.isPreRelease("1.1.0"))
        assertFalse(VersionComparator.isPreRelease("v1.1.0"))
    }

    @Test
    fun `preReleaseNumber extracts the number`() {
        assertEquals(1, VersionComparator.preReleaseNumber("1.1.0-rc.1"))
        assertEquals(3, VersionComparator.preReleaseNumber("v1.1.0-rc.3"))
        assertEquals(0, VersionComparator.preReleaseNumber("1.1.0"))
    }

    @Test
    fun `rc is older than stable of same version`() {
        assertTrue(VersionComparator.isOlderThan("1.1.0-rc.1", "1.1.0"))
        assertTrue(VersionComparator.isOlderThan("v1.1.0-rc.3", "v1.1.0"))
    }

    @Test
    fun `stable is not older than rc of same version`() {
        assertFalse(VersionComparator.isOlderThan("1.1.0", "1.1.0-rc.1"))
    }

    @Test
    fun `lower rc is older than higher rc`() {
        assertTrue(VersionComparator.isOlderThan("1.1.0-rc.1", "1.1.0-rc.2"))
        assertFalse(VersionComparator.isOlderThan("1.1.0-rc.2", "1.1.0-rc.1"))
    }

    @Test
    fun `same rc is not older`() {
        assertFalse(VersionComparator.isOlderThan("1.1.0-rc.1", "1.1.0-rc.1"))
    }

    @Test
    fun `rc of higher base version is newer than stable of lower`() {
        assertTrue(VersionComparator.isOlderThan("1.0.0", "1.1.0-rc.1"))
    }

    @Test
    fun `rc of lower base version is older than stable of higher`() {
        assertTrue(VersionComparator.isOlderThan("1.0.0-rc.1", "1.1.0"))
    }
}
