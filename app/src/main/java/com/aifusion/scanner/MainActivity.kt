package com.aifusion.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.util.Size
import android.view.View
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var deviceStatus: TextView
    private lateinit var scanStatus: TextView
    private lateinit var startButton: Button
    private lateinit var trackingLockButton: Button
    private lateinit var miniPreview: Scan3DPreviewView
    private lateinit var trackingOverlay: TrackingOverlayView
    private lateinit var hologramOverlay: HologramAnalysisOverlayView
    private val objectTracker = ObjectTracker()
    @Volatile private var pendingTargetX: Float? = null
    @Volatile private var pendingTargetY: Float? = null
    private lateinit var coverageText: TextView
    private lateinit var profile: DeviceProfile
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalysis: ImageAnalysis
    private val previewHandler = Handler(Looper.getMainLooper())
    // Keep camera tracking responsive while the heavier depth model runs independently.
    private val aiExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val depthExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val depthInFlight = AtomicBoolean(false)
    private var lastTrackMs = 0L
    private var lastPreviewMs = 0L
    private lateinit var tracker: CameraTracking
    private lateinit var coverage: ScanCoverage
    private lateinit var scanSession: ScanSession
    private lateinit var depthAi: DepthAiEngine
    private val temporalDepth = TemporalDepthFilter()
    @Volatile private var scanning = false
    private var frameCount = 0
    @Volatile private var captureInFlight = false
    private var finishRequested = false
    private var consecutiveCaptureErrors = 0
    private var reconstructing = false
    private val exportExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val depthLock = Any()
    private var latestDepthData: FloatArray? = null
    private var latestDepthWidth = 0
    private var latestDepthHeight = 0
    private var latestDepthElapsedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        deviceStatus = findViewById(R.id.deviceStatus)
        scanStatus = findViewById(R.id.scanStatus)
        startButton = findViewById(R.id.startScan)
        trackingLockButton = findViewById(R.id.trackingLock)
        trackingLockButton.setOnClickListener {
            if (objectTracker.isActive()) {
                objectTracker.clear()
                pendingTargetX = null
                pendingTargetY = null
                trackingOverlay.clearTarget()
                trackingLockButton.text = "Lock Target"
                scanStatus.text = "Tracking unlocked • tap object to lock again"
            } else {
                scanStatus.text = if (scanning) "Tap an object in the preview to lock tracking" else "Start scan, then tap an object to lock tracking"
            }
        }
        miniPreview = findViewById(R.id.miniPreview)
        miniPreview.visibility = View.GONE
        trackingOverlay = findViewById(R.id.trackingOverlay)
        hologramOverlay = findViewById(R.id.hologramOverlay)
        trackingOverlay.onTargetSelected = { x, y ->
            pendingTargetX = x
            pendingTargetY = y
            scanStatus.text = if (scanning) "Target selected • locking object…" else "Target selected • press Start 3D Scan"
        }
        coverageText = findViewById(R.id.coverageText)
        coverageText.visibility = View.GONE

        profile = SmartDeviceEngine.detect(this)
        tracker = CameraTracking(this, profile)
        coverage = ScanCoverage()
        scanSession = ScanSession(this, profile)
        depthAi = DepthAiEngine(this, profile)
        hologramOverlay.setLowPowerMode(profile.mode == ScanMode.LOW_RAM)
        miniPreview.setLowPowerMode(profile.mode == ScanMode.LOW_RAM)

        deviceStatus.text = "AI-FUSION • " + SmartDeviceEngine.summary(profile) + " • Depth AI " + depthAi.backend
        startButton.setOnClickListener { if (!scanning) startScan() else finishScan() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else scanStatus.text = "Camera permission is required for 3D scanning."
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(if (profile.mode == ScanMode.LOW_RAM) 65 else 85)
                .build()
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(if (profile.mode == ScanMode.LOW_RAM) Size(480, 360) else Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(1)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(aiExecutor) { image -> analyzeLiveFrame(image) }
                }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalysis)
            scanStatus.text = "Camera ready • point at the object"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startScan() {
        if (reconstructing || captureInFlight) return
        try {
            scanSession.start()
        } catch (t: Throwable) {
            scanStatus.text = "Cannot start scan: " + (t.message ?: "storage unavailable")
            return
        }
        scanning = true
        hologramOverlay.setActive(true)
        finishRequested = false
        frameCount = 0
        lastTrackMs = 0L
        lastPreviewMs = 0L
        consecutiveCaptureErrors = 0
        synchronized(depthLock) {
            latestDepthData = null
            latestDepthWidth = 0
            latestDepthHeight = 0
            latestDepthElapsedMs = 0L
        }
        coverage.reset()
        miniPreview.clear()
        coverageText.visibility = View.GONE
        tracker.start()
        objectTracker.clear()
        trackingOverlay.clearTarget()
        trackingLockButton.text = "Lock Target"
        temporalDepth.reset()
        startButton.isEnabled = true
        startButton.text = "Stop 3D Scan"
        coverageText.text = "0% covered • LIVE"
        scanStatus.text = "AI Depth " + depthAi.backend + " • tap object to lock • frames: 0"
        captureFrame()
    }

    private fun captureFrame() {
        if (!scanning || captureInFlight) return
        captureInFlight = true
        val file = File(scanSession.framesDir, "frame_%04d.jpg".format(frameCount))
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        try {
            imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        captureInFlight = false
                        consecutiveCaptureErrors = 0
                        if (finishRequested) {
                            if (file.exists()) recordCapturedFrame(file)
                            finishRequested = false
                            finalizeScan()
                            return
                        }
                        if (!scanning) {
                            file.delete()
                            return
                        }
                        recordCapturedFrame(file)
                        if (scanning) {
                            val delay = if (profile.mode == ScanMode.LOW_RAM) 1200L else 850L
                            previewHandler.postDelayed({ captureFrame() }, delay)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        captureInFlight = false
                        file.delete()
                        handleCaptureFailure(exception.message ?: "camera capture failed")
                    }
                })
        } catch (t: Throwable) {
            captureInFlight = false
            file.delete()
            handleCaptureFailure(t.message ?: "camera capture failed")
        }
    }

    private fun recordCapturedFrame(file: File) {
        if (!file.exists() || file.length() == 0L) {
            scanStatus.text = "Empty camera frame skipped"
            return
        }
        val snapshot = tracker.snapshot()
        if (snapshot.quality == "TOO_FAST") {
            file.delete()
            scanStatus.text = "Move slower • frame skipped • tracking"
            return
        }

        val frameIndex = frameCount
        scanSession.recordFrame(file)
        scanSession.recordTracking(frameIndex, snapshot)
        val depthSnapshot = synchronized(depthLock) {
            val data = latestDepthData
            if (data != null && latestDepthElapsedMs > 0L &&
                SystemClock.elapsedRealtime() - latestDepthElapsedMs <= 4000L) {
                Triple(data.copyOf(), latestDepthWidth, latestDepthHeight)
            } else null
        }
        var depthSaved = false
        var depthSaveError: String? = null
        if (depthSnapshot != null) {
            try {
                scanSession.recordDepthMap(frameIndex, depthSnapshot.first, depthSnapshot.second, depthSnapshot.third)
                depthSaved = true
            } catch (t: Throwable) {
                depthSaveError = t.message
            }
        }
        frameCount++
        coverage.update(snapshot)
        coverageText.text = coverage.percent().toString() + "% covered"
        scanStatus.text = when {
            depthSaved -> "Captured • depth map saved • " + coverage.guidance() + " • frames: " + frameCount
            depthSnapshot == null -> "Captured • waiting for stable AI depth • frames: " + frameCount
            else -> "Frame saved • depth map could not be stored" +
                (depthSaveError?.let { ": " + it } ?: "") + " • frames: " + frameCount
        }
    }

    private fun handleCaptureFailure(message: String) {
        if (finishRequested) {
            finishRequested = false
            finalizeScan()
            return
        }
        if (!scanning) return
        consecutiveCaptureErrors++
        scanStatus.text = "Camera capture failed (" + consecutiveCaptureErrors + "): " + message
        if (consecutiveCaptureErrors >= 5) {
            scanning = false
            scanStatus.text = "Camera capture repeatedly failed • preserving saved frames"
            finishScan()
            return
        }
        val exponent = (consecutiveCaptureErrors - 1).coerceIn(0, 3)
        val retryDelay = minOf(4000L, 500L * (1L shl exponent))
        previewHandler.postDelayed({ captureFrame() }, retryDelay)
    }

    private fun analyzeLiveFrame(image: ImageProxy) {
        if (!scanning) {
            image.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val trackingInterval = if (profile.mode == ScanMode.LOW_RAM) 260L else 130L
        val hasPendingTarget = pendingTargetX != null && pendingTargetY != null
        if (!hasPendingTarget && now - lastTrackMs < trackingInterval) {
            image.close()
            return
        }
        lastTrackMs = now

        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap == null) return

        val requestX = pendingTargetX
        val requestY = pendingTargetY
        if (requestX != null && requestY != null) {
            // PreviewView uses FILL_CENTER, so undo its centre crop before locking a pixel patch.
            val framePoint = viewToFramePoint(requestX, requestY, bitmap)
            objectTracker.lock(bitmap, framePoint.first, framePoint.second)
            pendingTargetX = null
            pendingTargetY = null
            runOnUiThread {
                if (scanning && objectTracker.isActive()) {
                    trackingLockButton.text = "Unlock Target"
                    scanStatus.text = "Object locked • smoothing tracking"
                } else if (scanning) {
                    trackingLockButton.text = "Lock Target"
                    scanStatus.text = "Target not lockable • tap a clearer feature"
                }
            }
        }

        // Tracking is updated on its own cadence and no longer waits for AI-depth inference.
        val snapshot = tracker.snapshot()
        val track = objectTracker.update(bitmap, snapshot.motion)
        val objectLockActive = objectTracker.isActive()
        val displayTarget = frameToViewTarget(track.x, track.y, track.width, track.height, bitmap)
        runOnUiThread {
            if (!scanning) return@runOnUiThread
            hologramOverlay.updateTarget(
                displayTarget.x, displayTarget.y, track.tracked,
                displayTarget.width, displayTarget.height, objectLockActive
            )
            if (track.tracked) {
                trackingOverlay.setTarget(
                    displayTarget.x, displayTarget.y,
                    width = displayTarget.width.coerceIn(0.08f, 0.65f),
                    height = displayTarget.height.coerceIn(0.08f, 0.65f),
                    active = true
                )
            } else if (objectLockActive) {
                trackingOverlay.setTracking(false)
            }
        }

        if (snapshot.quality == "TOO_FAST") {
            bitmap.recycle()
            runOnUiThread {
                if (scanning) scanStatus.text = "Move slower • AI depth paused • tracking continues"
            }
            return
        }

        // Depth is intentionally less frequent; it must not block the tracking updates above.
        val depthInterval = if (profile.mode == ScanMode.LOW_RAM) 1100L else 650L
        if (now - lastPreviewMs < depthInterval || !depthInFlight.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        lastPreviewMs = now
        try {
            depthExecutor.execute {
                processDepthFrame(bitmap, snapshot, track, objectLockActive)
            }
        } catch (t: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            depthInFlight.set(false)
        }
    }

    private fun processDepthFrame(
        bitmap: Bitmap,
        snapshot: TrackingSnapshot,
        track: ObjectTrackResult,
        objectLockActive: Boolean
    ) {
        var outputBitmap: Bitmap? = null
        try {
            val result = depthAi.estimate(bitmap)
            val stableDepth = temporalDepth.filter(
                result.depth,
                result.width,
                result.height,
                snapshot.motion,
                snapshot.quality
            )
            if (scanning) synchronized(depthLock) {
                latestDepthData = stableDepth.copyOf()
                latestDepthWidth = result.width
                latestDepthHeight = result.height
                latestDepthElapsedMs = SystemClock.elapsedRealtime()
            }
            val depthPreview = depthToBitmap(stableDepth, result.width, result.height)
            outputBitmap = depthPreview
            runOnUiThread {
                if (scanning && !isDestroyed) {
                    coverage.update(snapshot)
                    miniPreview.setDepthPreview(
                        depthPreview, coverage, track.x, track.y, objectLockActive, track.tracked
                    )
                    coverageText.visibility = View.VISIBLE
                    coverageText.text = coverage.percent().toString() + "% covered • AI depth • " + result.inferenceMs + "ms"
                    scanStatus.text = when {
                        objectLockActive && !track.tracked ->
                            "TARGET LOST • hold still and reveal the same object • relocalising"
                        objectLockActive ->
                            "TARGET LOCKED • confidence " + (track.score * 100f).toInt().coerceIn(0, 100) +
                                "% • AI Depth " + result.inferenceMs + "ms"
                        else ->
                            "AI Depth " + result.backend + " • " + result.inferenceMs + "ms • DEPTH PREVIEW"
                    }
                } else if (!depthPreview.isRecycled) {
                    depthPreview.recycle()
                }
            }
            outputBitmap = null // Ownership passes to the main-thread preview callback.
        } catch (t: Throwable) {
            runOnUiThread {
                if (scanning && !isDestroyed) {
                    scanStatus.text = "AI Depth fallback • " + (t.message ?: "inference unavailable")
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            outputBitmap?.let { if (!it.isRecycled) it.recycle() }
            depthInFlight.set(false)
        }
    }

    private fun depthToBitmap(depth: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val v = (depth[i].coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private data class DisplayTarget(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    /**
     * Translate touch coordinates from the PreviewView's FILL_CENTER viewport
     * into the rotated ImageAnalysis bitmap coordinates.
     */
    private fun viewToFramePoint(x: Float, y: Float, bitmap: Bitmap): Pair<Float, Float> {
        val viewWidth = trackingOverlay.width.toFloat().coerceAtLeast(1f)
        val viewHeight = trackingOverlay.height.toFloat().coerceAtLeast(1f)
        val viewAspect = viewWidth / viewHeight
        val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        return if (imageAspect > viewAspect) {
            val visible = (viewAspect / imageAspect).coerceIn(0.01f, 1f)
            val offset = (1f - visible) * 0.5f
            ((offset + x.coerceIn(0f, 1f) * visible).coerceIn(0f, 1f)) to y.coerceIn(0f, 1f)
        } else {
            val visible = (imageAspect / viewAspect).coerceIn(0.01f, 1f)
            val offset = (1f - visible) * 0.5f
            x.coerceIn(0f, 1f) to (offset + y.coerceIn(0f, 1f) * visible).coerceIn(0f, 1f)
        }
    }

    /** Translate the track box back into screen coordinates for the live overlays. */
    private fun frameToViewTarget(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        bitmap: Bitmap
    ): DisplayTarget {
        val viewWidth = trackingOverlay.width.toFloat().coerceAtLeast(1f)
        val viewHeight = trackingOverlay.height.toFloat().coerceAtLeast(1f)
        val viewAspect = viewWidth / viewHeight
        val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        return if (imageAspect > viewAspect) {
            val visible = (viewAspect / imageAspect).coerceIn(0.01f, 1f)
            val offset = (1f - visible) * 0.5f
            DisplayTarget(
                ((x - offset) / visible).coerceIn(0f, 1f),
                y.coerceIn(0f, 1f),
                width / visible,
                height
            )
        } else {
            val visible = (imageAspect / viewAspect).coerceIn(0.01f, 1f)
            val offset = (1f - visible) * 0.5f
            DisplayTarget(
                x.coerceIn(0f, 1f),
                ((y - offset) / visible).coerceIn(0f, 1f),
                width,
                height / visible
            )
        }
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        // YUV_420_888 planes may have padded row strides and interleaved chroma.
        // Read each plane using its own strides instead of assuming contiguous data.
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val nv21 = ByteArray(width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2))
        val yBase = yBuffer.position()
        var target = 0
        for (row in 0 until height) {
            val rowBase = yBase + row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[target++] = yBuffer.get(rowBase + col * yPlane.pixelStride)
            }
        }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        for (row in 0 until chromaHeight) {
            val uRow = uBase + row * uPlane.rowStride
            val vRow = vBase + row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[target++] = vBuffer.get(vRow + col * vPlane.pixelStride)
                nv21[target++] = uBuffer.get(uRow + col * uPlane.pixelStride)
            }
        }

        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(
                Rect(0, 0, width, height),
                if (profile.mode == ScanMode.LOW_RAM) 40 else 50,
                out
            )
        val bytes = out.toByteArray()
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (profile.mode == ScanMode.LOW_RAM) 16 else 8
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        )
        if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
        return rotated
    }

    private fun finishScan() {
        if (reconstructing) return
        scanning = false
        hologramOverlay.setActive(false)
        trackingLockButton.text = "Lock Target"
        if (captureInFlight) {
            finishRequested = true
            tracker.stop()
            objectTracker.clear()
            trackingOverlay.clearTarget()
            temporalDepth.reset()
            startButton.isEnabled = false
            startButton.text = "Finishing…"
            scanStatus.text = "Finishing current camera frame…"
            return
        }
        finalizeScan()
    }

    private fun finalizeScan() {
        scanning = false
        hologramOverlay.setActive(false)
        tracker.stop()
        temporalDepth.reset()
        objectTracker.clear()
        trackingOverlay.clearTarget()
        trackingLockButton.text = "Lock Target"
        miniPreview.setCompleted()
        finishRequested = false

        if (frameCount <= 0) {
            reconstructing = false
            startButton.isEnabled = true
            startButton.text = "Start 3D Scan"
            scanStatus.text = "No frames saved • improve lighting and try again"
            return
        }

        reconstructing = true
        startButton.isEnabled = false
        startButton.text = "Building 3D…"
        scanStatus.text = "Saved " + frameCount + " frames • building depth mesh…"
        val savedFrameCount = frameCount
        val savedCoverage = coverage.percent()
        exportExecutor.execute {
            try {
                val result = scanSession.buildResult(savedFrameCount)
                runOnUiThread {
                    reconstructing = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    startButton.isEnabled = true
                    startButton.text = "Start 3D Scan"
                    scanStatus.text = "Depth mesh exported • " + savedFrameCount +
                        " frames • " + savedCoverage + "% guide coverage • OBJ + GLB"
                    Toast.makeText(this, "Saved: " + result.name, Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    reconstructing = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    startButton.isEnabled = true
                    startButton.text = "Start 3D Scan"
                    scanStatus.text = "Frames preserved • mesh export failed: " +
                        (t.message ?: "AI depth unavailable")
                    Toast.makeText(this, "Scan saved, but no valid depth mesh was exported", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        scanning = false
        hologramOverlay.setActive(false)
        tracker.stop()
        temporalDepth.reset()
        objectTracker.clear()
        trackingOverlay.clearTarget()
        previewHandler.removeCallbacksAndMessages(null)
        exportExecutor.shutdownNow()
        aiExecutor.shutdownNow()
        depthExecutor.shutdownNow()
        depthAi.close()
        miniPreview.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (scanning) finishScan() else super.onBackPressed()
    }
}
