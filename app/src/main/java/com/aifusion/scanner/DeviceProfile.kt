package com.aifusion.scanner

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale

enum class ScanMode { LOW_RAM, BALANCED, PERFORMANCE }

data class DeviceProfile(
    val totalRamMb: Long,
    val cpuCores: Int,
    val abi: String,
    val soc: String,
    val mode: ScanMode,
    val acceleration: String
)

object SmartDeviceEngine {
    fun detect(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val ramMb = info.totalMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val soc = listOf(Build.HARDWARE, Build.BOARD, Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() }.distinct().joinToString(" / ")
        val mode = when {
            ramMb < 5000 || cores <= 4 -> ScanMode.LOW_RAM
            ramMb >= 7000 && cores >= 6 -> ScanMode.PERFORMANCE
            else -> ScanMode.BALANCED
        }
        val acceleration = if (Build.VERSION.SDK_INT >= 31) "CPU/GPU delegate ready" else "CPU"
        return DeviceProfile(ramMb, cores, abi, soc.ifBlank { "unknown" }, mode, acceleration)
    }

    fun summary(profile: DeviceProfile): String =
        String.format(
            Locale.US,
            "%s • %d MB RAM • %d cores • %s",
            profile.mode.name.replace('_', ' '),
            profile.totalRamMb,
            profile.cpuCores,
            profile.acceleration
        )
}
