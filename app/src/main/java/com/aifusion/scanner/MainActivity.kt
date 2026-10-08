package com.aifusion.scanner

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var deviceStatus: TextView
    private lateinit var scanStatus: TextView
    private lateinit var startButton: Button
    private lateinit var miniPreview: Scan3DPreviewView
    private lateinit var coverageText: TextView
    private lateinit var profile: DeviceProfile
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalysis: ImageAnalysis
    private val previewHandler = Handler(Looper.getMainLooper())
    private var lastPreviewMs = 0L
    private lateinit var tracker: CameraTracking
    private lateinit var coverage: ScanCoverage
    private var scanning = false
    private var frameCount = 0
    private lateinit var scanSession: ScanSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        deviceStatus = findViewById(R.id.deviceStatus)
        scanStatus = findViewById(R.id.scanStatus)
        startButton = findViewById(R.id.startScan)
        miniPreview = findViewById(R.id.miniPreview)
        coverageText = findViewById(R.id.coverageText)
        profile = SmartDeviceEngine.detect(this)
        tracker = CameraTracking(this, profile)
        coverage = ScanCoverage()
        scanSession = ScanSession(this, profile)

        deviceStatus.text = "AI-FUSION • " + SmartDeviceEngine.summary(profile)
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
                .also { analysis -> analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image -> analyzeLiveFrame(image) } }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalysis)
            scanStatus.text = "Camera ready • point at the object"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startScan() {
        scanning = true
        frameCount = 0
        coverage.reset()
        miniPreview.clear()
        tracker.start()
        scanSession.start()
        startButton.text = "Stop 3D Scan"
        coverageText.text = "0% covered • LIVE"
        scanStatus.text = "AI 3D scanning live • " + coverage.guidance() + " • frames: 0"
        captureFrame()
    }

    private fun captureFrame() {
        if (!scanning) return
        val file = File(scanSession.framesDir, "frame_%04d.jpg".format(frameCount))
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    frameCount++
                    val snapshot = tracker.snapshot()
                    scanSession.recordFrame(file)
                    scanSession.recordTracking(frameCount, snapshot)
                    coverage.update(snapshot)
                    updateMiniPreview(file)
                    coverageText.text = coverage.percent().toString() + "% covered"
                    scanStatus.text = "AI 3D LIVE • " + coverage.guidance() + " • frames: " + frameCount
                    if (scanning) previewHandler.postDelayed({ captureFrame() }, if (profile.mode == ScanMode.LOW_RAM) 900L else 550L)
                }
                override fun onError(exception: ImageCaptureException) {
                    scanStatus.text = "Capture error: " + (exception.message ?: "unknown")
                }
            })
    }

    private fun analyzeLiveFrame(image: ImageProxy) {
        if (!scanning) { image.close(); return }
        val now = System.currentTimeMillis()
        val interval = if (profile.mode == ScanMode.LOW_RAM) 250L else 140L
        if (now - lastPreviewMs < interval) { image.close(); return }
        lastPreviewMs = now
        val bitmap = imageToBitmap(image)
        image.close()
        if (bitmap != null) runOnUiThread {
            if (scanning) {
                val snapshot = tracker.snapshot()
                coverage.update(snapshot)
                miniPreview.setPreview(bitmap, coverage)
                coverageText.text = coverage.percent().toString() + "% covered • LIVE 3D"
            } else bitmap.recycle()
        }
    }

    private fun imageToBitmap(image: ImageProxy): android.graphics.Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val yb=image.planes[0].buffer; val ub=image.planes[1].buffer; val vb=image.planes[2].buffer
        val y=ByteArray(yb.remaining()).also{yb.get(it)}; val u=ByteArray(ub.remaining()).also{ub.get(it)}; val v=ByteArray(vb.remaining()).also{vb.get(it)}
        val nv21=ByteArray(y.size+u.size+v.size)
        System.arraycopy(y,0,nv21,0,y.size)
        var p=y.size; var i=0
        while(i<v.size && i<u.size){nv21[p++]=v[i];nv21[p++]=u[i];i++}
        val out=ByteArrayOutputStream()
        YuvImage(nv21,ImageFormat.NV21,image.width,image.height,null).compressToJpeg(Rect(0,0,image.width,image.height),if(profile.mode==ScanMode.LOW_RAM)45 else 55,out)
        val bytes=out.toByteArray()
        val opts=BitmapFactory.Options().apply{inSampleSize=if(profile.mode==ScanMode.LOW_RAM)12 else 8}
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
    }

    private fun updateMiniPreview(file: File) {
        val options = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        if (bitmap != null) miniPreview.setPreview(bitmap, coverage)
    }

    private fun finishScan() {
        scanning = false
        tracker.stop()
        startButton.text = "Start 3D Scan"
        val result = scanSession.buildResult(frameCount)
        scanStatus.text = "Scan saved • " + frameCount + " frames • " + coverage.percent() + "% guide coverage • OBJ + GLB exported"
        Toast.makeText(this, "Saved: " + result.name, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        tracker.stop()
        miniPreview.clear()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (scanning) finishScan() else super.onBackPressed()
    }
}
