# AI-FUSION 3D Scanner — FAST / HD Reconstruction Plan

Status: implementation specification (not a claim that the features are already implemented).

## Goal

Produce a useful, inspectable 3D reconstruction from phone-camera capture. The scan screen must show (1) the real camera feed and tracking status, (2) scan coverage, and (3) a live mesh that is built from captured geometry—not a decorative cube or a hologram drawn over the camera.

The scanner must stay on-device and adapt work to the phone's available memory, thermal state, and graphics capability.

## User flow

1. Open **Scan** and see the camera preview, tracking quality, and a small live 3D preview.
2. Move around the object or rotate the object while keeping the camera/object motion supported by the tracking pipeline.
3. Use coverage hints to revisit poorly observed regions.
4. Tap **Finish Scan**.
5. Choose **FAST** for a quick, lower-cost reconstruction or **HD** for a heavier refinement pass using saved capture data.
6. Inspect the resulting model by orbiting, panning, and zooming; then save/export it.

## Two real reconstruction modes

### FAST

- Keep the capture responsive by limiting queued frames and rejecting blurry/duplicate observations.
- Update a coarse preview mesh frequently.
- Use a conservative point/voxel budget and lower texture resolution.
- Prefer a quick, valid model over dense but unstable geometry.
- Make progress visible and allow the user to stop or regenerate.

### HD

- Reuse saved keyframes and their camera poses; do not require a second scan when the retained data is sufficient.
- Refine alignment and fusion, allow denser geometry within the device's memory budget, and build higher-resolution textures from sharp, well-exposed frames.
- Run heavy work off the UI thread and in bounded chunks. Show real progress stages (alignment, fusion, meshing, texturing, cleanup).
- If source coverage or texture detail is inadequate, explain the limitation instead of inventing details.

FAST and HD must change actual processing settings and output quality; they must not merely change the preview's shading.

## Capture and reconstruction pipeline

1. **Camera frames:** receive frames from CameraX or the existing camera module. Respect camera lifecycle and avoid retaining unnecessary full-resolution bitmaps.
2. **Tracking / pose:** use the project's existing visual-inertial tracking implementation where present. If ARCore is used, check tracking state and feature support at runtime. Treat Depth API as an optional input, not as a requirement on every phone.
3. **Frame selection:** keep a frame only when it adds useful coverage, is sharp enough, and has a valid pose. Reject near-identical frames and pause capture when tracking is lost.
4. **Geometry:** fuse valid depth observations when supported; otherwise use the project's calibrated multi-view reconstruction path. RGB-only capture must not be represented as metric depth unless the algorithm has enough information to estimate geometry.
5. **Live mesh:** rebuild/update a coarse mesh incrementally from observed geometry. Keep camera preview, pose estimation, and rendering decoupled so meshing cannot freeze the UI.
6. **Coverage map:** project accepted observations into the model/view and classify regions as observed, weakly observed, or not observed. Use these states for scan guidance.
7. **Finalization:** perform heavier fusion, mesh cleanup, normal generation and texture baking as supported by the retained data.
8. **Export:** save a native session plus an OBJ mesh and associated texture files. Add FBX only if a reliable exporter is available in the project; do not silently write a different format under an FBX extension.

## Preview requirements

- A persistent live 3D viewport with orbit, pan, and zoom.
- The partial mesh must grow only when real reconstruction data is accepted.
- Distinguish clearly between **observed surface**, **weak/uncertain surface**, and **not scanned**. Use a subtle grid/hologram overlay only as a coverage aid; it is not geometry.
- Show tracking states such as **Tracking**, **Limited tracking**, and **Tracking lost**.
- Provide explicit states for **Preparing**, **Scanning**, **Rebuilding preview**, **Finalizing**, **Exporting**, **Completed**, and **Recoverable error**.
- Never show a made-up percentage. Use stage-based progress unless the backend can calculate actual work completion.

## Adaptive device budget

Create one runtime budget manager from measured signals rather than trusting model/brand names alone:

- Available memory and memory pressure.
- Recent frame-processing time and preview frame rate.
- Thermal status where the platform exposes it.
- GPU capability and optional depth support.
- User-selected FAST/HD mode.

When pressure rises, first reduce preview mesh density and texture resolution, cap the queue, and defer HD-only operations. Do not silently discard the original session data needed for later HD processing. If the app cannot retain enough session data, tell the user before finishing.

Avoid separate unbounded queues for frames, point clouds, and textures. Prefer bounded producer/consumer stages and release native buffers promptly.

## Suggested core models

- `ScanSession`: ID, capture settings, timestamps, output paths, session status.
- `CaptureFrame`: image/depth references, camera intrinsics, pose, sharpness/quality score, accepted/rejected reason.
- `ScanCoverage`: observed and uncertain surface/voxel regions.
- `ReconstructionSettings`: mode, geometry budget, texture size, frame-selection thresholds.
- `ReconstructionStage`: stage name, progress when measurable, message, cancelability.
- `ScanResult`: mesh path, texture paths, bounds, quality warnings, export formats.

Keep these independent from the UI so reconstruction can be tested without a phone camera.

## Error handling and user guidance

- If tracking is lost, pause frame acceptance and ask the user to return to a textured, well-lit view.
- If the object is black, glossy, transparent, or textureless, warn that tracking/geometry may be less reliable; suggest diffuse lighting and additional viewpoints.
- If memory is low, reduce live-preview detail first and offer FAST finalization. Never crash just to preserve HD settings.
- If too little coverage exists, allow a retake or export with a clear incomplete-coverage warning.
- Never claim LiDAR-level measurements unless they have been calibrated and validated against a known reference.

## Acceptance checklist

- [ ] Camera preview remains responsive while a mesh is being updated.
- [ ] At least two distinct viewpoints produce visible, data-backed growth in the live mesh.
- [ ] Previously observed geometry stays aligned when new views are added; no obvious duplicate shells from repeated frames.
- [ ] Unscanned/weakly observed areas are visually distinct from reconstructed surfaces.
- [ ] FAST finalization produces a saved mesh and reports any coverage limitations.
- [ ] HD finalization demonstrably uses higher reconstruction/texture budgets when supported, and reuses the saved session.
- [ ] Cancel/retry and app-background/foreground transitions release camera and native buffers safely.
- [ ] Low-memory handling reduces detail before the app reaches an out-of-memory crash.
- [ ] OBJ export includes required material/texture files when texture export is enabled.
- [ ] Automated tests cover frame selection, session state transitions, coverage classification, and export paths; a device test checks preview responsiveness.

## Implementation order

1. Inspect and document the current capture, tracking, mesh, preview, and export modules.
2. Connect the live 3D viewport to real partial reconstruction data.
3. Add observed/uncertain/unscanned coverage feedback.
4. Add FAST finalization and a session format that preserves useful source data.
5. Add bounded HD refinement, progress stages, cancellation, and recovery.
6. Benchmark on a low-memory phone and a mid-range phone; tune budgets from recorded measurements.
7. Add FBX only after validating round-trip output in a compatible 3D viewer.

**Important:** this file is a build plan. The live mesh, FAST/HD processor, and exports are not considered implemented until their corresponding code and device tests exist.
