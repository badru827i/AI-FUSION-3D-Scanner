package com.aifusion.scanner

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs

/**
 * Deterministic quality gate for normalized monocular depth maps.
 *
 * This measures whether a depth surface contains enough valid, non-flat structure
 * to form a useful 2.5D preview. It is not a learned confidence model and does
 * not convert relative MiDaS depth into metric distance.
 */
data class DepthQuality(
    val validFraction: Float,
    val normalizedRange: Float,
    val meanGradient: Float,
    val score: Int,
    val usable: Boolean
)

object DepthQualityAnalyzer {
    fun analyze(values: FloatArray, width: Int, height: Int): DepthQuality {
        require(width >= 2 && height >= 2) { "Depth map must be at least 2x2" }
        require(width.toLong() * height.toLong() <= values.size.toLong()) {
            "Depth buffer is smaller than its dimensions"
        }

        val count = width * height
        var valid = 0
        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val value = values[i]
            if (value.isFinite()) {
                valid++
                minValue = minOf(minValue, value)
                maxValue = maxOf(maxValue, value)
            }
        }

        val validFraction = valid.toFloat() / count
        val range = if (valid > 0) (maxValue - minValue).coerceAtLeast(0f) else 0f
        var gradientTotal = 0f
        var gradientSamples = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val center = values[index]
                if (!center.isFinite()) continue
                if (x + 1 < width) {
                    val right = values[index + 1]
                    if (right.isFinite()) {
                        gradientTotal += abs(right - center)
                        gradientSamples++
                    }
                }
                if (y + 1 < height) {
                    val down = values[index + width]
                    if (down.isFinite()) {
                        gradientTotal += abs(down - center)
                        gradientSamples++
                    }
                }
            }
        }
        val meanGradient = if (gradientSamples > 0) gradientTotal / gradientSamples else 0f
        val rangeScore = (range / 0.20f).coerceIn(0f, 1f)
        val detailScore = (meanGradient / 0.035f).coerceIn(0f, 1f)
        val score = ((validFraction * 0.45f + rangeScore * 0.35f + detailScore * 0.20f) * 100f)
            .toInt().coerceIn(0, 100)
        val usable = validFraction >= 0.98f && range >= 0.025f && meanGradient >= 0.0005f

        return DepthQuality(validFraction, range, meanGradient, score, usable)
    }

    fun analyzeFile(file: File): DepthQuality {
        require(file.isFile && file.length() >= 12L) { "Depth map file is missing or truncated" }
        return DataInputStream(file.inputStream().buffered()).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            require(width in 2..1024 && height in 2..1024) { "Invalid depth-map dimensions" }
            val count = width.toLong() * height.toLong()
            require(count <= 1_048_576L) { "Depth map is too large" }
            val bytes = ByteArray(count.toInt())
            input.readFully(bytes)
            val values = FloatArray(bytes.size) { (bytes[it].toInt() and 0xff) / 255f }
            analyze(values, width, height)
        }
    }
}
