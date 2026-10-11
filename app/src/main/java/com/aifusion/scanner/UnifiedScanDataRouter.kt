package com.aifusion.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Low-memory, single source of truth for scan data.
 *
 * Publishes immutable-by-convention snapshots: input depth is copied, sanitised,
 * and mesh indices refer to the same normalised depth surface. Consumers must not
 * mutate arrays. Depth is relative monocular depth, not metric distance.
 */
data class UnifiedScanSnapshot(
    val frameIndex: Long,
    val timestampMs: Long,
    val depth: FloatArray,
    val width: Int,
    val height: Int,
    val tracking: TrackingSnapshot?,
    val mesh: PolygonMeshData,
    val quality: ScanDataQuality
)

data class ScanDataQuality(
    val validDepthRatio: Float,
    val minDepth: Float,
    val maxDepth: Float,
    val meanDepth: Float,
    val rejectedTriangles: Int,
    val generatedAtMs: Long
)

data class PolygonMeshData(
    /** xyz triples, x/y normalised to [0,1], z relative depth [0,1]. */
    val vertices: FloatArray,
    /** Triangle indices; triangles crossing large depth discontinuities are omitted. */
    val triangleIndices: IntArray,
    val rows: Int,
    val columns: Int
) {
    val vertexCount: Int get() = vertices.size / 3
    val triangleCount: Int get() = triangleIndices.size / 3
}

class UnifiedScanDataRouter(
    private val meshMaxDimension: Int = 96,
    private val depthEdgeThreshold: Float = 0.18f
) {
    @Volatile private var current: UnifiedScanSnapshot? = null
    @Volatile private var trackingSnapshot: TrackingSnapshot? = null
    private var sequence = 0L

    init {
        require(meshMaxDimension in 8..256) { "meshMaxDimension must be 8..256" }
        require(depthEdgeThreshold in 0.01f..1f) { "depthEdgeThreshold must be 0.01..1.0" }
    }

    @Synchronized
    fun publishTracking(snapshot: TrackingSnapshot) {
        trackingSnapshot = snapshot
        val old = current ?: return
        current = old.copy(tracking = snapshot)
    }

    @Synchronized
    fun publishDepth(
        depth: FloatArray,
        width: Int,
        height: Int,
        timestampMs: Long
    ): UnifiedScanSnapshot? {
        if (width < 2 || height < 2) return null
        val count = width.toLong() * height.toLong()
        if (count > depth.size.toLong() || count > MAX_DEPTH_SAMPLES) return null

        val safeDepth = FloatArray(count.toInt())
        var valid = 0
        var minDepth = 1f
        var maxDepth = 0f
        var sum = 0.0
        for (i in safeDepth.indices) {
            val raw = depth[i]
            // NaN/Inf cannot poison geometry or quality metrics.
            val value = if (raw.isFinite()) raw.coerceIn(0f, 1f) else Float.NaN
            safeDepth[i] = value
            if (value.isFinite()) {
                valid++
                minDepth = min(minDepth, value)
                maxDepth = max(maxDepth, value)
                sum += value.toDouble()
            }
        }
        if (valid == 0) return null

        // Fill isolated invalid samples from the nearest valid neighbour, bounded
        // to a small local search. This avoids holes caused by one bad inference value.
        sanitiseInvalidSamples(safeDepth, width, height)
        val mesh = buildMesh(safeDepth, width, height)
        val now = timestampMs.coerceAtLeast(0L)
        val quality = ScanDataQuality(
            validDepthRatio = valid.toFloat() / safeDepth.size,
            minDepth = minDepth,
            maxDepth = maxDepth,
            meanDepth = (sum / valid).toFloat(),
            rejectedTriangles = max(0, ((mesh.rows - 1) * (mesh.columns - 1) * 2) - mesh.triangleCount),
            generatedAtMs = now
        )
        val next = UnifiedScanSnapshot(
            frameIndex = ++sequence,
            timestampMs = now,
            depth = safeDepth,
            width = width,
            height = height,
            tracking = trackingSnapshot,
            mesh = mesh,
            quality = quality
        )
        current = next
        return next
    }

    /** Returns the latest coherent depth/tracking/mesh snapshot, or null before first depth. */
    fun latest(): UnifiedScanSnapshot? = current

    /** Drop all per-scan state so frames from two scans cannot be mixed. */
    @Synchronized
    fun clear() {
        current = null
        trackingSnapshot = null
        sequence = 0L
    }

    private fun sanitiseInvalidSamples(depth: FloatArray, width: Int, height: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (depth[index].isFinite()) continue
                var replacement = 0.5f
                var found = false
                for (radius in 1..2) {
                    search@ for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            if (max(abs(dx), abs(dy)) != radius) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val candidate = depth[ny * width + nx]
                            if (candidate.isFinite()) {
                                replacement = candidate
                                found = true
                                break@search
                            }
                        }
                    }
                    if (found) break
                }
                depth[index] = replacement
            }
        }
    }

    private fun buildMesh(depth: FloatArray, width: Int, height: Int): PolygonMeshData {
        val longest = max(width, height)
        val step = max(1, (longest + meshMaxDimension - 1) / meshMaxDimension)
        val columns = ((width - 1) / step) + 1
        val rows = ((height - 1) / step) + 1
        val vertices = FloatArray(rows * columns * 3)
        val sampledDepth = FloatArray(rows * columns)
        var out = 0
        var sample = 0
        for (row in 0 until rows) {
            val y = min(row * step, height - 1)
            for (column in 0 until columns) {
                val x = min(column * step, width - 1)
                val z = depth[y * width + x].coerceIn(0f, 1f)
                sampledDepth[sample++] = z
                vertices[out++] = x.toFloat() / (width - 1)
                vertices[out++] = y.toFloat() / (height - 1)
                vertices[out++] = z
            }
        }

        // Build a compact index buffer only for triangles whose depth edges are
        // plausible. This reduces long spikes at object/background boundaries.
        val indices = IntArray(max(0, (rows - 1) * (columns - 1) * 6))
        var index = 0
        for (row in 0 until rows - 1) {
            for (column in 0 until columns - 1) {
                val a = row * columns + column
                val b = a + 1
                val c = a + columns
                val d = c + 1
                val za = sampledDepth[a]
                val zb = sampledDepth[b]
                val zc = sampledDepth[c]
                val zd = sampledDepth[d]
                if (maxOf(abs(za - zb), abs(za - zc), abs(zb - zc)) <= depthEdgeThreshold) {
                    indices[index++] = a
                    indices[index++] = c
                    indices[index++] = b
                }
                if (maxOf(abs(zb - zc), abs(zb - zd), abs(zc - zd)) <= depthEdgeThreshold) {
                    indices[index++] = b
                    indices[index++] = c
                    indices[index++] = d
                }
            }
        }
        return PolygonMeshData(vertices, indices.copyOf(index), rows, columns)
    }

    private companion object {
        // Hard ceiling prevents accidental multi-megabyte arrays from malformed callers.
        const val MAX_DEPTH_SAMPLES = 1_048_576L
    }
}
