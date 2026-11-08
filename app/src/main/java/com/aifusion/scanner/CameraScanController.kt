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

data class CameraFrameStats(
    val frameNumber: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
    val meanLuma: Double
)

/**
 * Offline CameraX input stage. It deliberately does not claim to produce depth
 * or geometry; it supplies measured frame metadata to the future vision pipeline.
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
            val buffer = image.planes.firstOrNull()?.buffer ?: return
            val limit = buffer.limit()
            val stride = (limit / 512).coerceAtLeast(1)
            var sum = 0L
            var samples = 0
            var index = 0
            while (index < limit) {
                sum += buffer.get(index).toInt() and 0xff
                samples++
                index += stride
            }
            val mean = if (samples == 0) 0.0 else sum.toDouble() / samples
            onFrame(
                CameraFrameStats(
                    frameNumber = frameCounter.incrementAndGet(),
                    width = image.width,
                    height = image.height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    timestampNanos = image.imageInfo.timestamp,
                    meanLuma = mean
                )
            )
        } catch (_: Exception) {
            // A malformed or dropped frame must not stop subsequent analysis.
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
