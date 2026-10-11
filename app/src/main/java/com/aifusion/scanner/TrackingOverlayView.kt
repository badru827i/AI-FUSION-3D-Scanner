package com.aifusion.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lightweight motion-tracking overlay for the live scanner preview.
 *
 * The target box, anchor label and short motion path all follow the tracked
 * object. Sparse keyframes are kept in memory to stabilize the visual path;
 * they are not yet an editable/exportable video timeline.
 */
class TrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class TrackKeyframe(val x: Float, val y: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyframes = ArrayDeque<TrackKeyframe>()
    private var locked = false
    private var tracking = false
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var boxW = 0.22f
    private var boxH = 0.22f
    private var framesSinceKeyframe = 0
    var onTargetSelected: ((Float, Float) -> Unit)? = null

    fun setTarget(
        x: Float,
        y: Float,
        width: Float = 0.22f,
        height: Float = 0.22f,
        active: Boolean = true
    ) {
        val nextX = x.coerceIn(0f, 1f)
        val nextY = y.coerceIn(0f, 1f)
        val moved = hypot((nextX - centerX).toDouble(), (nextY - centerY).toDouble()).toFloat()
        centerX = nextX
        centerY = nextY
        boxW = width.coerceIn(0.08f, 0.65f)
        boxH = height.coerceIn(0.08f, 0.65f)
        locked = active
        tracking = active

        // Record a sparse motion path instead of allocating on every camera frame.
        framesSinceKeyframe++
        val last = keyframes.lastOrNull()
        if (active && (last == null || moved >= 0.012f || framesSinceKeyframe >= 8)) {
            keyframes.addLast(TrackKeyframe(centerX, centerY))
            while (keyframes.size > 12) keyframes.removeFirst()
            framesSinceKeyframe = 0
        }
        invalidate()
    }

    fun setTracking(active: Boolean) {
        tracking = active
        invalidate()
    }

    fun clearTarget() {
        locked = false
        tracking = false
        keyframes.clear()
        framesSinceKeyframe = 0
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = (event.x / width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
            val y = (event.y / height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
            setTarget(x, y)
            onTargetSelected?.invoke(x, y)
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!locked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * resources.displayMetrics.density
            paint.color = 0x99FFFFFF.toInt()
            val s = 34f * resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, 3f * resources.displayMetrics.density, paint)
            canvas.drawLine(cx - s, cy, cx - 12f, cy, paint)
            canvas.drawLine(cx + 12f, cy, cx + s, cy, paint)
            canvas.drawLine(cx, cy - s, cx, cy - 12f, paint)
            canvas.drawLine(cx, cy + 12f, cx, cy + s, paint)
            return
        }

        val density = resources.displayMetrics.density
        val cx = centerX * width
        val cy = centerY * height
        val bw = boxW * width
        val bh = boxH * height
        val rect = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)

        // Short trail shows recent tracked positions; oldest points fade visually.
        if (keyframes.size > 1) {
            val points = keyframes.toList()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            for (i in 1 until points.size) {
                val alpha = (45 + 150 * i / points.size).coerceIn(45, 195)
                paint.color = (alpha shl 24) or 0x0055E8FF
                canvas.drawLine(
                    points[i - 1].x * width, points[i - 1].y * height,
                    points[i].x * width, points[i].y * height, paint
                )
            }
            paint.style = Paint.Style.FILL
            for (i in points.indices) {
                val p = points[i]
                val alpha = (35 + 150 * (i + 1) / points.size).coerceIn(35, 185)
                paint.color = (alpha shl 24) or 0x0055E8FF
                canvas.drawCircle(p.x * width, p.y * height, (2f + i * 0.12f) * density, paint)
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.color = if (tracking) 0xFF55D68A.toInt() else 0xFFFFC857.toInt()
        canvas.drawRoundRect(rect, 10f * density, 10f * density, paint)

        // Attached anchor label behaves like a lightweight tracked text/effect layer.
        val label = if (tracking) "AI-FUSION • TRACK" else "ANCHOR • REACQUIRE"
        paint.textSize = 11f * density
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val padX = 8f * density
        val labelH = 22f * density
        val labelW = paint.measureText(label) + padX * 2f
        val labelLeft = rect.left.coerceIn(4f * density, (width - labelW - 4f * density).coerceAtLeast(4f * density))
        val labelTop = (rect.top - labelH - 6f * density).coerceAtLeast(4f * density)
        paint.style = Paint.Style.FILL
        paint.color = if (tracking) 0xDD10323B.toInt() else 0xDD4A3512.toInt()
        canvas.drawRoundRect(
            RectF(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH),
            6f * density, 6f * density, paint
        )
        paint.color = if (tracking) 0xFF70F5FF.toInt() else 0xFFFFD36A.toInt()
        canvas.drawText(label, labelLeft + padX, labelTop + labelH * 0.68f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = if (tracking) 0xFF55D68A.toInt() else 0xFFFFC857.toInt()
        canvas.drawLine(cx - 10f * density, cy, cx + 10f * density, cy, paint)
        canvas.drawLine(cx, cy - 10f * density, cx, cy + 10f * density, paint)
    }
}
