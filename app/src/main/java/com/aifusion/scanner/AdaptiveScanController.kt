package com.aifusion.scanner

/**
 * User intent for one scan. AUTO adapts to device capability; the two explicit
 * modes change sampling/preview priorities. They do not imply multi-view fusion
 * until camera-pose alignment and surface fusion are implemented.
 */
enum class ScanIntent(val displayName: String) {
    AUTO("Auto"),
    SMALL_DETAIL("Small Detail"),
    LARGE_COVERAGE("Large Coverage")
}

data class AdaptiveScanSettings(
    val intent: ScanIntent,
    val captureIntervalMs: Long,
    val depthIntervalMs: Long,
    val jpegQuality: Int,
    val meshMaxDimension: Int,
    val useFullFramePreview: Boolean,
    val pressureLevel: Int
)

/**
 * Chooses conservative per-device defaults and throttles depth inference when
 * measured inference time indicates that the current workload is too heavy.
 * CPU remains the baseline; this controller does not assume an NPU exists.
 */
class AdaptiveScanController(private val profile: DeviceProfile) {
    var selectedIntent: ScanIntent = ScanIntent.AUTO
        private set

    private var pressureLevel = 0
    private var fastInferenceStreak = 0

    fun cycleIntent(): ScanIntent {
        selectedIntent = when (selectedIntent) {
            ScanIntent.AUTO -> ScanIntent.SMALL_DETAIL
            ScanIntent.SMALL_DETAIL -> ScanIntent.LARGE_COVERAGE
            ScanIntent.LARGE_COVERAGE -> ScanIntent.AUTO
        }
        resetPressure()
        return selectedIntent
    }

    fun beginScan() {
        resetPressure()
    }

    fun settings(): AdaptiveScanSettings {
        val lowRam = profile.mode == ScanMode.LOW_RAM
        val performance = profile.mode == ScanMode.PERFORMANCE

        val captureBase = when (selectedIntent) {
            ScanIntent.AUTO -> if (lowRam) 1200L else if (performance) 750L else 850L
            ScanIntent.SMALL_DETAIL -> if (lowRam) 1050L else if (performance) 650L else 780L
            ScanIntent.LARGE_COVERAGE -> if (lowRam) 1250L else if (performance) 820L else 930L
        }

        val depthBase = when (selectedIntent) {
            ScanIntent.AUTO -> if (lowRam) 1100L else if (performance) 450L else 650L
            ScanIntent.SMALL_DETAIL -> if (lowRam) 1000L else if (performance) 400L else 550L
            ScanIntent.LARGE_COVERAGE -> if (lowRam) 1200L else if (performance) 600L else 800L
        }

        val jpegQuality = when (selectedIntent) {
            ScanIntent.AUTO -> if (lowRam) 75 else if (performance) 92 else 90
            ScanIntent.SMALL_DETAIL -> if (lowRam) 82 else if (performance) 95 else 92
            ScanIntent.LARGE_COVERAGE -> if (lowRam) 72 else if (performance) 90 else 86
        }

        val meshDimension = when (selectedIntent) {
            ScanIntent.AUTO -> when (profile.mode) {
                ScanMode.LOW_RAM -> 96
                ScanMode.BALANCED -> 160
                ScanMode.PERFORMANCE -> 224
            }
            ScanIntent.SMALL_DETAIL -> when (profile.mode) {
                ScanMode.LOW_RAM -> 112
                ScanMode.BALANCED -> 192
                ScanMode.PERFORMANCE -> 224
            }
            ScanIntent.LARGE_COVERAGE -> when (profile.mode) {
                ScanMode.LOW_RAM -> 96
                ScanMode.BALANCED -> 144
                ScanMode.PERFORMANCE -> 192
            }
        }

        val intervalMultiplier = 1.0 + pressureLevel * 0.30
        return AdaptiveScanSettings(
            intent = selectedIntent,
            captureIntervalMs = captureBase,
            depthIntervalMs = (depthBase * intervalMultiplier).toLong(),
            jpegQuality = jpegQuality,
            meshMaxDimension = meshDimension,
            useFullFramePreview = selectedIntent == ScanIntent.LARGE_COVERAGE,
            pressureLevel = pressureLevel
        )
    }

    /** Call after a successful inference; use a few quick samples to recover. */
    fun observeDepthInference(inferenceMs: Long) {
        val slowThreshold = when (profile.mode) {
            ScanMode.LOW_RAM -> 1450L
            ScanMode.BALANCED -> 950L
            ScanMode.PERFORMANCE -> 700L
        }

        if (inferenceMs > slowThreshold) {
            pressureLevel = (pressureLevel + 1).coerceAtMost(3)
            fastInferenceStreak = 0
        } else if (inferenceMs < (slowThreshold * 0.65f).toLong()) {
            fastInferenceStreak++
            if (fastInferenceStreak >= 3 && pressureLevel > 0) {
                pressureLevel--
                fastInferenceStreak = 0
            }
        } else {
            fastInferenceStreak = 0
        }
    }

    fun statusLabel(): String = when (selectedIntent) {
        ScanIntent.AUTO -> "AUTO"
        ScanIntent.SMALL_DETAIL -> "SMALL DETAIL"
        ScanIntent.LARGE_COVERAGE -> "LARGE COVERAGE"
    }

    private fun resetPressure() {
        pressureLevel = 0
        fastInferenceStreak = 0
    }
}
