package com.aifusion.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ScanMiniPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var coverage: ScanCoverage? = null

    fun setPreview(value: Bitmap, scanCoverage: ScanCoverage) {
        bitmap?.recycle()
        bitmap = value
        coverage = scanCoverage
        invalidate()
    }

    fun clear() {
        bitmap?.recycle()
        bitmap = null
        coverage = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        paint.style = Paint.Style.FILL
        paint.color = 0xCC101418.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 18f, 18f, paint)

        val image = bitmap
        if (image != null) {
            val pad = 10f
            val dst = RectF(pad, pad, w - pad, h - pad)
            val srcRatio = image.width.toFloat() / image.height
            val dstRatio = dst.width() / dst.height()
            if (srcRatio > dstRatio) {
                val newH = image.height * (dst.width() / image.width)
                val top = dst.centerY() - newH / 2f
                canvas.drawBitmap(image, null, RectF(dst.left, top, dst.right, top + newH), paint)
            } else {
                val newW = image.width * (dst.height() / image.height)
                val left = dst.centerX() - newW / 2f
                canvas.drawBitmap(image, null, RectF(left, dst.top, left + newW, dst.bottom), paint)
            }
        }

        val c = coverage ?: return
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) - 9f
        val sector = 360f / 12f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.strokeCap = Paint.Cap.ROUND

        for (band in 0 until 3) {
            val inset = band * 11f
            val rect = RectF(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)
            for (s in 0 until 12) {
                paint.color = if (c.isVisited(band * 12 + s)) 0xFF55D68A.toInt() else 0xFFE85D5D.toInt()
                canvas.drawArc(rect, s * sector - 90f, sector - 3f, false, paint)
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = 0xEEFFFFFF.toInt()
        paint.textSize = 13f
        canvas.drawText(c.percent().toString() + "%", 12f, 20f, paint)
        paint.textSize = 10f
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawText("mini scan map", 12f, h - 10f, paint)
    }
}
