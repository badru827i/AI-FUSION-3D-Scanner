package com.aifusion.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var deviceStatus: TextView
    private lateinit var scanStatus: TextView
    private lateinit var startButton: Button
    private lateinit var miniPreview: Scan3DPreviewView
    private lateinit var trackingOverlay: TrackingOverlayView
    private val objectTracker = ObjectTracker()
    @Volatile private var pendingTargetX: Float? = null
    @Volatile private var pendingTargetY: Float? = null
    private lateinit var coverageText: TextView
    private lateinit var profile: DeviceProfile
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalysis: ImageAnalysis
    private val previewHandler = Handler(Looper.getMainLooper())
    private val aiExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastPreviewMs = 0L
    private lateinit var tracker: CameraTracking
    private lateinit var coverage: ScanCoverage
    private lateinit var scanSession: ScanSession
    private lateinit var depthAi: DepthAiEngine
    private val temporalDepth = TemporalDepthFilter()
    private var scanning = false
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
        miniPreview = findViewById(R.id.miniPreview)
        trackingOverlay = findViewById(R.id.trackingOverlay)
        trackingOverlay.onTargetSelected = { x, y ->
            pendingTargetX = x
            pendingTargetY = y
            scanStatus.text = if (scanning) "Target selected • locking object…" else "Target selected • press Start 3D Scan"
        }
        coverageText = findViewById(R.id.coverageText)

        profile = SmartDeviceEngine.detect(this)
        tracker = CameraTracking(this, profile)
        coverage = ScanCoverage()
        scanSession = ScanSession(this, profile)
        depthAi = DepthAiEngine(this, profile)

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
        finishRequested = false
        frameCount = 0
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
        tracker.start()
        objectTracker.clear()
        trackingOverlay.clearTarget()
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
        if (depthSnapshot != null) {
            scanSession.recordDepthMap(frameIndex, depthSnapshot.first, depthSnapshot.second, depthSnapshot.third)
        }
        frameCount++
        coverage.update(snapshot)
        coverageText.text = coverage.percent().toString() + "% covered"
        scanStatus.text = if (depthSnapshot != null) {
            "Captured • depth map saved • " + coverage.guidance() + " • frames: " + frameCount
        } else {
            "Captured • waiting for stable AI depth • frames: " + frameCount
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
        val now = System.currentTimeMillis()
        val interval = if (profile.mode == ScanMode.LOW_RAM) 900L else 500L
        if (now - lastPreviewMs < interval) {
            image.close()
            return
        }
        lastPreviewMs = now
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap == null) return

        val requestX = pendingTargetX
        val requestY = pendingTargetY
        if (requestX != null && requestY != null) {
            objectTracker.lock(bitmap, requestX, requestY)
            pendingTargetX = null
            pendingTargetY = null
            runOnUiThread { if (scanning) scanStatus.text = "Object locked • tracking + AI depth" }
        }
        val track = objectTracker.update(bitmap)
        runOnUiThread {
            if (scanning && track.tracked) {
                trackingOverlay.setTarget(track.x, track.y, active = true)
            } else if (scanning && objectTracker.isActive()) {
                trackingOverlay.setTracking(false)
            }
        }

        val snapshot = tracker.snapshot()
        if (snapshot.quality == "TOO_FAST") {
            bitmap.recycle()
            runOnUiThread {
                if (scanning) scanStatus.text = "Move slower • AI depth paused • tracking"
            }
            return
        }

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
            val depthBitmap = depthToBitmap(stableDepth, result.width, result.height)
            bitmap.recycle()
            runOnUiThread {
                if (scanning) {
                    coverage.update(snapshot)
                    miniPreview.setDepthPreview(depthBitmap, coverage)
                    coverageText.text = coverage.percent().toString() + "% covered • AI depth • " + result.inferenceMs + "ms"
                    scanStatus.text = "AI Depth " + result.backend + " • " + result.inferenceMs + "ms • DEPTH PREVIEW"
                } else {
                    depthBitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            bitmap.recycle()
            runOnUiThread {
                if (scanning) scanStatus.text = "AI Depth fallback • " + (t.message ?: "inference unavailable")
            }
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
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun finishScan() {
        if (reconstructing) return
        scanning = false
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
        tracker.stop()
        temporalDepth.reset()
        objectTracker.clear()
        trackingOverlay.clearTarget()
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
        tracker.stop()
        temporalDepth.reset()
        objectTracker.clear()
        trackingOverlay.clearTarget()
        previewHandler.removeCallbacksAndMessages(null)
        exportExecutor.shutdownNow()
        aiExecutor.shutdownNow()
        depthAi.close()
        miniPreview.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (scanning) finishScan() else super.onBackPressed()
    }
}
