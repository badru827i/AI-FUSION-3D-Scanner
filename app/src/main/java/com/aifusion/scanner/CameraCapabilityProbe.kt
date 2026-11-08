package com.aifusion.scanner

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Read-only capability probe. It does not open or bind a second camera.
 * "Concurrent" means Android advertises a supported combination of two rear
 * camera IDs; actual synchronized depth fusion remains a separate step.
 */
object CameraCapabilityProbe {
    fun summary(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Dual rear: capability API unavailable"
        }

        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facingById = manager.cameraIdList.associateWith { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING)
                }.getOrNull()
            }
            val hasConcurrentRearPair = manager.concurrentCameraIds.any { combination ->
                combination.count { id ->
                    facingById[id] == CameraCharacteristics.LENS_FACING_BACK
                } >= 2
            }

            if (hasConcurrentRearPair) {
                "Dual rear: concurrent capable • fusion pending"
            } else {
                "Dual rear: single-camera fallback"
            }
        } catch (_: Throwable) {
            "Dual rear: capability unknown"
        }
    }
}