package com.aifusion.scanner

import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshExportTest {

    @Test
    fun depthMapProducesConsistentMeshTopology() = withTempDir { dir ->
        val depthFile = writeDepthMap(dir, 8, 8)
        val mesh = MeshGenerator.fromDepthMap(depthFile, 8)

        assertEquals("Each vertex must contain x/y/z", 8 * 8 * 3, mesh.vertices.size)
        assertEquals("Each grid cell must become two triangles", 7 * 7 * 6, mesh.indices.size)
        assertTrue("Vertex positions must be finite", mesh.vertices.all { it.isFinite() })
        assertTrue("Every triangle index must refer to a real vertex",
            mesh.indices.all { it in 0 until 64 })
    }

    @Test
    fun objPlyAndGlbExportsContainValidStructure() = withTempDir { dir ->
        val mesh = MeshGenerator.fromDepthMap(writeDepthMap(dir, 8, 8), 8)
        val obj = File(dir, "sample.obj")
        val ply = File(dir, "sample.ply")
        val glb = File(dir, "sample.glb")

        MeshExporter.writeObj(obj, mesh)
        MeshExporter.writePly(ply, mesh)
        MeshExporter.writeGlb(glb, mesh)

        val objLines = obj.readLines()
        val vertices = objLines.filter { it.startsWith("v ") }
        val faces = objLines.filter { it.startsWith("f ") }
        assertEquals(64, vertices.size)
        assertEquals(7 * 7 * 2, faces.size)
        faces.forEach { face ->
            face.removePrefix("f ").trim().split(Regex("\\s+")).forEach { token ->
                val index = token.toInt()
                assertTrue("OBJ face index must be 1-based and in range", index in 1..vertices.size)
            }
        }

        val plyText = ply.readText()
        assertTrue(plyText.startsWith("ply\\nformat ascii 1.0\\n"))
        assertTrue(plyText.contains("element vertex 64\\n"))
        assertTrue(plyText.contains("element face 98\\n"))
        assertTrue(plyText.contains("end_header\\n"))

        val glbBytes = glb.readBytes()
        assertTrue("GLB must contain a header", glbBytes.size >= 20)
        assertEquals('g'.code.toByte(), glbBytes[0])
        assertEquals('l'.code.toByte(), glbBytes[1])
        assertEquals('T'.code.toByte(), glbBytes[2])
        assertEquals('F'.code.toByte(), glbBytes[3])
        val header = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("GLB version must be 2", 2, header.getInt(4))
        assertEquals("GLB length header must match actual file size", glbBytes.size, header.getInt(8))
    }

    @Test(expected = EOFException::class)
    fun truncatedDepthMapFailsClearlyInsteadOfProducingAnEmptyMesh() = withTempDir { dir ->
        val file = File(dir, "truncated.bin")
        DataOutputStream(file.outputStream()).use {
            it.writeInt(4)
            it.writeInt(4)
            it.write(byteArrayOf(0, 1, 2))
        }
        MeshGenerator.fromDepthMap(file, 4)
    }

    private fun writeDepthMap(dir: File, width: Int, height: Int): File {
        val file = File(dir, "depth.bin")
        val bytes = ByteArray(width * height) { index ->
            ((index % width) * 255 / (width - 1)).toByte()
        }
        DataOutputStream(file.outputStream().buffered()).use {
            it.writeInt(width)
            it.writeInt(height)
            it.write(bytes)
        }
        return file
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("aifusion-mesh-test-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
