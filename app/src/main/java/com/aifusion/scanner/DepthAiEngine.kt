package com.aifusion.scanner

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
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
        // LiteRT Community export of the pretrained Depth Anything V2 Small model.
        // Fixed graph input: normalized RGB, NCHW, 518 x 686.
        private const val MODEL = "depth_anything_v2_small_wi8_afp32.tflite"
        private const val INPUT_WIDTH = 686
        private const val INPUT_HEIGHT = 518
    }

    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private lateinit var interpreter: Interpreter
    var backend: String = "CPU"
        private set

    init {
        val cpuOptions = Interpreter.Options().apply {
            setNumThreads(if (profile.mode == ScanMode.LOW_RAM) 2 else 4)
        }
        val model = loadModel()

        // NNAPI is only an optional delegate. A successful delegate allocation
        // does not prove that every operation runs on an NPU.
        if (android.os.Build.VERSION.SDK_INT >= 27 && profile.mode != ScanMode.LOW_RAM) {
            var delegate: NnApiDelegate? = null
            try {
                val nnDelegate = NnApiDelegate()
                delegate = nnDelegate
                val nnOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                    addDelegate(nnDelegate)
                }
                model.rewind()
                val candidate = Interpreter(model, nnOptions)
                candidate.allocateTensors()
                interpreter = candidate
                nnApiDelegate = nnDelegate
                backend = "Android NNAPI"
            } catch (_: Throwable) {
                try { delegate?.close() } catch (_: Throwable) {}
            }
        }

        // Keep FP32 GPU arithmetic: the published LiteRT model card warns that
        // reduced-precision GPU execution can produce incorrect depth maps.
        if (!::interpreter.isInitialized && profile.mode != ScanMode.LOW_RAM) {
            try {
                val compatibility = CompatibilityList()
                if (compatibility.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate(
                        GpuDelegate.Options().setPrecisionLossAllowed(false)
                    )
                    try {
                        val gpuOptions = Interpreter.Options().apply {
                            setNumThreads(4)
                            addDelegate(delegate)
                        }
                        model.rewind()
                        val candidate = Interpreter(model, gpuOptions)
                        candidate.allocateTensors()
                        interpreter = candidate
                        gpuDelegate = delegate
                        backend = "GPU (FP32)"
                    } catch (_: Throwable) {
                        try { delegate.close() } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {
                // Fall through to CPU.
            }
        }

        if (!::interpreter.isInitialized) {
            model.rewind()
            val candidate = Interpreter(model, cpuOptions)
            candidate.allocateTensors()
            interpreter = candidate
            backend = "CPU"
        }

        val inputShape = interpreter.getInputTensor(0).shape()
        require(inputShape.contentEquals(intArrayOf(1, 3, INPUT_HEIGHT, INPUT_WIDTH))) {
            "Unexpected Depth Anything V2 input shape: " + inputShape.contentToString()
        }
    }

    private fun loadModel(): ByteBuffer {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        require(bytes.size in 25_000_000..30_000_000) {
            "Depth Anything V2 model asset is missing, incomplete, or has an unexpected size."
        }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    fun estimate(bitmap: Bitmap): DepthResult {
        val start = SystemClock.elapsedRealtime()
        val input = ByteBuffer.allocateDirect(
            INPUT_WIDTH * INPUT_HEIGHT * 3 * 4
        ).order(ByteOrder.nativeOrder())

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
        try {
            val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
            scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

            // Model contract is NCHW RGB float32, normalized by ImageNet mean/std.
            for (channel in 0..2) {
                val mean = when (channel) {
                    0 -> 0.485f
                    1 -> 0.456f
                    else -> 0.406f
                }
                val std = when (channel) {
                    0 -> 0.229f
                    1 -> 0.224f
                    else -> 0.225f
                }
                for (pixel in pixels) {
                    val value = when (channel) {
                        0 -> (pixel shr 16) and 255
                        1 -> (pixel shr 8) and 255
                        else -> pixel and 255
                    }
                    input.putFloat((value / 255f - mean) / std)
                }
            }
            input.rewind()

            val outputTensor = interpreter.getOutputTensor(0)
            require(outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                "Unsupported depth output type: " + outputTensor.dataType()
            }
            val outputShape = outputTensor.shape()
            require(outputShape.contentEquals(intArrayOf(1, INPUT_HEIGHT, INPUT_WIDTH))) {
                "Unexpected Depth Anything V2 output shape: " + outputShape.contentToString()
            }
            val output = ByteBuffer.allocateDirect(outputTensor.numElements() * 4)
                .order(ByteOrder.nativeOrder())

            interpreter.run(input, output)
            output.rewind()

            val raw = FloatArray(outputTensor.numElements())
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            for (i in raw.indices) {
                val value = output.float
                require(value.isFinite()) { "Depth model returned a non-finite value." }
                raw[i] = value
                minV = min(minV, value)
                maxV = max(maxV, value)
            }

            // Keep this as a normalized relative-depth preview only. This is not
            // metric distance; calibrated stereo is a separate geometry path.
            val range = max(1e-6f, maxV - minV)
            for (i in raw.indices) raw[i] = (raw[i] - minV) / range

            return DepthResult(
                depth = raw,
                width = INPUT_WIDTH,
                height = INPUT_HEIGHT,
                backend = backend,
                inferenceMs = SystemClock.elapsedRealtime() - start
            )
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    override fun close() {
        if (::interpreter.isInitialized) interpreter.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
    }
}
