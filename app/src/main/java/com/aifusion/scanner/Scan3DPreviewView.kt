package com.aifusion.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight interactive relative-depth surface preview.
 *
 * This renders a shaded triangle mesh from the current monocular depth estimate.
 * It is an inspectable relief preview, not metric geometry or a complete object mesh.
 * Mesh density is reduced for low-memory devices.
 */
class Scan3DPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Vertex(
        val x: Float,
        val y: Float,
        val z: Float,
        val depth: Float,
        val screenX: Float,
        val screenY: Float
    )

    private data class Face(
        val a: Vertex,
        val b: Vertex,
        val c: Vertex,
        val averageZ: Float,
        val color: Int
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePath = Path()
    private var bitmap: Bitmap? = null
    private var coverage: ScanCoverage? = null
    private var yaw = -0.30f
    private var pitch = 0.62f
    private var zoom = 1f
    private var lowPower = false
    private var aiDepth = false
    private var focusX = 0.5f
    private var focusY = 0.5f
    private var focusLocked = false
    private var trackingFound = true
    private var live = true
    private var userAdjustedView = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pinchStartDistance = 0f
    private var pinchStartZoom = 1f

    fun setLowPowerMode(enabled: Boolean) {
        lowPower = enabled
        invalidate()
    }

    fun setPreview(value: Bitmap, scanCoverage: ScanCoverage) {
        replaceBitmap(value)
        coverage = scanCoverage
        aiDepth = false
        live = false
        focusLocked = false
        trackingFound = true
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
        // A subtle idle turn helps reveal the relief. User rotation takes priority.
        if (!userAdjustedView) yaw = (yaw + 0.018f) % 6.28318f
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
        yaw = -0.30f
        pitch = 0.62f
        zoom = 1f
        aiDepth = false
        live = false
        focusLocked = false
        trackingFound = true
        userAdjustedView = false
        visibility = View.GONE
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                userAdjustedView = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinchStartDistance = pointerDistance(event)
                    pinchStartZoom = zoom
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val distance = pointerDistance(event)
                    if (pinchStartDistance > 1f && distance > 1f) {
                        zoom = (pinchStartZoom * distance / pinchStartDistance).coerceIn(0.72f, 1.55f)
                    }
                } else {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    yaw = (yaw + dx * 0.008f) % 6.28318f
                    pitch = (pitch + dy * 0.006f).coerceIn(-0.35f, 1.20f)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount > 2) {
                    pinchStartDistance = pointerDistance(event, event.actionIndex)
                    pinchStartZoom = zoom
                } else {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun pointerDistance(event: MotionEvent, skipIndex: Int = -1): Float {
        var first = -1
        var second = -1
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            if (first < 0) first = i else {
                second = i
                break
            }
        }
        if (first < 0 || second < 0) return 0f
        val dx = event.getX(first) - event.getX(second)
        val dy = event.getY(first) - event.getY(second)
        return sqrt(dx * dx + dy * dy)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.style = Paint.Style.FILL
        paint.color = 0xE60B1018.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 18f, 18f, paint)

        val image = bitmap
        if (image != null && !image.isRecycled && image.width > 1 && image.height > 1) {
            drawSurfaceMesh(canvas, image, w, h)
        } else {
            paint.color = 0xCCFFFFFF.toInt()
            paint.textSize = 12f * resources.displayMetrics.density
            canvas.drawText("Waiting for depth…", 10f, h / 2f, paint)
        }
        drawCoverage(canvas, w, h)
        drawLegend(canvas, w, h)
    }

    private fun drawSurfaceMesh(canvas: Canvas, image: Bitmap, w: Float, h: Float) {
        val divisions = if (lowPower) 12 else 20
        val rows = divisions + 1
        val cols = divisions + 1
        val regionW = if (focusLocked) 0.68f else 0.90f
        val regionH = if (focusLocked) 0.68f else 0.90f
        val regionLeft = (focusX - regionW / 2f).coerceIn(0f, 1f - regionW)
        val regionTop = (focusY - regionH / 2f).coerceIn(0f, 1f - regionH)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val scale = min(w, h) * 0.34f * zoom
        val cameraDistance = 2.65f
        val vertices = Array(rows) { arrayOfNulls<Vertex>(cols) }

        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val u = gx / (cols - 1f)
                val v = gy / (rows - 1f)
                val px = ((regionLeft + u * regionW) * (image.width - 1)).toInt().coerceIn(0, image.width - 1)
                val py = ((regionTop + v * regionH) * (image.height - 1)).toInt().coerceIn(0, image.height - 1)
                val depth = smoothDepth(image, px, py)
                val x = (u - 0.5f) * 2f
                val y = (v - 0.5f) * 2f
                val z = (depth - 0.5f) * if (aiDepth) 0.96f else 0.65f

                // Rotate the sampled depth surface in 3D, then apply mild perspective.
                val rx = x * cy + z * sy
                val rz = -x * sy + z * cy
                val ry = y * cp - rz * sp
                val cameraZ = y * sp + rz * cp
                val perspective = cameraDistance / (cameraDistance - cameraZ).coerceAtLeast(1.25f)
                vertices[gy][gx] = Vertex(
                    rx, ry, cameraZ, depth,
                    w / 2f + rx * scale * perspective,
                    h / 2f + ry * scale * perspective
                )
            }
        }

        val faces = ArrayList<Face>(divisions * divisions * 2)
        for (gy in 0 until divisions) {
            for (gx in 0 until divisions) {
                val a = vertices[gy][gx] ?: continue
                val b = vertices[gy][gx + 1] ?: continue
                val c = vertices[gy + 1][gx] ?: continue
                val d = vertices[gy + 1][gx + 1] ?: continue
                faces.add(createFace(a, c, b))
                faces.add(createFace(b, c, d))
            }
        }

        // Approximate painter's ordering to show overlapping relief surfaces naturally.
        faces.sortBy { it.averageZ }
        paint.style = Paint.Style.FILL
        for (face in faces) {
            facePath.reset()
            facePath.moveTo(face.a.screenX, face.a.screenY)
            facePath.lineTo(face.b.screenX, face.b.screenY)
            facePath.lineTo(face.c.screenX, face.c.screenY)
            facePath.close()
            paint.color = face.color
            canvas.drawPath(facePath, paint)
        }

        // Draw subtle surface wires after the faces for readable form and depth changes.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (lowPower) 0.7f else 0.85f
        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val p = vertices[gy][gx] ?: continue
                paint.color = if (p.depth > 0.58f) 0xA96EF2FF.toInt() else 0x8B55D6C7.toInt()
                if (gx + 1 < cols) {
                    val q = vertices[gy][gx + 1] ?: continue
                    canvas.drawLine(p.screenX, p.screenY, q.screenX, q.screenY, paint)
                }
                if (gy + 1 < rows) {
                    val q = vertices[gy + 1][gx] ?: continue
                    canvas.drawLine(p.screenX, p.screenY, q.screenX, q.screenY, paint)
                }
            }
        }
    }

    private fun smoothDepth(image: Bitmap, x: Int, y: Int): Float {
        fun value(px: Int, py: Int): Float {
            val color = image.getPixel(px.coerceIn(0, image.width - 1), py.coerceIn(0, image.height - 1))
            return ((color shr 16 and 0xff) * 0.299f +
                (color shr 8 and 0xff) * 0.587f + (color and 0xff) * 0.114f) / 255f
        }
        val center = value(x, y)
        val neighbors = value(x - 1, y) + value(x + 1, y) +
            value(x, y - 1) + value(x, y + 1)
        return (center * 0.60f + neighbors * 0.10f).coerceIn(0f, 1f)
    }

    private fun createFace(a: Vertex, b: Vertex, c: Vertex): Face {
        val ux = b.x - a.x
        val uy = b.y - a.y
        val uz = b.z - a.z
        val vx = c.x - a.x
        val vy = c.y - a.y
        val vz = c.z - a.z
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val normalLength = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
        val light = ((nx * -0.35f + ny * -0.45f + nz * 0.82f) / normalLength)
            .coerceIn(-1f, 1f)
        val depth = ((a.depth + b.depth + c.depth) / 3f).coerceIn(0f, 1f)
        val luminance = (0.38f + light * 0.22f + depth * 0.28f).coerceIn(0.12f, 0.92f)
        val red = (8f + 25f * luminance).toInt().coerceIn(0, 255)
        val green = (55f + 170f * luminance).toInt().coerceIn(0, 255)
        val blue = (90f + 165f * luminance).toInt().coerceIn(0, 255)
        val alpha = if (aiDepth) 222 else 205
        val color = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        return Face(a, b, c, (a.z + b.z + c.z) / 3f, color)
    }

    private fun drawCoverage(canvas: Canvas, w: Float, h: Float) {
        val c = coverage ?: return
        val radius = min(w, h) * 0.43f
        val cx = w / 2f
        val cy = h / 2f
        val sector = 360f / 12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.2f * resources.displayMetrics.density
        for (s in 0 until 12) {
            paint.color = if (c.isVisited(12 + s)) 0xCC55D68A.toInt() else 0x55EF5350
            canvas.drawArc(
                RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                s * sector - 90f, sector - 4f, false, paint
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xF2FFFFFF.toInt()
        paint.textSize = 10f * resources.displayMetrics.density
        canvas.drawText(c.percent().toString() + "% coverage", 10f, h - 6f, paint)
    }

    private fun drawLegend(canvas: Canvas, w: Float, h: Float) {
        val density = resources.displayMetrics.density
        paint.style = Paint.Style.FILL
        paint.color = 0xF2FFFFFF.toInt()
        paint.textSize = 10.5f * density
        val title = when {
            !aiDepth -> "3D SURFACE PREVIEW"
            !live -> "3D RELIEF • RELATIVE DEPTH"
            focusLocked && trackingFound -> "LIVE MODEL • TARGET LOCKED"
            focusLocked -> "LIVE MODEL • REACQUIRING"
            else -> "LIVE MODEL • AI DEPTH"
        }
        canvas.drawText(title, 10f, 17f * density, paint)
        paint.color = 0xCCBDEFFF.toInt()
        paint.textSize = 9f * density
        canvas.drawText("Drag: rotate  •  Pinch: zoom", 10f, 31f * density, paint)
        if (focusLocked) {
            paint.color = if (trackingFound) 0xFF7AF0B0.toInt() else 0xFFFFD36A.toInt()
            canvas.drawText(
                if (trackingFound) "OBJECT FOCUS" else "HOLDING LAST POSITION",
                10f, h - 18f * density, paint
            )
        }
    }
}
