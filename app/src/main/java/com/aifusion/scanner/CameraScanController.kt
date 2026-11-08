package com.aifusion.scanner

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class CameraFrameStats(
    val frameNumber: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
    val meanLuma: Double,
    val lumaStdDev: Double,
    val underexposedRatio: Double,
    val overexposedRatio: Double,
    val edgeStrength: Double,
    val sampleCount: Int
)

/**
 * Offline CameraX input stage. Sampled luminance statistics feed the advisor;
 * they are not depth, geometry, or focus measurements.
 */
class CameraScanController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (CameraFrameStats?) -> Unit
) {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val frameCounter = AtomicLong(0)
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { image -> analyzeFrame(image) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
                analysisUseCase = analysis
            } catch (_: Exception) {
                onFrame(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val limit = buffer.limit()
            val gridWidth = minOf(48, image.width).coerceAtLeast(1)
            val gridHeight = minOf(48, image.height).coerceAtLeast(1)
            val values = FloatArray(gridWidth * gridHeight)
            var count = 0
            var sum = 0.0
            var under = 0
            var over = 0

            for (gy in 0 until gridHeight) {
                val y = gy * image.height / gridHeight
                for (gx in 0 until gridWidth) {
                    val x = gx * image.width / gridWidth
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    if (offset < 0 || offset >= limit) continue
                    val value = (buffer.get(offset).toInt() and 0xff).toFloat()
                    values[gy * gridWidth + gx] = value
                    count++
                    sum += value
                    if (value <= 32f) under++
                    if (value >= 223f) over++
                }
            }

            val mean = if (count == 0) 0.0 else sum / count
            var squared = 0.0
            var edgeSum = 0.0
            var edgeCount = 0
            if (count > 0) {
                for (gy in 0 until gridHeight) {
                    for (gx in 0 until gridWidth) {
                        val index = gy * gridWidth + gx
                        val delta = values[index].toDouble() - mean
                        squared += delta * delta
                        if (gx > 0) {
                            edgeSum += kotlin.math.abs(values[index].toDouble() - values[index - 1])
                            edgeCount++
                        }
                        if (gy > 0) {
                            edgeSum += kotlin.math.abs(values[index].toDouble() - values[index - gridWidth])
                            edgeCount++
                        }
                    }
                }
            }

            val stdDev = if (count == 0) 0.0 else sqrt(squared / count)
            onFrame(
                CameraFrameStats(
                    frameNumber = frameCounter.incrementAndGet(),
                    width = image.width,
                    height = image.height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    timestampNanos = image.imageInfo.timestamp,
                    meanLuma = mean,
                    lumaStdDev = stdDev,
                    underexposedRatio = if (count == 0) 0.0 else under.toDouble() / count,
                    overexposedRatio = if (count == 0) 0.0 else over.toDouble() / count,
                    edgeStrength = if (edgeCount == 0) 0.0 else edgeSum / edgeCount,
                    sampleCount = count
                )
            )
        } catch (_: Exception) {
            // One malformed frame must not stop subsequent analysis.
        } finally {
            image.close()
        }
    }

    fun stop() {
        analysisUseCase?.let { useCase ->
            runCatching { cameraProvider?.unbind(useCase) }
        }
        analysisUseCase = null
        onFrame(null)
    }

    fun close() {
        stop()
        analyzerExecutor.shutdown()
    }
}
