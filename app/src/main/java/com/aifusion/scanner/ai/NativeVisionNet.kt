package com.aifusion.scanner.ai

import kotlin.math.tanh

/**
 * AF-VisionNet Android inference contract.
 * No fake checkpoint is bundled: inference stays unavailable until trained,
 * validated weights are exported and installed in app assets.
 */
class NativeVisionNet(private val weights: FloatArray?, private val inputChannels: Int = 3) {
    val isReady: Boolean get() = weights != null && weights.size == inputChannels + 1

    fun infer(rgb: FloatArray, width: Int, height: Int): FloatArray? {
        require(width > 0 && height > 0 && rgb.size == width * height * inputChannels)
        val w = weights ?: return null
        if (w.size != inputChannels + 1) return null
        return FloatArray(width * height) { pixel ->
            var score = w[inputChannels]
            for (channel in 0 until inputChannels) score += rgb[pixel * inputChannels + channel] * w[channel]
            (tanh(score.toDouble()) * 0.5 + 0.5).toFloat()
        }
    }
}
