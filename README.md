# AI-FUSION 3D Scanner — Native Vision Rebuild

Clean Android-first rewrite for an original, locally trained AI-FUSION vision model and camera-based 3D reconstruction.

## Principles
- No hosted AI API or cloud inference.
- Model architecture and training pipeline are owned by this project.
- Weights must be trained and evaluated on approved datasets; random weights are never presented as a trained AI.
- Offline-first Android camera and inference pipeline.
- Dual-camera capture is capability-gated by Android Camera2 and device stream limits; two rear sensors do not guarantee simultaneous capture.

## Modules
- app/: Android application, CameraX frame input and device capability detection.
- model/: model contract and checkpoint placement.
- training/: model training and export pipeline.
- docs/: calibration, depth fusion and validation.

## Current implementation status

- Interactive OpenGL ES 2.0 wireframe viewport is present, with rotation, pinch zoom and demo-only coverage.
- CameraX back-camera ImageAnalysis input is implemented with runtime permission handling, latest-frame backpressure, frame dimensions, rotation, timestamp and sampled luma statistics.
- Camera frames are processed off the UI thread and closed after analysis. No camera preview is shown in the main viewport.
- The displayed geometry remains procedural demo geometry. It is **not** generated from camera frames.
- Trained AF-VisionNet weights, Android depth inference, synchronized dual-camera capture, pose tracking, depth fusion, real mesh reconstruction, texture generation and OBJ/PLY export are not implemented yet.
- GitHub Actions builds a debug APK. A successful completed workflow run is required before calling the branch build-verified.

## Next engineering stages

1. Confirm the CameraX build and test camera permission/lifecycle on physical Android devices.
2. Add camera intrinsics calibration, exposure/focus controls and motion/pose tracking.
3. Train and validate AF-VisionNet on paired RGB/depth data.
4. Connect real depth output to incremental point-cloud and mesh reconstruction.
5. Add calibrated dual-camera capture only on devices whose stream combinations pass runtime checks.
6. Add mesh repair, solid/textured display and OBJ/PLY export.
