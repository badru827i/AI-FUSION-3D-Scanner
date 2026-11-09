package com.aifusion.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DepthQualityAnalyzerTest {
    @Test
    fun structuredDepthMapIsUsable() {
        val values = FloatArray(16 * 16) { index ->
            val x = index % 16
            val y = index / 16
            (x / 15f * 0.65f + y / 15f * 0.35f)
        }
        val result = DepthQualityAnalyzer.analyze(values, 16, 16)
        assertTrue(result.usable)
        assertTrue(result.score > 50)
        assertEquals(1f, result.validFraction, 0.0001f)
    }

    @Test
    fun flatDepthMapIsRejected() {
        val result = DepthQualityAnalyzer.analyze(FloatArray(64) { 0.5f }, 8, 8)
        assertFalse(result.usable)
        assertEquals(0f, result.normalizedRange, 0.0001f)
    }

    @Test
    fun nonFiniteDepthIsCountedAsInvalid() {
        val values = FloatArray(16) { 0.5f }
        values[3] = Float.NaN
        val result = DepthQualityAnalyzer.analyze(values, 4, 4)
        assertFalse(result.usable)
        assertTrue(result.validFraction < 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dimensionsCannotExceedBuffer() {
        DepthQualityAnalyzer.analyze(FloatArray(4), 3, 3)
    }
}
