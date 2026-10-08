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
 * Lightweight live 3D reconstruction preview.
 *
 * It converts the latest captured RGB frame into a shaded relief/point mesh so
 * the user can see a 3D-looking object while scanning. This is intentionally
 * cheap for low-RAM phones. True metric depth will replace the luminance depth
 * estimate when the ONNX/TFLite depth stage is added.
 */
class Scan3DPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var coverage: ScanCoverage? = null
    private var rotation = 0f

    fun setPreview(value: Bitmap, scanCoverage: ScanCoverage) {
        bitmap?.recycle()
        bitmap = value
        coverage = scanCoverage
        rotation += 0.08f
        invalidate()
    }

    fun clear() {
        bitmap?.recycle()
        bitmap = null
        coverage = null
        rotation = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = 0xEE0B1015.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 18f, 18f, paint)

        val image = bitmap
        if (image != null) {
            drawReliefMesh(canvas, image, w, h)
        } else {
            paint.color = 0xCCFFFFFF.toInt()
            paint.textSize = 11f
            canvas.drawText("3D preview", 10f, h / 2f, paint)
        }

        drawCoverage(canvas, w, h)
    }

    private fun drawReliefMesh(canvas: Canvas, image: Bitmap, w: Float, h: Float) {
        val cols = 18
        val rows = 18
        val cx = w / 2f
        val cy = h / 2f
        val scale = minOf(w, h) * 0.34f
        val points = Array(rows) { Array(cols) { floatArrayOf(0f, 0f, 0f, 0f) } }

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val px = (x * (image.width - 1) / (cols - 1)).coerceIn(0, image.width - 1)
                val py = (y * (image.height - 1) / (rows - 1)).coerceIn(0, image.height - 1)
                val p = image.getPixel(px, py)
                val r = (p shr 16 and 0xff) / 255f
                val g = (p shr 8 and 0xff) / 255f
                val b = (p and 0xff) / 255f
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val nx = (x / (cols - 1f) - 0.5f) * 2f
                val ny = (y / (rows - 1f) - 0.5f) * 2f
                val z = (lum - 0.5f) * 0.75f
                points[y][x][0] = nx
                points[y][x][1] = ny
                points[y][x][2] = z
                points[y][x][3] = lum
            }
        }

        val c = cos(rotation)
        val s = sin(rotation)
        val projected = Array(rows) { Array(cols) { floatArrayOf(0f, 0f, 0f, 0f) } }

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val p = points[y][x]
                val rx = p[0] * c - p[2] * s
                val rz = p[0] * s + p[2] * c
                val depth = 1.4f + rz
                projected[y][x][0] = cx + rx * scale / depth
                projected[y][x][1] = cy + p[1] * scale / depth
                projected[y][x][2] = rz
                projected[y][x][3] = p[3]
            }
        }

        paint.strokeWidth = 1.2f
        paint.style = Paint.Style.STROKE
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val p = projected[y][x]
                if (x + 1 < cols) {
                    val q = projected[y][x + 1]
                    paint.color = 0xCC6FE7FF.toInt()
                    canvas.drawLine(p[0], p[1], q[0], q[1], paint)
                }
                if (y + 1 < rows) {
                    val q = projected[y + 1][x]
                    paint.color = 0xAA55D68A.toInt()
                    canvas.drawLine(p[0], p[1], q[0], q[1], paint)
                }
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = 0xF2FFFFFF.toInt()
        paint.textSize = 10f
        canvas.drawText("3D reconstruction", 8f, 14f, paint)
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
        canvas.drawText(c.percent().toString() + "%", 8f, h - 8f, paint)
    }
}
