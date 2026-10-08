package com.aifusion.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class TrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var locked = false
    private var tracking = false
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var boxW = 0.22f
    private var boxH = 0.22f
    var onTargetSelected: ((Float, Float) -> Unit)? = null

    fun setTarget(x: Float, y: Float, width: Float = 0.22f, height: Float = 0.22f, active: Boolean = true) {
        centerX = x.coerceIn(0f, 1f)
        centerY = y.coerceIn(0f, 1f)
        boxW = width.coerceIn(0.08f, 0.65f)
        boxH = height.coerceIn(0.08f, 0.65f)
        locked = active
        tracking = active
        invalidate()
    }

    fun setTracking(active: Boolean) { tracking = active; invalidate() }
    fun clearTarget() { locked = false; tracking = false; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = (event.x / width.toFloat()).coerceIn(0f, 1f)
            val y = (event.y / height.toFloat()).coerceIn(0f, 1f)
            setTarget(x, y)
            onTargetSelected?.invoke(x, y)
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!locked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0x99FFFFFF.toInt()
            val s = 34f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, 3f, paint)
            canvas.drawLine(cx - s, cy, cx - 12f, cy, paint)
            canvas.drawLine(cx + 12f, cy, cx + s, cy, paint)
            canvas.drawLine(cx, cy - s, cx, cy - 12f, paint)
            canvas.drawLine(cx, cy + 12f, cx, cy + s, paint)
            return
        }
        val cx = centerX * width
        val cy = centerY * height
        val bw = boxW * width
        val bh = boxH * height
        val rect = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = if (tracking) 0xFF55D68A.toInt() else 0xFFFFC857.toInt()
        canvas.drawRoundRect(rect, 14f, 14f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 13f
        paint.color = 0xEEFFFFFF.toInt()
        canvas.drawText(if (tracking) "TRACKING" else "TARGET", rect.left, rect.top - 8f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawLine(cx - 12f, cy, cx + 12f, cy, paint)
        canvas.drawLine(cx, cy - 12f, cx, cy + 12f, paint)
    }
}