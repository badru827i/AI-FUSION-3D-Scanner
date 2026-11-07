# AI-FUSION 3D Scanner — Native Vision Rebuild

Clean Android-first rewrite for an original, locally trained AI-FUSION vision model and camera-based 3D reconstruction.

## Principles
- No third-party pretrained model or hosted AI API.
- Model architecture and training pipeline are owned by this project.
- Weights are produced by training on approved datasets; random weights are never presented as a trained AI.
- Offline-first Android inference.
- 12MP + 50MP simultaneous capture is capability-gated by Android Camera2 and device stream limits.

## Modules
- app/: Android application and device capability detection.
- model/: model contract and checkpoint placement.
- training/: from-scratch model and training/export pipeline.
- docs/: calibration, depth fusion and validation.

## Current implementation status

- Interactive OpenGL ES 2.0 wireframe viewport is present.
- The mesh preview supports one-finger rotation, two-finger zoom and a demo-only coverage control.
- The displayed geometry is procedural demo geometry. It is **not** produced by the camera, depth AI or a real scan.
- Camera capture, synchronized multi-camera fusion, trained model weights, live depth, mesh reconstruction, texture generation and OBJ export are not implemented yet.
- An Android debug-build GitHub Actions workflow has been added. A successful APK build must be confirmed by a completed workflow run before this branch is considered build-verified.

## Next engineering stages

1. Verify the Android build in GitHub Actions and fix any build errors.
2. Add runtime camera permission and device-supported camera stream inspection.
3. Implement calibrated frame capture and motion/pose tracking.
4. Train and validate the original AF-VisionNet model on paired RGB/depth data.
5. Connect real depth output to incremental mesh reconstruction.
6. Add solid/textured display modes and OBJ export.
