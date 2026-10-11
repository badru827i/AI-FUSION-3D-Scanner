# AI-FUSION 3D Scanner

An on-device Android camera scanner prototype for phones without LiDAR. The project targets a wide range of Android devices, with the Samsung Galaxy A05s as a low-memory baseline and Galaxy A27 5G as a mid-range test target.

## Adaptive scan modes

The scan-mode button cycles through three strategies:

- **AUTO** — conservative sampling, selected from the detected device profile.
- **SMALL DETAIL** — tighter live-depth preview, higher capture JPEG quality, shorter depth-sampling interval, and a larger mesh budget where the device allows it.
- **LARGE COVERAGE** — full-frame depth preview and a lower-cost analysis stream to help scan larger objects without focusing the preview on a tracked patch.

The user can lock an object to guide visual tracking. Large Coverage deliberately keeps the reconstruction preview full-frame even when a tracking target is locked.

## Adaptive device and runtime budget

- SmartDeviceEngine selects LOW_RAM, BALANCED, or PERFORMANCE from detected RAM and CPU cores.
- AdaptiveScanController selects camera capture cadence, depth inference cadence, JPEG quality, and mesh output resolution.
- After each successful depth inference, runtime inference time is measured. Slow inference increases the depth interval; several quick inferences are required before the controller reduces throttling again.
- The scanner uses a CPU baseline and may use the TensorFlow Lite GPU delegate when supported. It does not assume every Android phone exposes a usable GPU or NPU delegate.
- Captured frames and exported scan files are stored locally. No cloud or server is required for a scan.

## Current implementation

- CameraX preview, still capture, and bounded live analysis.
- Gyroscope/accelerometer motion guidance.
- Lightweight locked-target tracking with confidence and recovery feedback.
- Hologram grid overlay and live relative-depth relief preview.
- MiDaS-small TensorFlow Lite inference, with a GPU delegate attempt and CPU fallback.
- OBJ and GLB 2.0 export plus per-scan JSON metadata.
- Metadata records scan intent, selected device profile, mesh budget, and final adaptive pressure level.

## Important limitations

**This is not yet a complete multi-view photogrammetry or metric 3D scanner.** MiDaS provides relative monocular depth rather than measured real-world distances. The exported OBJ/GLB is currently a 2.5D surface generated from the last usable saved depth map; frames from different camera positions are saved but are not yet aligned and fused into one watertight full-object mesh. Projected colour textures, calibrated metric scale, and robust reconstruction for textureless, glossy, or very dark objects are also not yet complete.

The scan modes change the sampling, preview and mesh budget used by the current pipeline; Large Coverage does not yet claim that multiple views are fused into a complete object. Real accuracy and stability must be validated on physical devices and against objects of known dimensions before making professional-scanner claims.

## Build

The GitHub Actions workflow downloads and checksum-verifies the MiDaS TFLite model, builds the Android debug APK with JDK 17 / Gradle, and publishes the APK as a workflow artifact. The model is fetched during CI rather than included as a binary in Git.

### Live 3D checkpoint saving

During a scan, use **Save 3D Now** to export the latest usable AI depth surface to the current scan session as OBJ and GLB without stopping capture. The button reports when the first usable depth map is not ready yet. Checkpoint export is serialized with scan-session writes to reduce file-list races; the final scan export still runs when scanning is stopped. The saved geometry remains a single-view relative-depth surface, not a fused full-object scan.

## Unified scan data and polygon mesh router

The scanner now has a lightweight `UnifiedScanDataRouter` that publishes the latest relative-depth snapshot together with tracking metadata and a shared triangle-mesh topology. The camera/depth path publishes into this snapshot, captured frames read the latest routed depth for session storage, and the 3D preview renders from the same depth result. The mesh is a downsampled triangle surface intended for low-memory Android devices.

**Accuracy note:** this is still a monocular relative-depth surface, not a metric LiDAR scan or a fused watertight full-object mesh. Real multi-view fusion requires calibrated camera poses and cross-frame surface alignment.
