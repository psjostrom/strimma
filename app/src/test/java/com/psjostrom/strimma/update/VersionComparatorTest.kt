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
}
