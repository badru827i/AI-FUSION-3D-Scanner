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


## Realtime data routing and exports (v0.9.0)

- A latest-frame router packages frame dimensions, gyroscope/accelerometer motion quality, target-tracking result, depth payload, inference time and total analysis time into one bounded snapshot.
- The router uses a single latest-value slot instead of an unbounded queue. Slow consumers see the newest complete packet rather than building an increasingly stale backlog; old snapshots may be superseded intentionally.
- The selected target is mapped between the camera preview and the rotated analysis frame using the preview's FILL_CENTER crop. The lock button now arms target selection, confirms a previously selected target, and unlocks an active target.
- Completed scans produce OBJ, binary glTF (GLB), PLY and scan.json files inside the local scan session. Save OBJ / GLB / PLY opens Android's document picker to copy each file to a user-selected destination.
- The exported mesh is currently generated from the latest usable monocular relative-depth map. It is still a 2.5D surface rather than calibrated metric depth or a fused, watertight multi-view object. The router and UX changes do not claim that multi-view reconstruction is complete.


## AF-Vision MAX — custom depth AI (research scaffold)

The first PyTorch model/training scaffold is now in `training/af_vision/`:
- compact RGB-to-relative-depth network;
- paired RGB/depth dataset loader and training loop;
- held-out validation loss and tensor contract test;
- guarded TorchAO INT4 weight-only script for eligible Linear layers.

See [AF-Vision training guide](training/af_vision/README.md), [4-bit model specification](docs/AF_VISION_MAX_4BIT_MODEL_SPEC.md), and [implementation status](docs/AF_VISION_MAX_IMPLEMENTATION_STATUS.md).

**Status is intentionally explicit:** architecture and training code are committed, but no dataset has been added, no training run or INT4 quantization has been completed, and no trained weights have been integrated into the Android APK. The current scaffold predicts single-image relative depth; calibrated stereo/metric depth and multi-view fusion remain separate engineering milestones.
