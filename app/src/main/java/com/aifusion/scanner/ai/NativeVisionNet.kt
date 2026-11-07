package com.aifusion.scanner.ai

/**
 * Runtime gate for the original AF-VisionNet checkpoint.
 *
 * The training graph is defined in training/train_native_vision.py. The Android
 * tensor backend/export adapter is intentionally not marked ready until a
 * trained checkpoint has been evaluated and its exact tensor format is wired
 * into this class. This prevents fabricated depth maps from being shown.
 */
class NativeVisionNet(
    private val checkpointBytes: ByteArray? = null
) {
    enum class State { CHECKPOINT_MISSING, BACKEND_NOT_CONNECTED, READY }

    val state: State
        get() = when {
            checkpointBytes == null || checkpointBytes.isEmpty() -> State.CHECKPOINT_MISSING
            else -> State.BACKEND_NOT_CONNECTED
        }

    val isReady: Boolean
        get() = state == State.READY

    fun infer(rgb: FloatArray, width: Int, height: Int): FloatArray? {
        require(width > 0 && height > 0)
        require(rgb.size == width * height * 3)
        // Returns no depth until the trained AF-VisionNet Android backend is
        // connected. Never substitute synthetic or random depth values.
        return null
    }
}
