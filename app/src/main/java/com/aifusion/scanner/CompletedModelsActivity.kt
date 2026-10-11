package com.aifusion.scanner

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Local gallery for completed scans; no server or external storage permission required. */
class CompletedModelsActivity : Activity() {
    private lateinit var root: File
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = File(filesDir, "scans")
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 24))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "Completed 3D Models"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }
        val subtitle = TextView(this).apply {
            text = "Model yang siap disimpan di telefon • pilih untuk preview 3D"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(12))
        }
        page.addView(title)
        page.addView(subtitle)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        setContentView(page)
        showGallery()
    }

    private fun showGallery() {
        content.removeAllViews()
        val models = root.listFiles()?.filter { dir ->
            dir.isDirectory && File(dir, "${dir.name}.obj").isFile && File(dir, "${dir.name}.glb").isFile
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (models.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "Belum ada model siap. Selesaikan satu sesi scan dahulu."
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        models.forEach { dir ->
            val obj = File(dir, "${dir.name}.obj")
            val glb = File(dir, "${dir.name}.glb")
            content.addView(Button(this).apply {
                text = "${dir.name}\nOBJ + GLB • ${obj.length() / 1024} KB"
                isAllCaps = false
                setOnClickListener { showPreview(dir) }
            }, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(8) })
        }
    }

    private fun showPreview(dir: File) {
        try {
            val mesh = parseObj(File(dir, "${dir.name}.obj"))
            content.removeAllViews()
            content.addView(TextView(this).apply {
                text = dir.name + " • 3D Preview"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(8))
            })
            val preview = Scan3DPreviewView(this).apply {
                setLowPowerMode(true)
                setPolygonMode(true)
                setSavedModelMesh(mesh.first, mesh.second)
            }
            content.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
            content.addView(TextView(this).apply {
                text = "Seret untuk rotate • pinch untuk zoom\nFail tersimpan: OBJ dan GLB"
                textSize = 12f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(8), 0, dp(8))
            })
            content.addView(Button(this).apply {
                text = "Back to completed models"
                setOnClickListener { showGallery() }
            })
        } catch (t: Throwable) {
            Toast.makeText(this, "Tak dapat buka model: ${t.message ?: "fail tidak sah"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseObj(file: File): Pair<FloatArray, IntArray> {
        val points = ArrayList<Float>()
        val faces = ArrayList<Int>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("v ")) {
                    val p = trimmed.split(Regex("\\s+"))
                    if (p.size >= 4) {
                        points.add(p[1].toFloat()); points.add(p[2].toFloat()); points.add(p[3].toFloat())
                    }
                } else if (trimmed.startsWith("f ")) {
                    val p = trimmed.split(Regex("\\s+")).drop(1).mapNotNull {
                        it.substringBefore('/').toIntOrNull()?.minus(1)
                    }
                    if (p.size >= 3) for (i in 1 until p.size - 1) {
                        faces.add(p[0]); faces.add(p[i]); faces.add(p[i + 1])
                    }
                }
            }
        }
        require(points.size >= 12 && faces.size >= 6) { "OBJ tiada mesh yang boleh dipreview" }
        val minX = points.filterIndexed { i, _ -> i % 3 == 0 }.minOrNull() ?: -1f
        val maxX = points.filterIndexed { i, _ -> i % 3 == 0 }.maxOrNull() ?: 1f
        val minY = points.filterIndexed { i, _ -> i % 3 == 1 }.minOrNull() ?: -1f
        val maxY = points.filterIndexed { i, _ -> i % 3 == 1 }.maxOrNull() ?: 1f
        val minZ = points.filterIndexed { i, _ -> i % 3 == 2 }.minOrNull() ?: -1f
        val maxZ = points.filterIndexed { i, _ -> i % 3 == 2 }.maxOrNull() ?: 1f
        val sx = (maxX - minX).coerceAtLeast(0.0001f)
        val sy = (maxY - minY).coerceAtLeast(0.0001f)
        val sz = (maxZ - minZ).coerceAtLeast(0.0001f)
        val normalized = FloatArray(points.size)
        var i = 0
        while (i < points.size) {
            normalized[i] = ((points[i] - minX) / sx).coerceIn(0f, 1f)
            normalized[i + 1] = ((points[i + 1] - minY) / sy).coerceIn(0f, 1f)
            normalized[i + 2] = ((points[i + 2] - minZ) / sz).coerceIn(0f, 1f)
            i += 3
        }
        return normalized to faces.toIntArray()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
