# Scan Intelligence v1

## Purpose
Provide immediate, offline guidance about whether incoming camera frames are suitable for a later reconstruction stage.

## Signals
- Mean luma and dark/bright clipping ratios: rough exposure warnings.
- Luma standard deviation: coarse contrast signal.
- Average neighboring-pixel difference on a downsampled grid: coarse edge/detail signal.

These are quality heuristics, not semantic understanding, autofocus telemetry, motion estimation, depth, or geometry.

## Runtime
CameraX uses KEEP_ONLY_LATEST and a single analysis executor. At most a 48x48 grid is sampled per frame. No frame is uploaded or persisted by this pipeline.

## Next intelligence stages
1. Add camera metadata/AF state and gyro timestamps.
2. Add verified motion and frame-to-frame overlap analysis.
3. Train AF-VisionNet with paired RGB/depth data and report held-out metrics.
4. Connect an Android inference backend only after model export and device tests.
5. Feed calibrated depth plus camera poses into point-cloud registration and mesh fusion.
