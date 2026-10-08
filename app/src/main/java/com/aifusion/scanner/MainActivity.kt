package com.aifusion.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
    private lateinit var profile: DeviceProfile
    private lateinit var imageCapture: ImageCapture
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
        profile = SmartDeviceEngine.detect(this)
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
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            scanStatus.text = "Camera ready • move around the object"
        }, ContextCompat.getMainExecutor(this))
    }
    private fun startScan() {
        scanning = true
        frameCount = 0
        scanSession.start()
        startButton.text = "Capture frame"
        scanStatus.text = "Scan started • tap to capture • frames: 0"
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
                    scanSession.recordFrame(file)
                    scanStatus.text = "Scanning • frames: $frameCount • capture next angle"
                }
                override fun onError(exception: ImageCaptureException) {
                    scanStatus.text = "Capture error: ${exception.message ?: "unknown"}"
                }
            })
    }

    private fun finishScan() {
        scanning = false
        startButton.text = "Start 3D Scan"
        val result = scanSession.buildResult(frameCount)
        scanStatus.text = "Scan saved • $frameCount frames • OBJ + GLB exported"
        Toast.makeText(this, "Saved: ${result.name}", Toast.LENGTH_LONG).show()
    }

    override fun onBackPressed() {
        if (scanning) finishScan() else super.onBackPressed()
    }
}
