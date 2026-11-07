package com.aifusion.scanner

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.sin
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Interactive OpenGL ES 2.0 preview viewport.
 * Geometry is deliberately a demo mesh, not camera-derived scan data.
 */
class MeshViewport(context: Context) : GLSurfaceView(context) {
    private val meshRenderer = MeshRenderer()
    private var previousX = 0f
    private var previousY = 0f
    private var previousDistance = 0f

    init {
        setEGLContextClientVersion(2)
        setRenderer(meshRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setBackgroundColor(0xFF080F20.toInt())
    }

    fun addDemoCoverage() = queueEvent { meshRenderer.addCoverage() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) previousDistance = spacing(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val distance = spacing(event)
                    if (previousDistance > 0f && distance > 0f) {
                        meshRenderer.zoom(distance / previousDistance)
                    }
                    previousDistance = distance
                } else {
                    meshRenderer.rotate(event.x - previousX, event.y - previousY)
                }
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                previousDistance = 0f
                return true
            }
        }
        return true
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

private class MeshRenderer : GLSurfaceView.Renderer {
    @Volatile private var yaw = -22f
    @Volatile private var pitch = 8f
    @Volatile private var zoom = 1f
    @Volatile private var coverage = 0.42f
    private var program = 0
    private var vertexCount = 0
    private var vertices = FloatArray(0)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var positionHandle = 0
    private var mvpHandle = 0
    private var colorHandle = 0

    fun rotate(dx: Float, dy: Float) {
        yaw += dx * 0.55f
        pitch = (pitch + dy * 0.45f).coerceIn(-78f, 78f)
    }

    fun zoom(factor: Float) {
        zoom = (zoom * factor).coerceIn(0.55f, 2.8f)
    }

    fun addCoverage() {
        coverage = (coverage + 0.10f).coerceAtMost(1f)
        rebuildMesh()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.025f, 0.045f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vertexShader = """
            uniform mat4 uMVP;
            attribute vec3 aPosition;
            void main() { gl_Position = uMVP * vec4(aPosition, 1.0); }
        """.trimIndent()
        val fragmentShader = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """.trimIndent()
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertexShader))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader))
            GLES20.glLinkProgram(it)
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVP")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        Matrix.setLookAtM(view, 0, 0f, 0f, 4.1f, 0f, 0f, 0f, 0f, 1f, 0f)
        rebuildMesh()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 42f, ratio, 0.1f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, zoom, zoom, zoom)
        val mv = FloatArray(16)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        val buffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices).position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, buffer)
        GLES20.glLineWidth(1.7f)
        GLES20.glUniform4f(colorHandle, 0.10f, 0.72f, 1.0f, 1.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun rebuildMesh() {
        val out = ArrayList<Float>()
        val latSteps = 24
        val lonSteps = 48
        fun point(lat: Float, lon: Float): FloatArray {
            val shape = 1f + 0.12f * sin(lat * 3f) * cos(lon * 2f) +
                0.055f * cos(lat * 5f + lon)
            val ring = cos(lat) * shape
            return floatArrayOf(
                0.88f * ring * cos(lon),
                1.28f * sin(lat) * shape,
                0.72f * ring * sin(lon)
            )
        }
        fun addLine(a: FloatArray, b: FloatArray) {
            for (v in a) out.add(v)
            for (v in b) out.add(v)
        }
        for (i in 0..latSteps) {
            val lat = -Math.PI.toFloat() / 2f + Math.PI.toFloat() * i / latSteps
            for (j in 0 until lonSteps) {
                val lon1 = 2f * Math.PI.toFloat() * j / lonSteps
                val lon2 = 2f * Math.PI.toFloat() * (j + 1) / lonSteps
                if (lon1 / (2f * Math.PI.toFloat()) <= coverage) {
                    addLine(point(lat, lon1), point(lat, lon2))
                }
            }
        }
        for (j in 0..lonSteps) {
            val lon = 2f * Math.PI.toFloat() * j / lonSteps
            if (lon / (2f * Math.PI.toFloat()) > coverage) continue
            for (i in 0 until latSteps) {
                val lat1 = -Math.PI.toFloat() / 2f + Math.PI.toFloat() * i / latSteps
                val lat2 = -Math.PI.toFloat() / 2f + Math.PI.toFloat() * (i + 1) / latSteps
                addLine(point(lat1, lon), point(lat2, lon))
            }
        }
        vertices = out.toFloatArray()
        vertexCount = vertices.size / 3
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("OpenGL shader compile failed: $message")
        }
        return shader
    }
}
