# AF-Vision MAX 4-bit — Model & Training Specification

Status: **design/specification scaffold; trained weights are not yet produced**.

## Product goal

Build an original, task-specific on-device vision model for realtime camera-based 3D reconstruction on Android. The model is not a chatbot and must not claim LiDAR-grade or metric depth unless calibrated against measured ground truth.

## Model family

- Name: AF-Vision MAX
- Model profile: 4-bit weight-quantized, mobile vision network
- Primary tasks: relative depth, stereo depth confidence, geometric feature extraction, temporal feature consistency
- Output: depth map + confidence map + compact geometric feature map
- Separate deterministic reconstruction engine: camera pose tracking, depth alignment, point-cloud fusion, mesh extraction and export

## Proposed network

1. **Shared visual encoder** — compact multi-scale convolutional encoder for RGB frames.
2. **Stereo fusion block** — combines synchronized left/right feature maps only after camera intrinsics, extrinsics and timestamps are calibrated.
3. **Depth decoder** — predicts depth/disparity and per-pixel confidence at a bounded live resolution.
4. **Temporal consistency head** — estimates feature consistency between consecutive frames; it does not replace visual-inertial pose tracking.
5. **Geometry feature head** — supplies features for tracking and surface reconstruction.

The 12MP and 50MP labels are sensor resolutions, not a requirement to run neural inference on full-resolution frames. Use camera stream sizes supported by the device, then crop/resize to the model input while retaining calibration metadata.

## Quantization policy

- Target **INT4 weight-only quantization** for eligible layers to reduce model storage and weight bandwidth.
- Keep activations FP16 or INT8 initially; do not force 4-bit activations without measured accuracy and kernel support.
- Keep sensitive output heads and normalization operations at higher precision where required.
- Quantization-aware training (QAT) or calibrated post-training quantization must be evaluated against an FP16/FP32 baseline.
- Export only operators supported by the selected Android runtime/delegate. If INT4 kernels are unavailable on a device, load an INT8 or FP16 fallback rather than silently dequantizing the entire model on the CPU.
- INT4 is a storage/compute format choice, not a promise that every phone's NPU can execute every layer at 4-bit.

## Runtime pipeline

Camera capability probe → synchronized concurrent streams when supported → calibrated image preprocessing → AF-Vision inference → depth/confidence → camera pose estimation (visual-inertial tracking) → depth alignment → multi-frame point-cloud fusion → mesh extraction/cleanup → live renderer → OBJ/GLB/PLY export.

If concurrent camera streams are unsupported, use the best supported single-camera or sequential capture path and label the scan mode accurately.

## Training data and supervision

Train from licensed or otherwise permitted RGB-D and multi-view datasets with depth ground truth, camera intrinsics/extrinsics and camera poses. Candidate research datasets to evaluate include NYU Depth V2, ScanNet and TartanAir; verify each dataset's license and intended use before downloading or training. Add a project-owned capture set from target Android devices with calibration boards and known-size objects.

Training targets:
- depth/disparity regression with valid-pixel masks;
- confidence calibration;
- stereo consistency and left-right consistency;
- temporal consistency across tracked frames;
- edge-aware smoothness without erasing object boundaries;
- geometric consistency after reprojection.

Split by scene/object, not just by frame, to avoid leakage. Keep a held-out device and scene test set.

## Evaluation gates

Do not call the model ready until it passes:
- depth error metrics against ground truth (AbsRel, RMSE, delta accuracy);
- stereo reprojection and calibration checks;
- temporal flicker and tracking-loss tests;
- mesh completeness, hole rate and surface consistency;
- sustained on-device latency, memory and thermal tests;
- A/B comparison of FP16, INT8 and INT4-weight variants;
- validation on Qualcomm and MediaTek devices, with capability-based delegate selection.

Report metric depth only when calibration and scale recovery are available. Otherwise label output as relative depth.

## Android integration contract

- Keep model weights as a separately versioned asset, not a fake placeholder binary.
- Add model metadata: input shape, normalization, output tensors, quantization scheme, operator set, model checksum and minimum runtime version.
- Runtime must expose model availability and selected delegate; never report NPU/GPU acceleration unless actually selected.
- Use bounded latest-frame queues and drop stale frames rather than accumulating latency.
- Provide thermal-aware quality reduction (input resolution / inference cadence) while preserving scan continuity.
- Keep a visible fallback/error state when model weights are absent or unsupported.

## Delivery stages

1. Implement and test calibration + synchronized capture capability detection.
2. Build a reproducible FP16 training baseline and evaluation harness.
3. Train the task-specific depth/stereo model on permitted data.
4. Apply INT4 weight-only quantization and compare accuracy/latency/size.
5. Export runtime-compatible variants and integrate with Android.
6. Validate multi-frame pose/depth fusion and live mesh on physical devices.
7. Only then publish a release APK and describe the model as trained and operational.

## Current repository truth

The current README describes the existing reconstruction as single-view relative-depth output and says multi-view alignment/fusion and validated on-device AI acceleration remain incomplete. This specification does not change that status and does not claim that trained AF-Vision weights or a working INT4 runtime have been delivered.
