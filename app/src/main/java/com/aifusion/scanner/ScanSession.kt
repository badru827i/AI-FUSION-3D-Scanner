package com.aifusion.scanner

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class ScanResult(val name: String, val directory: File, val obj: File, val glb: File)

class ScanSession(private val context: Context, private val profile: DeviceProfile) {
    private val root = File(context.filesDir, "scans")
    lateinit var framesDir: File
        private set
    private val frames = mutableListOf<File>()
    private val tracking = mutableListOf<TrackingSnapshot>()
    private val depthMaps = mutableListOf<File>()

    fun start() {
        val sessionDir = File(root, "scan_${System.currentTimeMillis()}")
        framesDir = File(sessionDir, "frames")
        if (!framesDir.mkdirs() && !framesDir.isDirectory) {
            throw IllegalStateException("Cannot create local scan folder")
        }
        frames.clear()
        tracking.clear()
        depthMaps.clear()
    }

    fun recordFrame(file: File) {
        if (file.isFile && file.length() > 0L) frames += file
    }

    fun recordTracking(frame: Int, snapshot: TrackingSnapshot) {
        tracking += snapshot
    }

    /**
     * Store a compact 8-bit depth snapshot beside the JPEG frames.
     * This is a relative monocular depth surface, not metric depth.
     */
    fun recordDepthMap(frameIndex: Int, depth: FloatArray, width: Int, height: Int) {
        if (width < 2 || height < 2 || width.toLong() * height.toLong() > depth.size) return
        val dir = framesDir.parentFile ?: return
        val file = File(dir, "depth_${frameIndex.toString().padStart(4, '0')}.bin")
        val count = width * height
        val encoded = ByteArray(count)
        for (i in 0 until count) {
            encoded[i] = (depth[i].coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(width)
            output.writeInt(height)
            output.write(encoded)
        }
        depthMaps.removeAll { it.name == file.name }
        depthMaps += file
    }

    fun buildResult(frameCount: Int): ScanResult {
        val dir = framesDir.parentFile ?: root
        val name = dir.name
        val validFrames = frames.filter { it.isFile && it.length() > 0L }
        require(validFrames.isNotEmpty()) { "No camera frames were saved" }
        val usableDepth = depthMaps.lastOrNull { it.isFile && it.length() > 8L }
            ?: throw IllegalStateException("No usable AI depth map was captured; frames are preserved")

        val maxDimension = when (profile.mode) {
            ScanMode.LOW_RAM -> 96
            ScanMode.BALANCED -> 160
            ScanMode.PERFORMANCE -> 224
        }
        val mesh = MeshGenerator.fromDepthMap(usableDepth, maxDimension)
        require(mesh.vertices.size >= 12 && mesh.indices.size >= 6) {
            "AI depth map did not produce a usable surface"
        }

        val obj = File(dir, "$name.obj")
        val glb = File(dir, "$name.glb")
        MeshExporter.writeObj(obj, mesh)
        MeshExporter.writeGlb(glb, mesh)
        require(obj.isFile && obj.length() > 50L) { "OBJ export is incomplete" }
        require(glb.isFile && glb.length() > 100L) { "GLB export is incomplete" }

        val metadata = """{"frames":${validFrames.size},"depthMaps":${depthMaps.count { it.isFile }},"requestedFrameCount":$frameCount,"mode":"${profile.mode}","reconstruction":"single-view-ai-depth-surface","multiViewFusion":"not-yet-implemented","trackingFrames":${tracking.size},"trackingQuality":"${tracking.lastOrNull()?.quality ?: "UNKNOWN"}","obj":"${obj.name}","glb":"${glb.name}"}"""
        File(dir, "scan.json").writeText(metadata)
        return ScanResult(name, dir, obj, glb)
    }
}

data class Mesh(val vertices: FloatArray, val indices: IntArray)

object MeshGenerator {
    /**
     * Builds a 2.5D surface from an actual saved AI depth map.
     * It intentionally does not fabricate a sphere or claim camera-pose fusion.
     */
    fun fromDepthMap(file: File, maxDimension: Int): Mesh {
        val decoded = DataInputStream(file.inputStream().buffered()).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            require(width in 2..1024 && height in 2..1024) { "Invalid depth-map dimensions" }
            val count = width.toLong() * height.toLong()
            require(count <= 1_048_576L) { "Depth map is too large" }
            val source = ByteArray(count.toInt())
            input.readFully(source)
            Triple(width, height, source)
        }
        val width = decoded.first
        val height = decoded.second
        val source = decoded.third

