package com.aifusion.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Multi-layer, low-cost tracking stabilizer.
 *
 * Layers: confidence gate -> 3-sample median -> motion/outlier gate ->
 * adaptive EMA -> bounded alpha-beta velocity correction. The router adjusts
 * smoothing and jump tolerance using confidence, recovery state and frame size.
 * Coordinates are normalized to 0..1, so this works across camera resolutions.
 */
data class FilteredTrackPoint(
    val x: Float,
    val y: Float,
    val confidence: Float,
    val rejected: Boolean
)

class TrackingFilterStack {
    private data class Point(val x: Float, val y: Float)

    private val history = ArrayDeque<Point>(3)
    private var outputX = 0.5f
    private var outputY = 0.5f
    private var velocityX = 0f
    private var velocityY = 0f
    private var initialized = false
    private var weakFrames = 0

    fun reset(x: Float = 0.5f, y: Float = 0.5f) {
        history.clear()
        outputX = x.coerceIn(0f, 1f)
        outputY = y.coerceIn(0f, 1f)
        velocityX = 0f
        velocityY = 0f
        initialized = false
        weakFrames = 0
    }

    fun process(
        measuredX: Float,
        measuredY: Float,
        confidence: Float,
        frameWidth: Int,
        frameHeight: Int,
        recovering: Boolean
    ): FilteredTrackPoint {
        val x = measuredX.coerceIn(0f, 1f)
        val y = measuredY.coerceIn(0f, 1f)
        val score = confidence.coerceIn(0f, 1f)
        if (!initialized) {
            outputX = x
            outputY = y
            history.addLast(Point(x, y))
            initialized = true
            return FilteredTrackPoint(outputX, outputY, score, false)
        }

        // Layer 1: confidence hysteresis. Weak detections cannot drag the lock.
        if (score < 0.64f) weakFrames++ else weakFrames = 0
        if (weakFrames >= 2) {
            velocityX *= 0.45f
            velocityY *= 0.45f
            return FilteredTrackPoint(outputX, outputY, score, true)
        }

        // Layer 2: median-of-three removes single-frame coordinate spikes.
        val candidates = (history.toList() + Point(x, y)).takeLast(3)
        val medianX = candidates.map { it.x }.sorted()[candidates.size / 2]
        val medianY = candidates.map { it.y }.sorted()[candidates.size / 2]

        // Router selects a conservative gate on small frames and wider gate during reacquisition.
        val diagonal = sqrt((frameWidth * frameWidth + frameHeight * frameHeight).toFloat())
            .coerceAtLeast(1f)
        val pixelScale = (diagonal / 900f).coerceIn(0.55f, 1.35f)
        val jumpLimit = when {
            recovering -> 0.105f * pixelScale
            score >= 0.90f -> 0.085f * pixelScale
            else -> 0.055f * pixelScale
        }
        val dx = medianX - outputX
        val dy = medianY - outputY
        val distance = sqrt(dx * dx + dy * dy)
        if (distance > jumpLimit && score < 0.91f) {
            velocityX *= 0.50f
            velocityY *= 0.50f
            return FilteredTrackPoint(outputX, outputY, score, true)
        }

        // Layer 3: confidence-aware EMA; more smoothing for low confidence/jitter.
        val alpha = when {
            recovering -> 0.22f
            score >= 0.92f -> if (distance > 0.045f) 0.48f else 0.30f
            score >= 0.82f -> 0.30f
            else -> 0.19f
        }
        val predictedX = (outputX + velocityX).coerceIn(0f, 1f)
        val predictedY = (outputY + velocityY).coerceIn(0f, 1f)
        var nextX = predictedX + (medianX - predictedX) * alpha
        var nextY = predictedY + (medianY - predictedY) * alpha

        // Layer 4: bounded alpha-beta velocity update prevents lag without teleporting.
        val residualX = nextX - outputX
        val residualY = nextY - outputY
        velocityX = (velocityX * 0.72f + residualX * 0.18f).coerceIn(-0.045f, 0.045f)
        velocityY = (velocityY * 0.72f + residualY * 0.18f).coerceIn(-0.045f, 0.045f)
        val maxStep = if (score >= 0.92f && !recovering) 0.075f else 0.045f
        nextX = outputX + (nextX - outputX).coerceIn(-maxStep, maxStep)
        nextY = outputY + (nextY - outputY).coerceIn(-maxStep, maxStep)
        outputX = nextX.coerceIn(0f, 1f)
        outputY = nextY.coerceIn(0f, 1f)

        history.addLast(Point(medianX, medianY))
        while (history.size > 3) history.removeFirst()
        return FilteredTrackPoint(outputX, outputY, score, false)
    }
}
