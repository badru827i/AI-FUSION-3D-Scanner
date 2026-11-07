package com.aifusion.scanner

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.aifusion.scanner.vision.CameraFusionCapability

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capability = CameraFusionCapability(this).inspect()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFF101426.toInt())
        }
        root.addView(TextView(this).apply {
            text = "AI-FUSION\\nVISION 3D"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFE9EDFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "\\nNative AI engine\\n${capability.summary}\\n\\nModel: waiting for trained AI-FUSION weights"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFB9C4E8.toInt())
        })
        setContentView(root)
    }
}
