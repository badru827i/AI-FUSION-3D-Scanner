package com.aifusion.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aifusion.scanner.ai.ScanIntelligenceEngine

class MainActivity : AppCompatActivity() {
    private lateinit var viewport: MeshViewport
    private lateinit var cameraStatus: TextView
    private lateinit var intelligenceStatus: TextView
    private var cameraController: CameraScanController? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraInput()
        else cameraStatus.text = "CAMERA PERMISSION DENIED — mesh demo kekal tersedia"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 15, 32)
        window.navigationBarColor = Color.rgb(8, 15, 32)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.rgb(8, 15, 32))
        }

        root.addView(TextView(this).apply {
            text = "AI-FUSION  /  3D SCANNER"
            textSize = 18f
            letterSpacing = 0.08f
            setTextColor(Color.rgb(229, 239, 255))
        }, LinearLayout.LayoutParams(-1, dp(34)))

        root.addView(TextView(this).apply {
            text = "LIVE MESH VIEWPORT"
            textSize = 11f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(65, 190, 255))
        }, LinearLayout.LayoutParams(-1, dp(24)))

        viewport = MeshViewport(this)
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(TextView(this).apply {
            text = "DEMO MESH  •  BELUM DIJANA DARIPADA KAMERA"
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setTextColor(Color.rgb(255, 190, 95))
            setPadding(0, dp(8), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, dp(30)))

        root.addView(TextView(this).apply {
            text = "Seret untuk pusing  •  Cubit untuk zum"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(164, 183, 220))
        }, LinearLayout.LayoutParams(-1, dp(26)))

        cameraStatus = TextView(this).apply {
            text = "CAMERA INPUT IDLE"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 205, 255))
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        root.addView(cameraStatus, LinearLayout.LayoutParams(-1, dp(38)))

        intelligenceStatus = TextView(this).apply {
            text = "SCAN INTELLIGENCE OFFLINE • Menunggu frame kamera"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(186, 205, 245))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        root.addView(intelligenceStatus, LinearLayout.LayoutParams(-1, dp(62)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(Button(this).apply {
            text = "Start camera input"
            isAllCaps = false
            setOnClickListener { requestCameraInput() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        controls.addView(Button(this).apply {
            text = "Stop"
            isAllCaps = false
            setOnClickListener { cameraController?.stop() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(56)))

        root.addView(Button(this).apply {
            text = "Tambah liputan demo mesh"
            isAllCaps = false
            setOnClickListener { viewport.addDemoCoverage() }
        }, LinearLayout.LayoutParams(-1, dp(48)))

        root.addView(TextView(this).apply {
            text = "AI advisor menilai cahaya, kontras dan isyarat detail secara offline. Ia belum menjana depth atau mesh sebenar."
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(126, 145, 180))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }, LinearLayout.LayoutParams(-1, dp(44)))

        setContentView(root)
        cameraController = CameraScanController(this, this) { stats ->
            runOnUiThread {
                if (stats == null) {
                    cameraStatus.text = "CAMERA INPUT STOPPED"
                    intelligenceStatus.text = "SCAN INTELLIGENCE OFFLINE • Menunggu frame kamera"
                } else {
                    val assessment = ScanIntelligenceEngine.assess(stats)
                    cameraStatus.text =
                        "FRAME #" + stats.frameNumber + " • " + stats.width + "×" + stats.height +
                            " • LUMA " + stats.meanLuma.toInt() +
                            " • KONTRAS " + stats.lumaStdDev.toInt()
                    intelligenceStatus.text =
                        "SCAN AI • " + assessment.qualityScore + "/100 • " +
                            assessment.headline + "\n" + assessment.guidance
                }
            }
        }
    }

    private fun requestCameraInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraInput()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraInput() {
        cameraStatus.text = "STARTING BACK CAMERA…"
        cameraController?.start()
    }

    override fun onResume() {
        super.onResume()
        if (::viewport.isInitialized) viewport.onResume()
    }

    override fun onPause() {
        cameraController?.stop()
        if (::viewport.isInitialized) viewport.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        cameraController?.close()
        cameraController = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
