# AF-Vision MAX implementation status

Updated: 2026-11-08

## AF-Vision architecture upgrade

- Replaced the first global-attention prototype with a mobile-oriented multi-scale encoder/decoder.
- Added depthwise-separable spatial context, lightweight channel attention and half/quarter-resolution skip paths to retain finer object boundaries.
- Kept the two-output contract: relative depth and per-pixel confidence.
- Added odd-resolution and finite-gradient tests.
- This is still an untrained research architecture scaffold. It does not by itself improve scan accuracy until trained and evaluated on licensed, representative RGB/depth pairs.

## Android scanner integration progress

- Scan mode uses an opaque live depth-mesh canvas and hides the normal camera preview surface while CameraX analysis/capture continues.
- The mesh view uses a denser on-screen grid and labels output as relative depth, not measured distance.
- A read-only Android 11+ camera capability probe reports whether the platform advertises two concurrent rear cameras. It does not bind both cameras or perform stereo fusion.
- The scanner still uses its existing MiDaS baseline for live depth. AF-Vision inference has not yet been connected to the APK.

## Dataset pipeline progress

- Added a CSV-manifest dataset preparation tool for paired RGB/depth data.
- Supports float32 .npy depth in meters and single-channel PNG depth (millimeters by default); normalizes output to uint16 millimeters with zero as invalid.
- Splits by scene_id to reduce train/validation leakage.
- Added example manifest and automated dataset preparation tests.
- TartanAir V2 is documented as a candidate dataset source; verify its current license and attribution terms before use.

The dataset itself has not been downloaded or committed. No AF-Vision training run has been completed and no trained AF-Vision weights exist.

## Remaining work

- Obtain licensed RGB/depth data and record source, license, camera intrinsics and depth units.
- Train the new architecture and report held-out-scene metrics.
- Compare FP32 and quantized accuracy, model size and latency.
- Export to ONNX/TFLite and validate Android runtime/delegate coverage.
- Integrate AF-Vision behind a safe fallback to the current depth model.
- Implement synchronized dual-camera capture, calibration, pose estimation and multi-view geometric fusion before claiming stereo/metric reconstruction.
- Validate thermal stability, memory and mesh quality on physical Android devices.
