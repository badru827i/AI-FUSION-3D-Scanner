package com.aifusion.scanner.ai

import com.aifusion.scanner.CameraFrameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanIntelligenceEngineTest {
    private fun frame(
        mean: Double = 120.0,
        std: Double = 42.0,
        under: Double = 0.02,
        over: Double = 0.01,
        edges: Double = 15.0,
        samples: Int = 1200
    ) = CameraFrameStats(
        frameNumber = 1,
        width = 640,
        height = 480,
        rotationDegrees = 0,
        timestampNanos = 1L,
        meanLuma = mean,
        lumaStdDev = std,
        underexposedRatio = under,
        overexposedRatio = over,
        edgeStrength = edges,
        sampleCount = samples
    )

    @Test fun goodFrameGetsHighScore() {
        val result = ScanIntelligenceEngine.assess(frame())
        assertEquals(100, result.qualityScore)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun darkFrameRecommendsMoreLight() {
        val result = ScanIntelligenceEngine.assess(frame(mean = 35.0, under = 0.7))
        assertTrue(result.qualityScore < 100)
        assertTrue(result.guidance.contains("pencahayaan", ignoreCase = true))
    }

    @Test fun lowTextureDoesNotClaimBlur() {
        val result = ScanIntelligenceEngine.assess(frame(edges = 1.0))
        assertTrue(result.issues.any { it.contains("butiran/tepi") })
        assertTrue(result.issues.none { it.contains("kabur", ignoreCase = true) })
    }

    @Test fun insufficientSamplesAreFlagged() {
        val result = ScanIntelligenceEngine.assess(frame(samples = 20))
        assertTrue(result.issues.any { it.contains("Sampel") })
    }
}
