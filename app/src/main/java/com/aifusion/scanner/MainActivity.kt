package com.aifusion.scanner

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var viewport: MeshViewport

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
            text = "DEMO MESH  •  BUKAN HASIL IMBASAN KAMERA"
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setTextColor(Color.rgb(255, 190, 95))
            setPadding(0, dp(10), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, dp(36)))

        root.addView(TextView(this).apply {
            text = "Seret untuk pusing  •  Cubit untuk zum"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(164, 183, 220))
        }, LinearLayout.LayoutParams(-1, dp(28)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(Button(this).apply {
            text = "Tambah liputan demo"
            isAllCaps = false
            setOnClickListener { viewport.addDemoCoverage() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(58)))

        root.addView(TextView(this).apply {
            text = "Peringkat ini menguji paparan 3D sahaja. Kamera dan AI depth sebenar belum disambungkan."
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(126, 145, 180))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }, LinearLayout.LayoutParams(-1, dp(42)))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::viewport.isInitialized) viewport.onResume()
    }

    override fun onPause() {
        if (::viewport.isInitialized) viewport.onPause()
        super.onPause()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
