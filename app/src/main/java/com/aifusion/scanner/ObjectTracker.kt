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
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var active = false
    private var misses = 0
    private var velocityX = 0f
    private var velocityY = 0f
    private var smoothedScore = 0f

    fun isActive(): Boolean = active

    fun clear() {
        patch = FloatArray(0)
        patchW = 0
        patchH = 0
        centerX = 0.5f
        centerY = 0.5f
        misses = 0
        velocityX = 0f
        velocityY = 0f
        smoothedScore = 0f
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
        centerX = x / (bitmap.width - 1f)
        centerY = y / (bitmap.height - 1f)
        misses = 0
        velocityX = 0f
        velocityY = 0f
        smoothedScore = 1f
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

        val baseRadius = max(8, min(width, height) / 3)
        val allowedRadius = max(width, height) / 2
        val searchRadius = min(allowedRadius, baseRadius * (1 + misses.coerceAtMost(2)))
        val searchCenterX = if (misses == 0) predictedX else previousX
        val searchCenterY = if (misses == 0) predictedY else previousY
        val coarseStep = if (max(width, height) <= 100) 2 else 3

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
                val adjusted = (visual - 0.13f * distance / max(1, searchRadius)).coerceIn(0f, 1f)
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
                val adjusted = (visual - 0.13f * distance / max(1, searchRadius)).coerceIn(0f, 1f)
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
        val requiredScore = if (wasRecovering) 0.62f else 0.66f
        if (bestVisualScore < requiredScore) {
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
        // Smooth the position while maintaining a bounded velocity estimate.
        centerX = (centerX + deltaX * 0.52f).coerceIn(0f, 1f)
        centerY = (centerY + deltaY * 0.52f).coerceIn(0f, 1f)
        velocityX = (velocityX * 0.35f + deltaX * 0.65f).coerceIn(-0.12f, 0.12f)
        velocityY = (velocityY * 0.35f + deltaY * 0.65f).coerceIn(-0.12f, 0.12f)
        smoothedScore = smoothedScore * 0.65f + bestVisualScore * 0.35f

        val moved = sqrt(
            ((bestX - previousX) * (bestX - previousX) + (bestY - previousY) * (bestY - previousY)).toFloat()
        )
        // Adapt only from strong, nearby matches to avoid contaminating the template.
        if (bestVisualScore >= 0.86f && moved <= searchRadius * 0.30f) {
            val observed = samplePatch(pixels, width, height, bestX, bestY, patchW, patchH)
            for (i in patch.indices) {
                patch[i] = patch[i] * 0.99f + observed[i] * 0.01f
            }
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
                i++
            }
        }

        val rawSimilarity = (1f - absoluteError / count).coerceIn(0f, 1f)
        val varianceProduct = templateVariance * candidateVariance
        if (varianceProduct < 1e-7f) return rawSimilarity

        val correlation = (covariance / sqrt(varianceProduct)).coerceIn(-1f, 1f)
        val correlationSimilarity = (correlation + 1f) * 0.5f
        // Raw similarity handles simple patches; normalized correlation is
        // less sensitive to moderate exposure changes on textured objects.
        return (rawSimilarity * 0.58f + correlationSimilarity * 0.42f).coerceIn(0f, 1f)
    }

    private fun gray(pixel: Int): Float =
        ((pixel shr 16 and 255) * 0.299f +
            (pixel shr 8 and 255) * 0.587f +
            (pixel and 255) * 0.114f) / 255f
}