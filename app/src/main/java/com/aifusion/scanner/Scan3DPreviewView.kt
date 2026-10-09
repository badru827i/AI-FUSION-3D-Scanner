package com.aifusion.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight live relief-mesh preview. It is derived from the current AI
 * relative-depth frame and, when a target is locked, crops around that target.
 * This is a visualization of monocular relative depth, not a metric scan.
 */
class Scan3DPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var coverage: ScanCoverage? = null
    private var rotation = 0f
    private var aiDepth = false
    private var focusX = 0.5f
    private var focusY = 0.5f
    private var focusLocked = false
    private var trackingFound = true
    private var live = true

    fun setPreview(value: Bitmap, scanCoverage: ScanCoverage) {
        replaceBitmap(value)
        coverage = scanCoverage
        aiDepth = false
        live = false
        focusLocked = false
        trackingFound = true
        rotation = (rotation + 0.08f) % 6.28318f
        visibility = View.VISIBLE
        invalidate()
    }

    fun setDepthPreview(
        value: Bitmap,
        scanCoverage: ScanCoverage,
        targetX: Float = 0.5f,
        targetY: Float = 0.5f,
        targetLocked: Boolean = false,
        targetTracking: Boolean = true
    ) {
        replaceBitmap(value)
        coverage = scanCoverage
        aiDepth = true
        live = true
        focusX = targetX.coerceIn(0f, 1f)
        focusY = targetY.coerceIn(0f, 1f)
        focusLocked = targetLocked
        trackingFound = targetTracking
        // Small continuous rotation makes changes in the reconstructed relief
        // easier to read without running a permanent animation loop.
        rotation = (rotation + 0.07f) % 6.28318f
        visibility = View.VISIBLE
        invalidate()
    }

    private fun replaceBitmap(value: Bitmap) {
        val previous = bitmap
        bitmap = value
        if (previous != null && previous !== value && !previous.isRecycled) previous.recycle()
    }

    fun setCompleted() {
        live = false
        focusLocked = false
        trackingFound = true
        invalidate()
    }

    fun clear() {
        val previous = bitmap
        bitmap = null
        if (previous != null && !previous.isRecycled) previous.recycle()
        coverage = null
        rotation = 0f
        aiDepth = false
        live = false
        focusLocked = false
        trackingFound = true
        visibility = View.GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        // Translucent so the camera image is still partly visible behind the preview.
        paint.color = 0xB20B1015.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 18f, 18f, paint)

        val image = bitmap
        if (image != null && !image.isRecycled) drawReliefMesh(canvas, image, w, h)
        else {
            paint.color = 0xCCFFFFFF.toInt()
            paint.textSize = 12f
            canvas.drawText("Waiting for depth…", 10f, h / 2f, paint)
        }
        drawCoverage(canvas, w, h)
    }

    private fun drawReliefMesh(canvas: Canvas, image: Bitmap, w: Float, h: Float) {
        val cols = 23
        val rows = 23
        val cx = w / 2f
        val cy = h / 2f
        val scale = minOf(w, h) * 0.34f
        val regionW = if (focusLocked) 0.70f else 0.92f
        val regionH = if (focusLocked) 0.74f else 0.92f
        val regionLeft = (focusX - regionW / 2f).coerceIn(0f, 1f - regionW)
        val regionTop = (focusY - regionH / 2f).coerceIn(0f, 1f - regionH)
        val points = Array(rows) { Array(cols) { FloatArray(4) } }

        for (y in 0 until rows) for (x in 0 until cols) {
            val nxInRegion = x / (cols - 1f)
            val nyInRegion = y / (rows - 1f)
            val px = ((regionLeft + nxInRegion * regionW) * (image.width - 1))
                .toInt().coerceIn(0, image.width - 1)
            val py = ((regionTop + nyInRegion * regionH) * (image.height - 1))
                .toInt().coerceIn(0, image.height - 1)
            val p = image.getPixel(px, py)
            val r = (p shr 16 and 0xff) / 255f
            val g = (p shr 8 and 0xff) / 255f
            val b = (p and 0xff) / 255f
            val depth = 0.299f * r + 0.587f * g + 0.114f * b
            val nx = (nxInRegion - 0.5f) * 2f
            val ny = (nyInRegion - 0.5f) * 2f
            val z = (depth - 0.5f) * if (aiDepth) 1.15f else 0.75f
            points[y][x][0] = nx
            points[y][x][1] = ny
            points[y][x][2] = z
            points[y][x][3] = depth
        }

        val c = cos(rotation)
        val s = sin(rotation)
        val projected = Array(rows) { Array(cols) { FloatArray(4) } }
        for (y in 0 until rows) for (x in 0 until cols) {
            val p = points[y][x]
            val rx = p[0] * c - p[2] * s
            val rz = p[0] * s + p[2] * c
            val perspective = (1.45f + rz).coerceAtLeast(0.65f)
            projected[y][x][0] = cx + rx * scale / perspective
            projected[y][x][1] = cy + p[1] * scale / perspective
            projected[y][x][2] = rz
            projected[y][x][3] = p[3]
        }

        paint.strokeWidth = 1.05f
        paint.style = Paint.Style.STROKE
        for (y in 0 until rows) for (x in 0 until cols) {
            val p = projected[y][x]
            if (x + 1 < cols) {
                val q = projected[y][x + 1]
                val depthTone = (((p[3] + q[3]) * 0.5f * 80f).toInt()).coerceIn(0, 80)
                paint.color = ((155 + depthTone) shl 24) or 0x006FE7FF
                canvas.drawLine(p[0], p[1], q[0], q[1], paint)
            }
            if (y + 1 < rows) {
                val q = projected[y + 1][x]
                paint.color = 0x9955D68A.toInt()
                canvas.drawLine(p[0], p[1], q[0], q[1], paint)
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = 0xF2FFFFFF.toInt()
        paint.textSize = 11f
        val title = when {
            !aiDepth -> "3D PREVIEW"
            !live -> "3D RESULT • RELATIVE DEPTH"
            focusLocked && trackingFound -> "LIVE 3D • TRACK LOCKED"
            focusLocked -> "LIVE 3D • RECOVERING"
            else -> "LIVE 3D • AI DEPTH"
        }
        canvas.drawText(title, 10f, 17f, paint)
        if (focusLocked) {
            paint.color = if (trackingFound) 0xFF55D68A.toInt() else 0xFFFFC857.toInt()
            paint.textSize = 9f
            canvas.drawText(if (trackingFound) "OBJECT FOCUS" else "HOLDING LAST POSITION", 10f, h - 18f, paint)
        }
    }

    private fun drawCoverage(canvas: Canvas, w: Float, h: Float) {
        val c = coverage ?: return
        val radius = minOf(w, h) * 0.43f
        val cx = w / 2f
        val cy = h / 2f
        val sector = 360f / 12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f
        for (s in 0 until 12) {
            paint.color = if (c.isVisited(12 + s)) 0xCC55D68A.toInt() else 0xCCEF5350.toInt()
            canvas.drawArc(
                RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                s * sector - 90f, sector - 4f, false, paint
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xDDFFFFFF.toInt()
        paint.textSize = 10f
        canvas.drawText(c.percent().toString() + "% coverage", 10f, h - 6f, paint)
    }
}