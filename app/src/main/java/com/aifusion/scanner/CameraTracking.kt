package com.aifusion.scanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

data class TrackingSnapshot(
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    val motion: Float,
    val quality: String
)

class CameraTracking(context: Context, private val profile: DeviceProfile) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var rx = 0f
    private var ry = 0f
    private var rz = 0f
    private var ax = 0f
    private var ay = 0f
    private var az = 0f
    private var running = false

    fun start() {
        if (running) return
        running = true
        val delay = if (profile.mode == ScanMode.LOW_RAM)
            SensorManager.SENSOR_DELAY_UI else SensorManager.SENSOR_DELAY_GAME
        gyro?.let { manager.registerListener(this, it, delay) }
        accel?.let { manager.registerListener(this, it, delay) }
    }

    fun stop() {
        if (!running) return
        manager.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                rx = event.values[0]
                ry = event.values[1]
                rz = event.values[2]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]
                ay = event.values[1]
                az = event.values[2]
            }
        }
    }

    fun snapshot(): TrackingSnapshot {
        val rotation = sqrt(rx * rx + ry * ry + rz * rz)
        val linear = (sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH).coerceAtLeast(0f)
        val motion = rotation + linear
        val quality = when {
            motion > 5f -> "TOO_FAST"
            motion > 2f -> "MOVE_SLOWER"
            else -> "GOOD"
        }
        return TrackingSnapshot(rx, ry, rz, motion, quality)
    }

    fun status(): String = when (snapshot().quality) {
        "TOO_FAST" -> "Move slower"
        "MOVE_SLOWER" -> "Good • move slower"
        else -> "Good movement"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
