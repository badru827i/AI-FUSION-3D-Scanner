package com.aifusion.scanner

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ObjectTrackResult(val x: Float, val y: Float, val score: Float, val tracked: Boolean)

class ObjectTracker {
    private var patch = FloatArray(0)
    private var patchW = 0
    private var patchH = 0
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var active = false

    fun isActive(): Boolean = active
    fun clear() { patch = FloatArray(0); active = false }

    fun lock(bitmap: Bitmap, nx: Float, ny: Float) {
        val x = (nx * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (ny * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        patchW = max(12, min(bitmap.width / 5, 44))
        patchH = max(12, min(bitmap.height / 5, 44))
        if (bitmap.width < patchW || bitmap.height < patchH) { active = false; return }
        patch = samplePatch(bitmap, x, y, patchW, patchH)
        centerX = x / bitmap.width.toFloat()
        centerY = y / bitmap.height.toFloat()
        active = patch.isNotEmpty()
    }

    fun update(bitmap: Bitmap): ObjectTrackResult {
        if (!active || patch.isEmpty()) return ObjectTrackResult(centerX, centerY, 0f, false)
        val cx = (centerX * bitmap.width).toInt()
        val cy = (centerY * bitmap.height).toInt()
        val radius = max(18, min(52, bitmap.width / 4))
        val step = 3
        var bestScore = Float.MAX_VALUE
        var bestX = cx
        var bestY = cy
        var y = max(patchH / 2, cy - radius)
        while (y <= min(bitmap.height - patchH / 2 - 1, cy + radius)) {
            var x = max(patchW / 2, cx - radius)
            while (x <= min(bitmap.width - patchW / 2 - 1, cx + radius)) {
                val score = match(bitmap, x, y)
                if (score < bestScore) { bestScore = score; bestX = x; bestY = y }
                x += step
            }
            y += step
        }
        val normalizedScore = (1f - bestScore).coerceIn(0f, 1f)
        if (normalizedScore < 0.52f) return ObjectTrackResult(centerX, centerY, normalizedScore, false)
        centerX = bestX / bitmap.width.toFloat()
        centerY = bestY / bitmap.height.toFloat()
        return ObjectTrackResult(centerX, centerY, normalizedScore, true)
    }

    private fun samplePatch(bitmap: Bitmap, cx: Int, cy: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val left = (cx - w / 2).coerceIn(0, bitmap.width - w)
        val top = (cy - h / 2).coerceIn(0, bitmap.height - h)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) out[i++] = gray(bitmap.getPixel(left + x, top + y))
        return out
    }

    private fun match(bitmap: Bitmap, cx: Int, cy: Int): Float {
        val left = cx - patchW / 2
        val top = cy - patchH / 2
        var sum = 0f
        var i = 0
        for (y in 0 until patchH) for (x in 0 until patchW) {
            sum += abs(gray(bitmap.getPixel(left + x, top + y)) - patch[i++])
        }
        return (sum / patch.size).coerceIn(0f, 1f)
    }

    private fun gray(pixel: Int): Float =
        ((pixel shr 16 and 255) * 0.299f + (pixel shr 8 and 255) * 0.587f + (pixel and 255) * 0.114f) / 255f
}