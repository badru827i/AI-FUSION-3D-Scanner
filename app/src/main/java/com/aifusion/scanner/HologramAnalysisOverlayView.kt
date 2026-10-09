package com.aifusion.scanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight hologram-style scan analysis overlay.
 * Square cells softly blend in/out around the locked target; this is a visual
 * analysis guide only and does not claim to measure depth by itself.
 */
class HologramAnalysisOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false
    private var lowPower = false
    private var targetVisible = false
    private var targetSelected = false
    private var targetX = 0.5f
    private var targetY = 0.5f
    private var targetW = 0.30f
    private var targetH = 0.30f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun setLowPowerMode(enabled: Boolean) {
        lowPower = enabled
        invalidate()
    }

    fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        if (enabled) startPulse() else stopPulse()
        invalidate()
    }

    fun updateTarget(
        x: Float,
        y: Float,
        tracked: Boolean,
        width: Float = 0.30f,
        height: Float = 0.30f,
        locked: Boolean = true
    ) {
        targetX = x.coerceIn(0f, 1f)
        targetY = y.coerceIn(0f, 1f)
        targetW = width.coerceIn(0.08f, 0.65f)
        targetH = height.coerceIn(0.08f, 0.65f)
        targetSelected = locked
        targetVisible = tracked && locked
        invalidate()
    }

    private fun startPulse() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (lowPower) 1800L else 1350L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        animator?.cancel()
        animator = null
        phase = 0f
        targetVisible = false
        targetSelected = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width <= 0 || height <= 0) return

        // A low-cost full-frame grid communicates that analysis spans the camera view;
        // target-local brackets below identify the region currently used for lock tracking.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.65f * resources.displayMetrics.density
        paint.color = 0x2250DFFF
        val gridStep = (32f * resources.displayMetrics.density).coerceAtLeast(24f)
        var gx = 0f
        while (gx < width) {
            canvas.drawLine(gx, 0f, gx, height.toFloat(), paint)
            gx += gridStep
        }
        var gy = 0f
        while (gy < height) {
            canvas.drawLine(0f, gy, width.toFloat(), gy, paint)
            gy += gridStep
        }
        paint.style = Paint.Style.FILL
        paint.textSize = 9f * resources.displayMetrics.density
        paint.color = 0x9970F5FF.toInt()
        canvas.drawText(
            if (targetSelected) "FULL FRAME ANALYSIS • TARGET LOCK" else "FULL FRAME ANALYSIS",
            10f * resources.displayMetrics.density,
            height - 12f * resources.displayMetrics.density,
            paint
        )

        val cx = targetX * width
        val cy = targetY * height
        val boxW = width * targetW
        val boxH = height * targetH
        val left = max(8f, cx - boxW / 2f)
        val top = max(8f, cy - boxH / 2f)
        val right = min(width - 8f, cx + boxW / 2f)
        val bottom = min(height - 8f, cy + boxH / 2f)
        val bounds = RectF(left, top, right, bottom)

        // Thin corner brackets frame the area currently being analysed.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        paint.color = if (targetVisible) 0xCC50E8FF.toInt() else 0x9968CFFF.toInt()
        val corner = min(bounds.width(), bounds.height()) * 0.14f
        canvas.drawLine(left, top + corner, left, top, paint)
        canvas.drawLine(left, top, left + corner, top, paint)
        canvas.drawLine(right - corner, top, right, top, paint)
        canvas.drawLine(right, top, right, top + corner, paint)
        canvas.drawLine(left, bottom - corner, left, bottom, paint)
        canvas.drawLine(left, bottom, left + corner, bottom, paint)
        canvas.drawLine(right - corner, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom - corner, right, bottom, paint)

        // A small grid of tiles fades in and out in a travelling wave.
        val columns = if (lowPower) 4 else 6
        val rows = if (lowPower) 4 else 6
        val cellW = bounds.width() / columns
        val cellH = bounds.height() / rows
        paint.style = Paint.Style.FILL
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val stagger = (col * 0.11f + row * 0.075f) % 1f
                val wave = (phase + stagger) % 1f
                val fade = (0.5f + 0.5f * sin(wave * Math.PI * 2.0)).toFloat()
                val alpha = (18 + fade * if (targetVisible) 82 else 42).toInt().coerceIn(12, 105)
                paint.color = ((alpha shl 24) or 0x0050DFFF).toInt()
                val inset = 2.2f
                val cell = RectF(
                    left + col * cellW + inset,
                    top + row * cellH + inset,
                    left + (col + 1) * cellW - inset,
                    top + (row + 1) * cellH - inset
                )
                canvas.drawRoundRect(cell, 2.5f, 2.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.8f
                paint.color = ((min(220, alpha + 55) shl 24) or 0x0050DFFF).toInt()
                canvas.drawRoundRect(cell, 2.5f, 2.5f, paint)
                paint.style = Paint.Style.FILL
            }
        }

        // Scanning line gently travels vertically through the target box.
        val lineY = top + bounds.height() * phase
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = if (targetVisible) 0xCC70F5FF.toInt() else 0x8870DFFF.toInt()
        canvas.drawLine(left, lineY, right, lineY, paint)

        if (!targetVisible) {
            paint.style = Paint.Style.FILL
            paint.textSize = 11f * resources.displayMetrics.density
            paint.color = 0xCCFFFFFF.toInt()
            val hint = if (targetSelected) "TARGET LOST • REACQUIRING" else "ANALYSING • MOVE SLOWLY"
            canvas.drawText(hint, left, min(height - 12f, bottom + 20f), paint)
        }
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }
}
