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
    private lateinit var interpreter: Interpreter
    var backend: String = "CPU"
        private set

    init {
        val cpuOptions = Interpreter.Options().apply {
            setNumThreads(if (profile.mode == ScanMode.LOW_RAM) 2 else 4)
        }

        val model = loadModel()

        if (profile.mode != ScanMode.LOW_RAM) {
            try {
                val compatibility = CompatibilityList()
                if (compatibility.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate(compatibility.bestOptionsForThisDevice)
                    val gpuOptions = Interpreter.Options().apply {
                        setNumThreads(4)
                        addDelegate(delegate)
                    }
                    try {
                        model.rewind()
                        interpreter = Interpreter(model, gpuOptions)
                        gpuDelegate = delegate
                        backend = "GPU"
                    } catch (_: Throwable) {
                        delegate.close()
                    }
                }
            } catch (_: Throwable) {
                // Fall through to CPU.
            }
        }

        if (!::interpreter.isInitialized) {
            model.rewind()
            interpreter = Interpreter(model, cpuOptions)
            backend = "CPU"
        }
    }

    private fun loadModel(): ByteBuffer {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        require(bytes.size > 1_000_000) { "Depth model asset is invalid or incomplete." }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    fun estimate(bitmap: Bitmap): DepthResult {
        val start = SystemClock.elapsedRealtime()
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        try {
            val pixels = IntArray(SIZE * SIZE)
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16) and 255) / 255f)
                input.putFloat(((pixel shr 8) and 255) / 255f)
                input.putFloat((pixel and 255) / 255f)
            }
            input.rewind()

            val outputTensor = interpreter.getOutputTensor(0)
            val outputCount = outputTensor.numElements()
            val output = ByteBuffer.allocateDirect(outputCount * 4)
                .order(ByteOrder.nativeOrder())

            interpreter.run(input, output)
            output.rewind()

            val raw = FloatArray(outputCount)
            for (i in raw.indices) raw[i] = output.float

            val shape = outputTensor.shape()
            val spatial = shape.filter { it > 1 }
            val outHeight = if (spatial.size >= 2) spatial[spatial.size - 2] else SIZE
            val outWidth = if (spatial.size >= 1) spatial[spatial.size - 1] else SIZE
            val planeSize = (outWidth * outHeight).coerceAtMost(raw.size)

            val depth = FloatArray(planeSize)
            val offset = (raw.size - planeSize).coerceAtLeast(0)
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY

            for (i in depth.indices) {
                val value = raw[offset + i]
                depth[i] = value
                minV = min(minV, value)
                maxV = max(maxV, value)
            }

            val range = max(1e-6f, maxV - minV)
            for (i in depth.indices) {
                depth[i] = (depth[i] - minV) / range
            }

            return DepthResult(
                depth = depth,
                width = outWidth,
                height = outHeight,
                backend = backend,
                inferenceMs = SystemClock.elapsedRealtime() - start
            )
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