        val outWidth = minOf(width, maxDimension.coerceAtLeast(2))
        val outHeight = minOf(height, maxDimension.coerceAtLeast(2))
        val vertices = FloatArray(outWidth * outHeight * 3)
        var p = 0
        for (y in 0 until outHeight) {
            val sy = y * (height - 1) / (outHeight - 1)
            for (x in 0 until outWidth) {
                val sx = x * (width - 1) / (outWidth - 1)
                val d = (source[sy * width + sx].toInt() and 0xff) / 255f
                vertices[p++] = (x / (outWidth - 1f) - 0.5f) * 2f
                vertices[p++] = (0.5f - y / (outHeight - 1f)) * 2f
                // MiDaS output is relative depth. This scale is for visualization, not metres.
                vertices[p++] = (d - 0.5f) * 1.2f
            }
        }

        val indices = IntArray((outWidth - 1) * (outHeight - 1) * 6)
        var q = 0
        for (y in 0 until outHeight - 1) {
            for (x in 0 until outWidth - 1) {
                val a = y * outWidth + x
                val b = a + 1
                val c = a + outWidth + 1
                val d = a + outWidth
                indices[q++] = a
                indices[q++] = d
                indices[q++] = b
                indices[q++] = b
                indices[q++] = d
                indices[q++] = c
            }
        }
        return Mesh(vertices, indices)
    }
}

object MeshExporter {
    fun writeObj(file: File, mesh: Mesh) {
        file.bufferedWriter().use { w ->
            var i = 0
            while (i < mesh.vertices.size) {
                w.append(String.format(Locale.US, "v %.6f %.6f %.6f\n", mesh.vertices[i], mesh.vertices[i+1], mesh.vertices[i+2]))
                i += 3
            }
            var j = 0
            while (j < mesh.indices.size) {
                w.append("f ${mesh.indices[j]+1} ${mesh.indices[j+1]+1} ${mesh.indices[j+2]+1}\n")
                j += 3
            }
        }
    }

    fun writeGlb(file: File, mesh: Mesh) {
        val pos = ByteBuffer.allocate(mesh.vertices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        mesh.vertices.forEach { pos.putFloat(it) }
        val ind = ByteBuffer.allocate(mesh.indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        mesh.indices.forEach { ind.putInt(it) }
        val bin = ByteArray(pos.position() + ind.position())
        System.arraycopy(pos.array(), 0, bin, 0, pos.position())
        System.arraycopy(ind.array(), 0, bin, pos.position(), ind.position())
        val json = """{"asset":{"version":"2.0","generator":"AI-FUSION 3D Scanner"},"buffers":[{"byteLength":${bin.size}}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${pos.position()},"target":34962},{"buffer":0,"byteOffset":${pos.position()},"byteLength":${ind.position()},"target":34963}],"accessors":[{"bufferView":0,"componentType":5126,"count":${mesh.vertices.size/3},"type":"VEC3"},{"bufferView":1,"componentType":5125,"count":${mesh.indices.size},"type":"SCALAR"}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}"""
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var vertex = 0
        while (vertex < mesh.vertices.size) {
            val x = mesh.vertices[vertex]
            val y = mesh.vertices[vertex + 1]
            val z = mesh.vertices[vertex + 2]
            minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
            vertex += 3
        }
        val gltfJson = json.replace(
            "\"count\":${mesh.vertices.size/3},\"type\":\"VEC3\"}",
            "\"count\":${mesh.vertices.size/3},\"type\":\"VEC3\",\"min\":[$minX,$minY,$minZ],\"max\":[$maxX,$maxY,$maxZ]}"
        )
        val jb = gltfJson.toByteArray(Charsets.UTF_8)
        val jp = (4 - jb.size % 4) % 4
        val bp = (4 - bin.size % 4) % 4
        val total = 12 + 8 + jb.size + jp + 8 + bin.size + bp
        file.outputStream().use { out ->
            fun putInt(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            putInt(0x46546C67); putInt(2); putInt(total)
            putInt(jb.size + jp); putInt(0x4E4F534A); out.write(jb); repeat(jp){out.write(0x20)}
            putInt(bin.size + bp); putInt(0x004E4942); out.write(bin); repeat(bp){out.write(0)}
        }
    }
}
