package com.aifusion.scanner

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ObjectTrackResult(
    val x: Float,
    val y: Float,
    val score: Float,
    val tracked: Boolean,
    val misses: Int = 0,
    val width: Float = 0.22f,
    val height: Float = 0.22f
)

/**
 * Lightweight template tracker for small Android devices.
 *
 * It combines normalized patch correlation with raw pixel similarity, a
 * coarse-to-fine search, temporal smoothing and cautious template adaptation.
 * The lock is retained during short occlusions so the next frame can relocalize.
 */
class ObjectTracker {
    private var patch = FloatArray(0)
    private var patchW = 0
    private var patchH = 0
    // Compact 3x3 spatial appearance descriptor helps distinguish similar gray patches.
    private var patchGrid = FloatArray(9)
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var active = false
    private var misses = 0
    private var velocityX = 0f
    private var velocityY = 0f
    private var smoothedScore = 0f
    private val filterStack = TrackingFilterStack()

    fun isActive(): Boolean = active

    fun clear() {
        patch = FloatArray(0)
        patchW = 0
        patchH = 0
        patchGrid = FloatArray(9)
        centerX = 0.5f
        centerY = 0.5f
        misses = 0
        velocityX = 0f
        velocityY = 0f
        smoothedScore = 0f
        filterStack.reset()
        active = false
    }

    fun lock(bitmap: Bitmap, nx: Float, ny: Float) {
        if (bitmap.width < 8 || bitmap.height < 8) {
            clear()
            return
        }

        // Use a larger appearance anchor than a tiny point so the lock is less likely
        // to jump to a similar background texture. Keep it bounded for entry-level phones.
        patchW = min(32, max(8, bitmap.width / 6)).coerceAtMost(bitmap.width - 1)
        patchH = min(32, max(8, bitmap.height / 6)).coerceAtMost(bitmap.height - 1)
        val pixels = grayscale(bitmap)
        val x = (nx.coerceIn(0f, 1f) * (bitmap.width - 1)).toInt()
        val y = (ny.coerceIn(0f, 1f) * (bitmap.height - 1)).toInt()

        // Keep the stored anchor aligned with the actual patch when a tap is near an edge.
        val sampleX = x.coerceIn(patchW / 2, bitmap.width - patchW + patchW / 2)
        val sampleY = y.coerceIn(patchH / 2, bitmap.height - patchH + patchH / 2)
        patch = samplePatch(pixels, bitmap.width, bitmap.height, sampleX, sampleY, patchW, patchH)
        patchGrid = normalizedGrid(patch, patchW, patchH)
        centerX = sampleX / (bitmap.width - 1f)
        centerY = sampleY / (bitmap.height - 1f)
        misses = 0
        velocityX = 0f
        velocityY = 0f
        smoothedScore = 0f
        filterStack.reset(centerX, centerY)
        active = patch.isNotEmpty()
    }

