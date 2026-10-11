package com.aifusion.scanner

/**
 * Single in-memory source of truth for the latest scan frame.
 * Depth, tracking, generated triangle topology and UI/export consumers share this snapshot.
 * Depth is relative monocular depth; coordinates are not metric without calibration.
 */
data class UnifiedScanSnapshot(
    val frameIndex: Long,
    val timestampMs: Long,
    val depth: FloatArray,
    val width: Int,
    val height: Int,
    val tracking: TrackingSnapshot?,
    val mesh: PolygonMeshData
)

data class PolygonMeshData(
    val vertices: FloatArray,
    val triangleIndices: IntArray,
    val rows: Int,
    val columns: Int
)

class UnifiedScanDataRouter(private val meshMaxDimension: Int = 96) {
    @Volatile private var current: UnifiedScanSnapshot? = null
    @Volatile private var trackingSnapshot: TrackingSnapshot? = null
    private var sequence = 0L

    @Synchronized
    fun publishTracking(snapshot: TrackingSnapshot) {
        trackingSnapshot = snapshot
        val old = current ?: return
        current = old.copy(tracking = snapshot)
    }

    @Synchronized
    fun publishDepth(depth: FloatArray, width: Int, height: Int, timestampMs: Long): UnifiedScanSnapshot? {
        if (width < 2 || height < 2 || width.toLong() * height.toLong() > depth.size) return null
        val safeDepth = depth.copyOf(width * height)
        val mesh = buildMesh(safeDepth, width, height)
        val next = UnifiedScanSnapshot(++sequence, timestampMs, safeDepth, width, height, trackingSnapshot, mesh)
        current = next
        return next
    }

    fun latest(): UnifiedScanSnapshot? = current

    @Synchronized
    fun clear() {
        current = null
        trackingSnapshot = null
        sequence = 0L
    }

    private fun buildMesh(depth: FloatArray, width: Int, height: Int): PolygonMeshData {
        val longest = maxOf(width, height)
        val step = maxOf(1, (longest + meshMaxDimension - 1) / meshMaxDimension)
        val columns = ((width - 1) / step) + 1
        val rows = ((height - 1) / step) + 1
        val vertices = FloatArray(rows * columns * 3)
        var out = 0
        for (row in 0 until rows) {
            val y = minOf(row * step, height - 1)
            for (column in 0 until columns) {
                val x = minOf(column * step, width - 1)
                val z = depth[y * width + x].coerceIn(0f, 1f)
                vertices[out++] = x.toFloat() / (width - 1)
                vertices[out++] = y.toFloat() / (height - 1)
                vertices[out++] = z
            }
        }
        val indices = IntArray(maxOf(0, (rows - 1) * (columns - 1) * 6))
        var index = 0
        for (row in 0 until rows - 1) {
            for (column in 0 until columns - 1) {
                val a = row * columns + column
                val b = a + 1
                val c = a + columns
                val d = c + 1
                indices[index++] = a
                indices[index++] = c
                indices[index++] = b
                indices[index++] = b
                indices[index++] = c
                indices[index++] = d
            }
        }
        return PolygonMeshData(vertices, indices, rows, columns)
    }
}
