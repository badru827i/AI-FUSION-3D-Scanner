# AI-FUSION 3D Scanner

Offline Android 3D-scanner research build. The current app includes a live OpenGL demo viewport and a CameraX analysis input path. The viewport mesh is still procedural demo geometry and is **not** reconstructed from camera frames.

## Current offline scan intelligence

The CameraX analyzer samples the Y/luminance plane and computes frame luminance, luminance variation, clipped-dark/bright ratios, and a coarse spatial edge/detail signal. `ScanIntelligenceEngine` turns those measurements into an interpretable scan-quality score and capture guidance. It is a lightweight deterministic advisor, not a trained neural network, object detector, focus meter, depth model, or metric measurement system.

## Not implemented yet

- Trained AF-VisionNet checkpoint and Android inference backend
- Relative/metric depth estimation
- Camera pose tracking and multi-view registration
- Verified synchronized 12MP + 50MP capture
- Point-cloud generation and true mesh reconstruction
- Surface fusion, texture reconstruction, OBJ/GLB/PLY export

## Build

The GitHub Actions workflow builds the debug APK. A successful build confirms compilation only; device camera behavior and scan accuracy still require physical-device testing.

## Model development

AF-VisionNet training starts from random initialization and requires paired RGB/depth tensors. Do not ship randomly initialized weights or describe them as a working depth model. See `training/train_native_vision.py` and `model/README.md`.
