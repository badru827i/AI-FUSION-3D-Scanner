package com.aifusion.scanner

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ObjectTrackResult(val x: Float, val y: Float, val score: Float, val tracked: Boolean)

/**
 * Lightweight template tracker for small Android phones.
 * Uses locally normalized grayscale patches to tolerate moderate exposure changes,
 * a coarse-to-fine search and smoothed motion so the lock does not jump every frame.
 */
class ObjectTracker {
    private var patch = FloatArray(0)
    private var patchW = 0
    private var patchH = 0
    @Volatile private var centerX = 0.5f
    @Volatile private var centerY = 0.5f
    @Volatile private var active = false
    private var velocityX = 0f
    private var velocityY = 0f
    private var lostFrames = 0

    fun isActive(): Boolean = active

    @Synchronized
    fun clear() {
        patch = FloatArray(0)
        active = false
        velocityX = 0f
        velocityY = 0f
        lostFrames = 0
    }

    @Synchronized
    fun lock(bitmap: Bitmap, nx: Float, ny: Float) {
        if (bitmap.width < 12 || bitmap.height < 12) {
            active = false
            return
        }
        val x = (nx * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (ny * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        patchW = min(24, max(10, bitmap.width / 7)).coerceAtMost(bitmap.width)
        patchH = min(24, max(10, bitmap.height / 7)).coerceAtMost(bitmap.height)
        patch = sampleNormalizedPatch(bitmap, x, y, patchW, patchH)
        centerX = x / bitmap.width.toFloat()
        centerY = y / bitmap.height.toFloat()
        velocityX = 0f
        velocityY = 0f
        lostFrames = 0
        active = patch.isNotEmpty()
    }

    @Synchronized
    fun update(bitmap: Bitmap): ObjectTrackResult {
        if (!active || patch.isEmpty() || bitmap.width < patchW || bitmap.height < patchH) {
            return ObjectTrackResult(centerX, centerY, 0f, false)
        }

        val oldX = (centerX * bitmap.width).toInt()
        val oldY = (centerY * bitmap.height).toInt()
        val predictedX = (oldX + velocityX * bitmap.width).toInt()
        val predictedY = (oldY + velocityY * bitmap.height).toInt()
        val baseRadius = max(12, min(42, bitmap.width / 3))
        val radius = min(baseRadius + lostFrames * 4, max(bitmap.width, bitmap.height) / 2)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestX = oldX
        var bestY = oldY

        // Coarse pass, followed by a finer local pass around the best candidate.
        val coarseStep = max(3, min(bitmap.width, bitmap.height) / 12)
        val coarse = search(bitmap, predictedX, predictedY, radius, coarseStep)
        bestX = coarse.first
        bestY = coarse.second
        bestScore = coarse.third
        val fine = search(bitmap, bestX, bestY, coarseStep * 2, 1)
        if (fine.third > bestScore) {
            bestX = fine.first
            bestY = fine.second
            bestScore = fine.third
        }

        if (bestScore < 0.42f) {
            lostFrames = (lostFrames + 1).coerceAtMost(12)
            return ObjectTrackResult(centerX, centerY, bestScore.coerceAtLeast(0f), false)
        }

        lostFrames = 0
        val rawX = bestX / bitmap.width.toFloat()
        val rawY = bestY / bitmap.height.toFloat()
        val dx = rawX - centerX
        val dy = rawY - centerY
        // Strong smoothing suppresses jitter; retain a little velocity for smooth motion.
        val alpha = if (bestScore > 0.72f) 0.42f else 0.25f
        centerX = (centerX + dx * alpha).coerceIn(0f, 1f)
        centerY = (centerY + dy * alpha).coerceIn(0f, 1f)
        velocityX = (velocityX * 0.55f + dx * 0.45f).coerceIn(-0.18f, 0.18f)
        velocityY = (velocityY * 0.55f + dy * 0.45f).coerceIn(-0.18f, 0.18f)

        // Adapt slowly only on a confident match to handle small lighting changes
        // without allowing a wrong object to immediately replace the locked template.
        if (bestScore > 0.76f) {
            val next = sampleNormalizedPatch(bitmap, bestX, bestY, patchW, patchH)
            if (next.size == patch.size) for (i in patch.indices) {
                patch[i] = patch[i] * 0.92f + next[i] * 0.08f
            }
        }
        return ObjectTrackResult(centerX, centerY, bestScore.coerceIn(0f, 1f), true)
    }

    private fun search(bitmap: Bitmap, cx: Int, cy: Int, radius: Int, step: Int): Triple<Int, Int, Float> {
        var bestX = cx.coerceIn(patchW / 2, bitmap.width - (patchW + 1) / 2)
        var bestY = cy.coerceIn(patchH / 2, bitmap.height - (patchH + 1) / 2)
        var best = Float.NEGATIVE_INFINITY
        val halfW = patchW / 2
        val halfH = patchH / 2
        val left = max(halfW, cx - radius)
        val right = min(bitmap.width - (patchW + 1) / 2, cx + radius)
        val top = max(halfH, cy - radius)
        val bottom = min(bitmap.height - (patchH + 1) / 2, cy + radius)
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val score = match(bitmap, x, y)
                if (score > best) { best = score; bestX = x; bestY = y }
                x += step
            }
            y += step
        }
        return Triple(bestX, bestY, best)
    }

    private fun sampleNormalizedPatch(bitmap: Bitmap, cx: Int, cy: Int, w: Int, h: Int): FloatArray {
        val left = (cx - w / 2).coerceIn(0, bitmap.width - w)
        val top = (cy - h / 2).coerceIn(0, bitmap.height - h)
        val raw = FloatArray(w * h)
        var sum = 0f
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            val value = gray(bitmap.getPixel(left + x, top + y))
            raw[i++] = value
            sum += value
        }
        val mean = sum / raw.size
        var variance = 0f
        for (value in raw) {
            val d = value - mean
            variance += d * d
        }
        val std = sqrt(variance / raw.size).coerceAtLeast(0.04f)
        for (j in raw.indices) raw[j] = ((raw[j] - mean) / std).coerceIn(-2.5f, 2.5f) / 2.5f
        return raw
    }

    private fun match(bitmap: Bitmap, cx: Int, cy: Int): Float {
        val left = cx - patchW / 2
        val top = cy - patchH / 2
        var sum = 0f
        var i = 0
        val count = patchW * patchH
        // First pass computes local brightness statistics.
        var mean = 0f
        for (y in 0 until patchH) for (x in 0 until patchW) {
            mean += gray(bitmap.getPixel(left + x, top + y))
        }
        mean /= count
        var variance = 0f
        for (y in 0 until patchH) for (x in 0 until patchW) {
            val d = gray(bitmap.getPixel(left + x, top + y)) - mean
            variance += d * d
        }
        val std = sqrt(variance / count).coerceAtLeast(0.04f)
        for (y in 0 until patchH) for (x in 0 until patchW) {
            val normalized = ((gray(bitmap.getPixel(left + x, top + y)) - mean) / std).coerceIn(-2.5f, 2.5f) / 2.5f
            sum += abs(normalized - patch[i++])
        }
        val error = sum / count
        return (1f - error / 1.15f).coerceIn(0f, 1f)
    }

    private fun gray(pixel: Int): Float =
        ((pixel shr 16 and 255) * 0.299f + (pixel shr 8 and 255) * 0.587f + (pixel and 255) * 0.114f) / 255f
}
