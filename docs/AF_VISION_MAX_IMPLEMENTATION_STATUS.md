# AF-Vision MAX implementation status

Updated: 2026-11-08

## Android scanner integration progress (2026-11-08)

- Scan mode now switches to an opaque full-screen live depth-mesh canvas and hides the normal camera preview surface while CameraX analysis/capture continues.
- The mesh view uses a denser on-screen grid and labels the current output as relative depth, not measured distance.
- A read-only Android 11+ camera capability probe reports whether the platform advertises two concurrent rear cameras. It does not bind both cameras or perform stereo fusion.
- Fixed a malformed export-error string concatenation found during Android source inspection.

These changes improve the scan UI and establish a capability check, but do not change the depth model: the shipped scanner still runs its existing MiDaS baseline. AF-Vision weights, AF-Vision Android inference, synchronized dual-camera capture, calibrated stereo depth and multi-view mesh fusion are still outstanding.

## Dataset pipeline progress (2026-11-08)

- Added a CSV-manifest dataset preparation tool for paired RGB/depth data.
- Supports float32 `.npy` depth in meters and single-channel PNG depth (millimeters by default); normalizes output to uint16 millimeters with zero as invalid.
- Splits by `scene_id`, not by adjacent frames, and rejects a one-scene manifest so train/validation leakage is less likely.
- Added example manifest and tests for deterministic scene assignment and depth conversion.
- Added a dedicated GitHub Actions Python test job for this preparation pipeline.
- Documented TartanAir V2 as the recommended first dataset source, with attribution and license review. ScanNet remains an agreement-controlled option.

The dataset itself has not been downloaded or committed. No training has started because no authorized dataset files are present in the repository or available runner workspace.

## Added to repository

- Initial PyTorch AFVisionMAX architecture scaffold.
- RGB + paired depth PNG dataset loader and training loop.
- Scene-disjoint dataset preparation tool, example CSV manifest, tests and CI job.
- Dataset source guidance and license notes.
- Guarded TorchAO INT4 weight-only quantization script for eligible Linear layers.
- Tensor contract test for output shapes and ranges.
- Mobile-oriented token-count reduction in the feature stem.

## Not yet completed

- No training dataset is bundled.
- No training run has been executed; no trained weights exist.
- INT4 quantization has not been run or accuracy-validated.
- The current network is single-image relative-depth only.
- Calibrated dual-camera stereo depth, metric scale recovery, visual-inertial pose tracking and multi-view fusion are not implemented by this model scaffold.
- No ONNX/TFLite model has been exported or validated.
- No AF-Vision inference adapter has been connected to the Android APK.
- No APK build or physical-device performance test has been completed.

## Release gates

1. Obtain and document dataset licenses and calibration metadata.
2. Train FP32 baseline and record validation metrics.
3. Evaluate held-out scenes and devices.
4. Apply INT4 to eligible layers and quantify accuracy/size/latency changes.
5. Export to a supported Android runtime and verify operator/delegate coverage.
6. Integrate inference with the scanner's latest-frame pipeline.
7. Validate live mesh quality, thermal stability and memory use on real devices.