    fun update(bitmap: Bitmap, cameraMotion: Float = 0f): ObjectTrackResult {
        if (!active || patch.isEmpty() || bitmap.width < patchW || bitmap.height < patchH) {
            return ObjectTrackResult(centerX, centerY, 0f, false, misses)
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = grayscale(bitmap)
        val previousX = (centerX * (width - 1)).toInt()
        val previousY = (centerY * (height - 1)).toInt()
        // Predict a short distance along the last reliable motion to reduce lag.
        // Keep the prediction bounded so a bad frame cannot send the lock away.
        val predictedX = (previousX + velocityX * (width - 1)).toInt()
            .coerceIn(0, width - 1)
        val predictedY = (previousY + velocityY * (height - 1)).toInt()
            .coerceIn(0, height - 1)
        val minX = patchW / 2
        val minY = patchH / 2
        val maxX = width - patchW + patchW / 2
        val maxY = height - patchH + patchH / 2

        // Expand the local search when gyro/accelerometer reports a camera turn.
        // This improves reacquisition without paying for a full-frame search each frame.
        val motionExpansion = when {
            cameraMotion > 5f -> 0.15f
            cameraMotion > 2f -> 0.08f
            else -> 0f
        }
        // Keep the normal search local. A very wide search often locks onto
        // a similar background texture and makes the overlay jump.
        val baseRadius = max(8, (min(width, height) * (0.12f + motionExpansion)).toInt())
        val allowedRadius = (max(width, height) * 0.48f).toInt()
        // After repeated misses, widen to a genuine full-frame relocalization pass.
        // Use a coarser stride during recovery to cap CPU work on entry-level phones.
        val fullFrameRecovery = misses >= 2
        val searchRadius = if (fullFrameRecovery) max(width, height)
            else min(allowedRadius, baseRadius * (1 + misses.coerceAtMost(2)))
        val searchCenterX = if (misses == 0) predictedX else previousX
        val searchCenterY = if (misses == 0) predictedY else previousY
        val coarseStep = when {
            fullFrameRecovery -> 5
            max(width, height) <= 100 -> 2
            else -> 3
        }

        var bestScore = -1f
        var bestVisualScore = 0f
        var bestX = previousX.coerceIn(minX, maxX)
        var bestY = previousY.coerceIn(minY, maxY)

        var y = max(minY, searchCenterY - searchRadius)
        val endY = min(maxY, searchCenterY + searchRadius)
        while (y <= endY) {
            var x = max(minX, searchCenterX - searchRadius)
            val endX = min(maxX, searchCenterX + searchRadius)
            while (x <= endX) {
                val visual = match(pixels, width, x, y)
                val distance = sqrt(
                    ((x - searchCenterX) * (x - searchCenterX) +
                        (y - searchCenterY) * (y - searchCenterY)).toFloat()
                )
                // Penalize distant candidates to reduce jumps to similar background patches.
                val adjusted = (visual - 0.22f * distance / max(1, searchRadius)).coerceIn(0f, 1f)
                if (adjusted > bestScore) {
                    bestScore = adjusted
                    bestVisualScore = visual
                    bestX = x
                    bestY = y
                }
                x += coarseStep
            }
            y += coarseStep
        }

        // Refine the best coarse candidate at single-pixel precision.
        val coarseX = bestX
        val coarseY = bestY
        val refineRadius = coarseStep + 1
        y = max(minY, coarseY - refineRadius)
        val refineEndY = min(maxY, coarseY + refineRadius)
        while (y <= refineEndY) {
            var x = max(minX, coarseX - refineRadius)
            val refineEndX = min(maxX, coarseX + refineRadius)
            while (x <= refineEndX) {
                val visual = match(pixels, width, x, y)
                val distance = sqrt(
                    ((x - searchCenterX) * (x - searchCenterX) +
                        (y - searchCenterY) * (y - searchCenterY)).toFloat()
                )
                val adjusted = (visual - 0.22f * distance / max(1, searchRadius)).coerceIn(0f, 1f)
                if (adjusted > bestScore) {
                    bestScore = adjusted
                    bestVisualScore = visual
                    bestX = x
                    bestY = y
                }
                x++
            }
            y++
        }

        // Require a stronger match for normal tracking; allow a slightly lower
        // threshold during recovery, but never silently switch targets on a weak match.
        val wasRecovering = misses > 0
        val requiredScore = if (wasRecovering) 0.66f else 0.69f
        val displacementPx = sqrt(
            ((bestX - previousX) * (bestX - previousX) +
                (bestY - previousY) * (bestY - previousY)).toFloat()
        )
        val frameDiagonal = sqrt((width * width + height * height).toFloat()).coerceAtLeast(1f)
        val normalizedDisplacement = displacementPx / frameDiagonal
        val allowedJump = if (cameraMotion > 2f) 0.24f else 0.14f
        // Reject a weak match that suddenly teleports to another part of the scene.
        val suspiciousJump = normalizedDisplacement > allowedJump && bestVisualScore < 0.86f
        if (bestVisualScore < requiredScore || suspiciousJump) {
            misses++
            velocityX *= 0.55f
            velocityY *= 0.55f
            return ObjectTrackResult(
                centerX, centerY, smoothedScore, false, misses,
                patchW / (width - 1f), patchH / (height - 1f)
            )
        }

        misses = 0
        val targetX = bestX / (width - 1f)
        val targetY = bestY / (height - 1f)
        val deltaX = (targetX - centerX).coerceIn(-0.18f, 0.18f)
        val deltaY = (targetY - centerY).coerceIn(-0.18f, 0.18f)
        val deltaMagnitude = sqrt(deltaX * deltaX + deltaY * deltaY)

        // Multi-layer router: confidence gate, median, outlier rejection, adaptive
        // EMA and bounded motion prediction. Never advance the template on a rejected point.
        val filtered = filterStack.process(
            targetX, targetY, bestVisualScore, width, height, wasRecovering
        )
        if (filtered.rejected) {
            misses++
            velocityX *= 0.55f
            velocityY *= 0.55f
            return ObjectTrackResult(
                centerX, centerY, smoothedScore, false, misses,
                patchW / (width - 1f), patchH / (height - 1f)
            )
        }
        centerX = filtered.x
        centerY = filtered.y
        velocityX = (velocityX * 0.70f + (centerX - previousX / (width - 1f)) * 0.30f)
            .coerceIn(-0.08f, 0.08f)
        velocityY = (velocityY * 0.70f + (centerY - previousY / (height - 1f)) * 0.30f)
            .coerceIn(-0.08f, 0.08f)
        smoothedScore = if (smoothedScore <= 0f) bestVisualScore else smoothedScore * 0.65f + bestVisualScore * 0.35f

        val moved = sqrt(
            ((bestX - previousX) * (bestX - previousX) + (bestY - previousY) * (bestY - previousY)).toFloat()
        )
        // Adapt only from strong, nearby matches to avoid contaminating the template.
        if (bestVisualScore >= 0.90f && moved <= max(4f, searchRadius * 0.12f)) {
            val observed = samplePatch(pixels, width, height, bestX, bestY, patchW, patchH)
            for (i in patch.indices) {
                patch[i] = patch[i] * 0.99f + observed[i] * 0.01f
            }
            patchGrid = normalizedGrid(patch, patchW, patchH)
        }

        return ObjectTrackResult(
            centerX, centerY, smoothedScore, true, misses,
            patchW / (width - 1f), patchH / (height - 1f)
        )
    }

    private fun grayscale(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val colors = IntArray(width * height)
        bitmap.getPixels(colors, 0, width, 0, 0, width, height)
        val gray = FloatArray(colors.size)
        for (i in colors.indices) gray[i] = gray(colors[i])
        return gray
    }

    private fun samplePatch(
        pixels: FloatArray,
        imageWidth: Int,
        imageHeight: Int,
        cx: Int,
        cy: Int,
        width: Int,
        height: Int
    ): FloatArray {
        val out = FloatArray(width * height)
        val left = (cx - width / 2).coerceIn(0, imageWidth - width)
        val top = (cy - height / 2).coerceIn(0, imageHeight - height)
        var i = 0
        for (y in 0 until height) {
            val row = (top + y) * imageWidth + left
            for (x in 0 until width) out[i++] = pixels[row + x]
        }
        return out
    }

    private fun match(pixels: FloatArray, imageWidth: Int, cx: Int, cy: Int): Float {
        val left = cx - patchW / 2
        val top = cy - patchH / 2
        val count = patch.size
        if (count == 0) return 0f

        var templateMean = 0f
        var candidateMean = 0f
        var i = 0
        for (y in 0 until patchH) {
            val row = (top + y) * imageWidth + left
            for (x in 0 until patchW) {
                templateMean += patch[i]
                candidateMean += pixels[row + x]
                i++
            }
        }
        templateMean /= count
        candidateMean /= count

        var absoluteError = 0f
        var covariance = 0f
        var templateVariance = 0f
        var candidateVariance = 0f
        val gridSums = FloatArray(9)
        val gridCounts = IntArray(9)
        i = 0
        for (y in 0 until patchH) {
            val row = (top + y) * imageWidth + left
            for (x in 0 until patchW) {
                val a = patch[i]
                val b = pixels[row + x]
                absoluteError += abs(a - b)
                val da = a - templateMean
                val db = b - candidateMean
                covariance += da * db
                templateVariance += da * da
                candidateVariance += db * db
                val cell = (y * 3 / patchH).coerceAtMost(2) * 3 +
                    (x * 3 / patchW).coerceAtMost(2)
                gridSums[cell] += b
                gridCounts[cell]++
                i++
            }
        }

        // Absolute pixels remain useful, but receive less weight so exposure and
        // lighting changes do not dominate the match score.
        val rawSimilarity = (1f - absoluteError / count).coerceIn(0f, 1f)
        val varianceProduct = templateVariance * candidateVariance
        val correlationSimilarity = if (varianceProduct < 1e-7f) {
            rawSimilarity
        } else {
            val correlation = (covariance / sqrt(varianceProduct)).coerceIn(-1f, 1f)
            (correlation + 1f) * 0.5f
        }

        // Compare the normalized brightness layout across nine cells. This is
        // tolerant of overall exposure changes but rejects many look-alike patches.
        val candidateGrid = FloatArray(9) { index ->
            gridSums[index] / max(1, gridCounts[index])
        }
        val candidateGridMean = candidateGrid.average().toFloat()
        var gridVariance = 0f
        for (value in candidateGrid) {
            val delta = value - candidateGridMean
            gridVariance += delta * delta
        }
        val gridScale = sqrt(gridVariance / 9f).coerceAtLeast(0.035f)
        var gridError = 0f
        for (index in candidateGrid.indices) {
            val normalized = ((candidateGrid[index] - candidateGridMean) / gridScale).coerceIn(-3f, 3f)
            gridError += abs(normalized - patchGrid[index]).coerceAtMost(6f)
        }
        // Sorting the compact descriptor makes this cue tolerant of quarter-turn
        // rotations of the object's coarse brightness layout. It is only one cue;
        // pixel correlation still helps avoid switching to a different target.
        val normalizedCandidate = FloatArray(9) { index ->
            ((candidateGrid[index] - candidateGridMean) / gridScale).coerceIn(-3f, 3f)
        }
        // Compare real 3x3 layouts under quarter-turns and mirror transforms.
        // Unlike sorting every cell, this preserves useful spatial structure.
        val rotationTolerantSimilarity = rotationInvariantGridSimilarity(normalizedCandidate)
        val gridSimilarity = (1f - gridError / (9f * 4f)).coerceIn(0f, 1f)

        // More weight on normalized correlation and coarse appearance cues makes
        // the lock less sensitive to lighting/background changes and mild turns.
        return (rawSimilarity * 0.28f +
            correlationSimilarity * 0.42f +
            gridSimilarity * 0.12f +
            rotationTolerantSimilarity * 0.18f).coerceIn(0f, 1f)
    }

    private fun rotationInvariantGridSimilarity(candidate: FloatArray): Float {
        if (candidate.size != 9 || patchGrid.size != 9) return 0f
        var bestError = Float.POSITIVE_INFINITY
        for (flip in 0..1) {
            for (rotation in 0..3) {
                var error = 0f
                for (y in 0..2) {
                    for (x in 0..2) {
                        var sourceX = if (flip == 1) 2 - x else x
                        var sourceY = y
                        repeat(rotation) {
                            val previousX = sourceX
                            sourceX = sourceY
                            sourceY = 2 - previousX
                        }
                        error += abs(candidate[sourceY * 3 + sourceX] - patchGrid[y * 3 + x])
                            .coerceAtMost(6f)
                    }
                }
                if (error < bestError) bestError = error
            }
        }
        return (1f - bestError / (9f * 4f)).coerceIn(0f, 1f)
    }

    private fun normalizedGrid(values: FloatArray, width: Int, height: Int): FloatArray {
        if (values.isEmpty() || width <= 0 || height <= 0) return FloatArray(9)
        val sums = FloatArray(9)
        val counts = IntArray(9)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cell = (y * 3 / height).coerceAtMost(2) * 3 +
                    (x * 3 / width).coerceAtMost(2)
                sums[cell] += values[index++]
                counts[cell]++
            }
        }
        val means = FloatArray(9) { cell -> sums[cell] / max(1, counts[cell]) }
        val mean = means.average().toFloat()
        var variance = 0f
        for (value in means) {
            val delta = value - mean
            variance += delta * delta
        }
        val scale = sqrt(variance / 9f).coerceAtLeast(0.035f)
        return FloatArray(9) { cell ->
            ((means[cell] - mean) / scale).coerceIn(-3f, 3f)
        }
    }

    private fun gray(pixel: Int): Float =
        ((pixel shr 16 and 255) * 0.299f +
            (pixel shr 8 and 255) * 0.587f +
            (pixel and 255) * 0.114f) / 255f
}