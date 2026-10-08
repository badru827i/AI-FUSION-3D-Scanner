# AI-FUSION-3D-Scanner

Camera-based 3D scanning for Android, designed to run without LiDAR.

## Test devices
- Samsung Galaxy A05s — low-RAM compatibility test
- Samsung Galaxy A27 5G — balanced/performance test
- Other Android phones — compatibility testing

## Pipeline
Camera capture → multi-view frames → depth estimation → point cloud → mesh → texture → GLB/OBJ export.

## Smart Device Engine
v0.1 detects RAM, CPU cores and ABI and selects LOW_RAM, BALANCED or PERFORMANCE. The base app stays lightweight; ONNX/TFLite depth inference and GPU/NPU delegates are planned as separate modules.

## Status
v0.1.0 — camera capture foundation + adaptive device profile.
