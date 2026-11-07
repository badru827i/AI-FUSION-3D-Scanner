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

## Status
Architecture scaffold. The model must be trained, exported, installed in app assets and validated on target hardware before live AI depth or 3D reconstruction is called functional.
