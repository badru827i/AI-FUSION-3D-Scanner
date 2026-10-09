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

## Scanner v0.2

Implemented:
- CameraX multi-frame JPEG capture
- A05s/A27 adaptive LOW_RAM, BALANCED and PERFORMANCE profiles
- Local scan storage and scan metadata
- Lightweight mesh generation with quality levels
- OBJ export
- GLB 2.0 export
- No cloud/server dependency

The current mesh is a lightweight geometry proxy so the APK remains small and stable on low-RAM phones. True learned camera-to-depth reconstruction, texture projection, and ONNX/TFLite GPU/NPU inference are deliberately not claimed as complete until an actual model is bundled and validated.

## Scanner stability + depth-mesh work

Current branch work improves the scan pipeline by:
- limiting live-analysis resolution for lower-memory phones;
- reading CameraX YUV planes using their actual row/pixel strides;
- preventing overlapping capture requests and using bounded exponential retry delays;
- preserving successful camera frames when a later capture or export fails;
- saving compact AI depth snapshots and creating OBJ/GLB geometry from an actual depth map instead of a generated radial sphere;
- recording reconstruction metadata and adding GLB position accessor bounds.

**Important limitation:** the exported geometry is currently a *single-view relative-depth surface* derived from MiDaS output. It is not metric/real-world scale, does not include a projected color texture, and does not yet fuse/align all camera views into a watertight full object. Multi-view pose tracking, depth-map alignment, surface fusion, and on-device validation on multiple phones remain future work. The scanner should not be advertised as a completed photogrammetry solution yet.
