package com.aifusion.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanExportPipelineTest {

    @Test
    fun recordedFrameAndDepthMapProducePreviewableExportFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = DeviceProfile(
            totalRamMb = 8192,
            cpuCores = 8,
            abi = "x86_64",
            soc = "Android Emulator",
            mode = ScanMode.BALANCED,
            acceleration = "test"
        )
        val session = ScanSession(context, profile)
        var outputDirectory: File? = null

        try {
            session.start()
            outputDirectory = session.framesDir.parentFile

            // Deterministic fixture stands in for one captured frame. The test
            // validates the real session/mesh/export pipeline, not camera hardware.
            val frame = File(session.framesDir, "frame_0000.jpg")
            frame.writeBytes(byteArrayOf(0x41, 0x49, 0x46, 0x55))
            session.recordFrame(frame)

            val width = 8
            val height = 8
            val depth = FloatArray(width * height) { index ->
                (index % width) / (width - 1f)
            }
            session.recordDepthMap(0, depth, width, height)

            val result = session.buildResult(1)
            outputDirectory = result.directory

            assertTrue("OBJ must be present and non-empty", result.obj.isFile && result.obj.length() > 50L)
            assertTrue("GLB must be present and non-empty", result.glb.isFile && result.glb.length() > 100L)
            assertTrue("PLY must be present and non-empty", result.ply.isFile && result.ply.length() > 50L)
            assertTrue("Metadata must be present", result.metadata.isFile)

            val objLines = result.obj.readLines()
            assertEquals("Expected 8x8 surface vertices", 64, objLines.count { it.startsWith("v ") })
            assertEquals("Expected two triangles per grid cell", 98, objLines.count { it.startsWith("f ") })
            assertTrue(result.metadata.readText().contains("\"multiViewFusion\":\"not-yet-implemented\""))
            assertTrue(result.metadata.readText().contains("\"exportFormats\":[\"OBJ\",\"GLB\",\"PLY\"]"))

            val glbHeader = result.glb.readBytes()
            assertEquals('g'.code.toByte(), glbHeader[0])
            assertEquals('l'.code.toByte(), glbHeader[1])
            assertEquals('T'.code.toByte(), glbHeader[2])
            assertEquals('F'.code.toByte(), glbHeader[3])
        } finally {
            outputDirectory?.deleteRecursively()
        }
    }
}
