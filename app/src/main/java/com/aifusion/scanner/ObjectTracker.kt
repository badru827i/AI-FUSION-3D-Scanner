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
    val misses: Int = 0
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
    // Cached template moments avoid recalculating them for every search candidate.
    private var patchSum = 0f
    private var patchSquaredSum = 0f
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var active = false
    private var misses = 0

    fun isActive(): Boolean = active

    fun clear() {
        patch = FloatArray(0)
        patchW = 0
        patchH = 0
        patchSum = 0f
        patchSquaredSum = 0f
        centerX = 0.5f
        centerY = 0.5f
        misses = 0
        active = false
    }

    fun lock(bitmap: Bitmap, nx: Float, ny: Float) {
        if (bitmap.width < 8 || bitmap.height < 8) {
            clear()
            return
        }

        patchW = min(24, max(6, bitmap.width / 7)).coerceAtMost(bitmap.width - 1)
        patchH = min(24, max(6, bitmap.height / 7)).coerceAtMost(bitmap.height - 1)
        val pixels = grayscale(bitmap)
        val x = (nx.coerceIn(0f, 1f) * (bitmap.width - 1)).toInt()
        val y = (ny.coerceIn(0f, 1f) * (bitmap.height - 1)).toInt()

        patch = samplePatch(pixels, bitmap.width, bitmap.height, x, y, patchW, patchH)
        refreshTemplateStats()
        centerX = x / (bitmap.width - 1f)
        centerY = y / (bitmap.height - 1f)
        misses = 0
        active = patch.isNotEmpty()
    }

    fun update(bitmap: Bitmap): ObjectTrackResult {
        if (!active || patch.isEmpty() || bitmap.width < patchW || bitmap.height < patchH) {
            return ObjectTrackResult(centerX, centerY, 0f, false, misses)
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = grayscale(bitmap)
        val previousX = (centerX * (width - 1)).toInt()
        val previousY = (centerY * (height - 1)).toInt()
        val minX = patchW / 2
        val minY = patchH / 2
        val maxX = width - patchW + patchW / 2
        val maxY = height - patchH + patchH / 2

        val baseRadius = max(8, min(width, height) / 3)
        val allowedRadius = max(width, height) / 2
        val searchRadius = min(allowedRadius, baseRadius * (1 + misses.coerceAtMost(2)))
        val coarseStep = if (max(width, height) <= 100) 2 else 3

        var bestScore = -1f
        var bestVisualScore = 0f
        var bestX = previousX.coerceIn(minX, maxX)
        var bestY = previousY.coerceIn(minY, maxY)

        var y = max(minY, previousY - searchRadius)
        val endY = min(maxY, previousY + searchRadius)
        while (y <= endY) {
            var x = max(minX, previousX - searchRadius)
            val endX = min(maxX, previousX + searchRadius)
            while (x <= endX) {
                val visual = match(pixels, width, x, y)
                val distance = sqrt(
                    ((x - previousX) * (x - previousX) + (y - previousY) * (y - previousY)).toFloat()
                )
                // Prefer nearby matches slightly; this prevents textureless areas
                // from jumping between equally dark patches on every frame.
                val adjusted = (visual - 0.10f * distance / max(1, searchRadius)).coerceIn(0f, 1f)
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
                    ((x - previousX) * (x - previousX) + (y - previousY) * (y - previousY)).toFloat()
                )
                val adjusted = (visual - 0.10f * distance / max(1, searchRadius)).coerceIn(0f, 1f)
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

        if (bestVisualScore < 0.60f) {
            misses++
            return ObjectTrackResult(centerX, centerY, bestVisualScore, false, misses)
        }

        val wasRecovering = misses > 0
        misses = 0
        val targetX = bestX / (width - 1f)
        val targetY = bestY / (height - 1f)
        val gain = when {
            wasRecovering -> 0.48f
            bestVisualScore >= 0.84f -> 0.46f
            else -> 0.30f
        }
        centerX = (centerX + (targetX - centerX) * gain).coerceIn(0f, 1f)
        centerY = (centerY + (targetY - centerY) * gain).coerceIn(0f, 1f)

        val moved = sqrt(
            ((bestX - previousX) * (bestX - previousX) + (bestY - previousY) * (bestY - previousY)).toFloat()
        )
        if (bestVisualScore >= 0.84f && moved <= searchRadius * 0.45f) {
            val observed = samplePatch(pixels, width, height, bestX, bestY, patchW, patchH)
            for (i in patch.indices) {
                patch[i] = patch[i] * 0.975f + observed[i] * 0.025f
            }
            refreshTemplateStats()
        }

        return ObjectTrackResult(centerX, centerY, bestVisualScore, true, misses)
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

    /**
     * One-pass candidate statistics. The old implementation scanned every
     * candidate twice and recalculated the fixed template statistics each time.
     * This version keeps the same similarity blend but reduces repeated pixel work.
     */
    private fun refreshTemplateStats() {
        var sum = 0f
        var squaredSum = 0f
        for (value in patch) {
            sum += value
            squaredSum += value * value
        }
        patchSum = sum
        patchSquaredSum = squaredSum
    }

    private fun match(pixels: FloatArray, imageWidth: Int, cx: Int, cy: Int): Float {
        val left = cx - patchW / 2
        val top = cy - patchH / 2
        val count = patch.size
        if (count == 0) return 0f

        var candidateSum = 0f
        var candidateSquaredSum = 0f
        var crossSum = 0f
        var absoluteError = 0f
        var i = 0
        for (y in 0 until patchH) {
            val row = (top + y) * imageWidth + left
            for (x in 0 until patchW) {
                val a = patch[i]
                val b = pixels[row + x]
                candidateSum += b
                candidateSquaredSum += b * b
                crossSum += a * b
                absoluteError += abs(a - b)
                i++
            }
        }

        val n = count.toFloat()
        val rawSimilarity = (1f - absoluteError / n).coerceIn(0f, 1f)
        val templateVariance = (patchSquaredSum - patchSum * patchSum / n).coerceAtLeast(0f)
        val candidateVariance =
            (candidateSquaredSum - candidateSum * candidateSum / n).coerceAtLeast(0f)
        val varianceProduct = templateVariance * candidateVariance
        if (varianceProduct < 1e-7f) return rawSimilarity

        val covariance = crossSum - patchSum * candidateSum / n
        val correlation = (covariance / sqrt(varianceProduct)).coerceIn(-1f, 1f)
        val correlationSimilarity = (correlation + 1f) * 0.5f
        return (rawSimilarity * 0.58f + correlationSimilarity * 0.42f).coerceIn(0f, 1f)
    }

    private fun gray(pixel: Int): Float =
        ((pixel shr 16 and 255) * 0.299f +
            (pixel shr 8 and 255) * 0.587f +
            (pixel and 255) * 0.114f) / 255f
}