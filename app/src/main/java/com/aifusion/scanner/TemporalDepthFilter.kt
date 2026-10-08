package com.aifusion.scanner

import kotlin.math.exp

/**
 * Stabilizes monocular depth across video frames.
 * It does not change the MiDaS model weights; it reduces frame-to-frame flicker
 * using motion-aware temporal blending.
 */
class TemporalDepthFilter {
    private var previous: FloatArray? = null
    private var width = 0
    private var height = 0

    fun reset() {
        previous = null
        width = 0
        height = 0
    }

    fun filter(
        depth: FloatArray,
        width: Int,
        height: Int,
        motion: Float,
        quality: String
    ): FloatArray {
        val old = previous
        if (old == null || old.size != depth.size || this.width != width || this.height != height) {
            previous = depth.copyOf()
            this.width = width
            this.height = height
            return depth
        }

        // More blending while the camera is stable; less while moving quickly.
        val alpha = when (quality) {
            "GOOD" -> 0.22f
            "MOVE_SLOWER" -> 0.48f
            else -> 0.82f
        }.coerceIn(0.15f, 0.90f)

        // Smooth abrupt sensor changes without adding much CPU work.
        val motionPenalty = (1f - exp(-motion * 0.08f)).coerceIn(0f, 0.65f)
        val blend = (alpha + motionPenalty * 0.25f).coerceIn(0.18f, 0.90f)

        val result = FloatArray(depth.size)
        for (i in depth.indices) {
            result[i] = old[i] * blend + depth[i] * (1f - blend)
        }

        previous = result.copyOf()
        return result
    }
}
