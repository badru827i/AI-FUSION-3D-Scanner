package com.aifusion.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Lightweight live feature-point screen. Points are sampled from image gradients;
 * they are visual feature candidates, not a claim of 2,000 persistent 3D landmarks.
 */
class TrackingPointsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Point(val x: Float, val y: Float, val strength: Float)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var points: List<Point> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var frameUpdatedAt = 0L
    var onClose: (() -> Unit)? = null

    init {
        isClickable = true
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        panelPaint.color = 0xB9101720.toInt()
        textPaint.color = 0xFFF4F8FC.toInt()
        textPaint.textSize = 15f * resources.displayMetrics.scaledDensity
        textPaint.isFakeBoldText = true
    }

    /** Extract up to 2,000 strongest local image-gradient candidates. */
    fun submitFrame(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100
        }
        val candidates = ArrayList<Point>(minOf(2600, (w - 2) * (h - 2)))
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val gx = abs(gray[i + 1] - gray[i - 1])
                val gy = abs(gray[i + w] - gray[i - w])
                val diagonal = abs(gray[i + w + 1] - gray[i - w - 1])
                val strength = gx + gy + diagonal / 2
                if (strength >= 12) {
                    candidates.add(Point(x / (w - 1f), y / (h - 1f), strength.toFloat()))
                }
            }
        }
        candidates.sortByDescending { it.strength }
        val limit = minOf(2000, candidates.size)
        val selected = ArrayList<Point>(limit)
        // Spread selected points across the frame rather than clustering only on one edge.
        val grid = HashSet<Int>(limit * 2)
        val cellsX = 48
        val cellsY = 48
        for (point in candidates) {
            val cellX = (point.x * cellsX).toInt().coerceIn(0, cellsX - 1)
            val cellY = (point.y * cellsY).toInt().coerceIn(0, cellsY - 1)
            val cell = cellY * cellsX + cellX
            if (grid.add(cell) || selected.size < 300) selected.add(point)
            if (selected.size >= limit) break
        }
        points = selected
        sourceWidth = w
        sourceHeight = h
        frameUpdatedAt = android.os.SystemClock.elapsedRealtime()
        postInvalidateOnAnimation()
    }

    fun clearPoints() {
        points = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Dim the live camera slightly so the feature field stays readable.
        canvas.drawColor(0x25101820)
        val radius = (width.coerceAtMost(height) / 420f).coerceIn(1.3f, 2.7f)
        for (p in points) {
            val alpha = (110 + p.strength.toInt().coerceIn(0, 145)).coerceAtMost(235)
            dotPaint.color = (alpha shl 24) or 0x004DE6FF
            dotPaint.style = Paint.Style.FILL
            canvas.drawCircle(p.x * width, p.y * height, radius, dotPaint)
            if (p.strength > 110f) {
                dotPaint.color = 0xAAFFFFFF.toInt()
                canvas.drawCircle(p.x * width, p.y * height, radius * 0.55f, dotPaint)
            }
        }
        val density = resources.displayMetrics.density
        val panel = RectF(12f * density, 16f * density, width - 12f * density, 92f * density)
        canvas.drawRoundRect(panel, 18f * density, 18f * density, panelPaint)
        textPaint.textSize = 16f * density
        canvas.drawText("DENSE TRACKING • LIVE", panel.left + 14f * density, panel.top + 27f * density, textPaint)
        textPaint.textSize = 13f * density
        textPaint.isFakeBoldText = false
        canvas.drawText("${points.size} feature points / 2,000 target", panel.left + 14f * density, panel.top + 49f * density, textPaint)
        val age = android.os.SystemClock.elapsedRealtime() - frameUpdatedAt
        canvas.drawText(if (age < 1800L) "FRAME ACTIVE  •  tap X to close" else "WAITING FOR CAMERA FRAME  •  tap X to close",
            panel.left + 14f * density, panel.top + 68f * density, textPaint)
        textPaint.isFakeBoldText = true
        textPaint.textSize = 24f * density
        canvas.drawText("×", width - 42f * density, 47f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (event.x > width - 76f * resources.displayMetrics.density &&
                event.y < 82f * resources.displayMetrics.density) {
                onClose?.invoke()
            }
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
