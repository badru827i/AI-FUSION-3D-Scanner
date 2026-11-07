package com.aifusion.scanner.vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

data class CameraFusionStatus(val cameraIds: List<String>, val logicalMultiCameraIds: List<String>, val summary: String)

/** Reports real device capability; megapixel labels do not guarantee concurrent streaming. */
class CameraFusionCapability(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun inspect(): CameraFusionStatus {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val logical = ids.filter { id ->
            runCatching {
                val caps = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            }.getOrDefault(false)
        }
        val summary = when {
            logical.isNotEmpty() -> "Logical multi-camera detected; stream combinations require runtime validation."
            ids.size >= 2 -> "Multiple cameras detected; simultaneous capture is not guaranteed."
            else -> "Single exposed camera path; sequential multi-view fallback required."
        }
        return CameraFusionStatus(ids, logical, summary)
    }
}
