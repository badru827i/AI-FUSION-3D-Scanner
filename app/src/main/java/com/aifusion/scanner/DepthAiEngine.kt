package com.aifusion.scanner

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class DepthResult(
    val depth: FloatArray,
    val width: Int,
    val height: Int,
    val backend: String,
    val inferenceMs: Long
)

class DepthAiEngine(
    private val context: Context,
    private val profile: DeviceProfile
) : AutoCloseable {
    companion object {
        private const val MODEL = "midas_v21_small_256.tflite"
        private const val SIZE = 256
    }

    private var gpuDelegate: GpuDelegate? = null
    private val interpreter: Interpreter
    val backend: String

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(if (profile.mode == ScanMode.LOW_RAM) 2 else 4)
        }
        var selected = "CPU"
        if (profile.mode != ScanMode.LOW_RAM) {
            try {
                val compatibility = CompatibilityList()
                if (compatibility.isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate(compatibility.bestOptionsForThisDevice)
                    options.addDelegate(gpuDelegate)
                    selected = "GPU"
                }
            } catch (_: Throwable) {
                gpuDelegate = null
            }
        }
        interpreter = Interpreter(loadModel(), options)
        backend = selected
    }

    private fun loadModel(): ByteBuffer {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    fun estimate(bitmap: Bitmap): DepthResult {
        val start = SystemClock.elapsedRealtime()
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        for (pixel in pixels) {
            input.putFloat(((pixel shr 16) and 255) / 255f)
            input.putFloat(((pixel shr 8) and 255) / 255f)
            input.putFloat((pixel and 255) / 255f)
        }
        input.rewind()

        val output = Array(1) { Array(SIZE) { FloatArray(SIZE) } }
        interpreter.run(input, output)

        val depth = FloatArray(SIZE * SIZE)
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val v = output[0][y][x]
            depth[y * SIZE + x] = v
            minV = min(minV, v)
            maxV = max(maxV, v)
        }
        val range = max(1e-6f, maxV - minV)
        for (i in depth.indices) depth[i] = (depth[i] - minV) / range

        if (scaled !== bitmap) scaled.recycle()
        return DepthResult(depth, SIZE, SIZE, backend, SystemClock.elapsedRealtime() - start)
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
