package com.aifusion.scanner

class ScanCoverage {
    private val yawSectors = 12
    private val pitchBands = 3
    private val visited = BooleanArray(yawSectors * pitchBands)
    private var yaw = 0f
    private var pitch = 0f
    private var lastNs = 0L

    fun reset() {
        visited.fill(false)
        yaw = 0f
        pitch = 0f
        lastNs = 0L
    }

    fun update(snapshot: TrackingSnapshot) {
        val now = System.nanoTime()
        if (lastNs != 0L) {
            val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.2f)
            yaw += snapshot.rotationZ * dt
            pitch += snapshot.rotationX * dt
        }
        lastNs = now

        if (snapshot.quality == "TOO_FAST") return

        val yawNorm = ((yaw / (2f * Math.PI.toFloat())) % 1f + 1f) % 1f
        val sector = (yawNorm * yawSectors).toInt().coerceIn(0, yawSectors - 1)
        val pitchBand = when {
            pitch < -0.35f -> 0
            pitch > 0.35f -> 2
            else -> 1
        }
        visited[pitchBand * yawSectors + sector] = true

        if (snapshot.quality == "GOOD") {
            visited[pitchBand * yawSectors + ((sector + yawSectors - 1) % yawSectors)] = true
            visited[pitchBand * yawSectors + ((sector + 1) % yawSectors)] = true
        }
    }

    fun percent(): Int = ((visited.count { it }.toFloat() / visited.size) * 100f).toInt()

    fun isComplete(): Boolean = percent() >= 90

    fun guidance(): String = if (isComplete()) "All directions covered" else "Scan " + missingDirection()

    fun isVisited(index: Int): Boolean = visited.getOrElse(index) { false }

    fun totalCells(): Int = visited.size

    fun missingDirection(): String {
        val top = (0 until yawSectors).count { visited[it] }
        val middle = (0 until yawSectors).count { visited[yawSectors + it] }
        val bottom = (0 until yawSectors).count { visited[2 * yawSectors + it] }
        return when {
            top < yawSectors / 2 -> "top"
            middle < yawSectors / 2 -> "around the sides"
            bottom < yawSectors / 2 -> "bottom"
            else -> "the red/missing areas"
        }
    }
}
