package com.psjostrom.strimma.data.meal

import org.junit.Assert.assertEquals
import org.junit.Test

class CarbSizeBucketTest {

    @Test fun `below 20g is SMALL`() = assertEquals(CarbSizeBucket.SMALL, CarbSizeBucket.fromGrams(19.9))
    @Test fun `exactly 20g is MEDIUM`() = assertEquals(CarbSizeBucket.MEDIUM, CarbSizeBucket.fromGrams(20.0))
    @Test fun `between 20 and 50 is MEDIUM`() = assertEquals(CarbSizeBucket.MEDIUM, CarbSizeBucket.fromGrams(35.0))
    @Test fun `exactly 50g is MEDIUM`() = assertEquals(CarbSizeBucket.MEDIUM, CarbSizeBucket.fromGrams(50.0))
    @Test fun `above 50g is LARGE`() = assertEquals(CarbSizeBucket.LARGE, CarbSizeBucket.fromGrams(50.1))
}
