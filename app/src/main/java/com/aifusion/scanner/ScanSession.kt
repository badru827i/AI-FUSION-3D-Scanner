package com.aifusion.scanner

import android.content.Context
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

    fun start() {
        framesDir = File(root, "scan_${System.currentTimeMillis()}/frames")
        framesDir.mkdirs()
        frames.clear()
        tracking.clear()
    }

    fun recordFrame(file: File) {
        if (file.exists()) frames += file
    }

    fun recordTracking(frame: Int, snapshot: TrackingSnapshot) {
        tracking += snapshot
    }

    fun buildResult(frameCount: Int): ScanResult {
        val dir = framesDir.parentFile ?: root
        val name = dir.name
        val quality = when (profile.mode) {
            ScanMode.LOW_RAM -> 16
            ScanMode.BALANCED -> 24
            ScanMode.PERFORMANCE -> 36
        }
        val mesh = MeshGenerator.radialProxy(frameCount.coerceAtLeast(3), quality)
        val obj = File(dir, "\$name.obj")
        MeshExporter.writeObj(obj, mesh)
        val glb = File(dir, "\$name.glb")
        MeshExporter.writeGlb(glb, mesh)
        File(dir, "scan.json").writeText(
            """{"frames":\$frameCount,"mode":"${profile.mode}","reconstruction":"camera-multiview-proxy","trackingFrames":${tracking.size},"trackingQuality":"${tracking.lastOrNull()?.quality ?: "UNKNOWN"}","obj":"${obj.name}","glb":"${glb.name}"}"""
        )
        return ScanResult(name, dir, obj, glb)
    }
}

data class Mesh(val vertices: FloatArray, val indices: IntArray)

object MeshGenerator {
    fun radialProxy(frames: Int, rings: Int): Mesh {
        val segments = frames.coerceIn(3, 72)
        val r = rings.coerceIn(8, 64)
        val vertices = FloatArray((r + 1) * segments * 3)
        var p = 0
        for (y in 0..r) {
            val v = y.toFloat() / r
            val yy = (v - .5f) * 2f
            val radius = kotlin.math.sqrt((1f - yy * yy).coerceAtLeast(.05f))
            for (x in 0 until segments) {
                val a = x.toFloat() / segments * (2f * kotlin.math.PI).toFloat()
                vertices[p++] = kotlin.math.cos(a) * radius
                vertices[p++] = yy
                vertices[p++] = kotlin.math.sin(a) * radius
            }
        }
        val idx = IntArray(r * segments * 6)
        var q = 0
        for (y in 0 until r) for (x in 0 until segments) {
            val n = (x + 1) % segments
            val a = y * segments + x
            val b = y * segments + n
            val c = (y + 1) * segments + n
            val d = (y + 1) * segments + x
            idx[q++] = a; idx[q++] = b; idx[q++] = d
            idx[q++] = b; idx[q++] = c; idx[q++] = d
        }
        return Mesh(vertices, idx)
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
        val jb = json.toByteArray(Charsets.UTF_8)
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
