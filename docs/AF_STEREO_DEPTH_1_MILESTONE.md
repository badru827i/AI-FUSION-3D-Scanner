# AF-Vision MAX Stereo — Depth AI milestone 1

## Objective

Add a purpose-built depth path for AI-FUSION 3D Scanner that can produce metric
3D points when the phone supplies a valid calibrated stereo pair, while
retaining monocular relative depth as a universal fallback.

## Architecture

\`\`\`text
Camera2 capability probe
  ├─ valid synchronized physical pair + calibration
  │    └─ rectify / resize pair + scale intrinsics
  │         └─ AF-Vision MAX Stereo
  │              └─ disparity + confidence
  │                   └─ Z = fx * baseline / disparity
  │                        └─ pose alignment + multi-frame fusion
  │                             └─ point cloud / mesh / OBJ-GLB-PLY
  └─ no valid pair or calibration
       └─ AF-Vision MAX monocular
            └─ relative depth + confidence (no meter claim)
\`\`\`

The neural model predicts correspondence disparity, not a free-form meter map.
Camera geometry supplies scale. A confidence map marks regions where the stereo
matching signal is weak; such pixels must not be promoted into trusted geometry.

## Capture gates

- The two views are from a Camera2-supported physical/logical camera pair.
- Streams are concurrent in the same capture session and timestamps are close.
- Camera intrinsics, extrinsics, distortion, rectification, and baseline are known.
- Resolution/crop changes update the intrinsics used by the depth equation.
- Exposure/focus mismatch and rolling-shutter motion are monitored.
- If any gate fails, switch to monocular relative mode; do not pretend that the
  camera pair is stereo-capable merely because the device has two rear lenses.

## Training acceptance

- Manifest records left/right RGB, depth ground truth, scene ID, focal length,
  baseline, units, camera model, and license/source metadata.
- Train/validation are split by scene, with separate test scenes and at least
  one held-out physical device.
- Evaluate disparity error, AbsRel, RMSE meters, confidence calibration, and
  behavior under low texture, reflections, motion, and lighting changes.
- Compare FP32 against mobile quantized variants; record accuracy, latency,
  memory, and temperature on Qualcomm and MediaTek devices.
- No model checkpoint is accepted based only on training loss.

## Android deployment

Use LiteRT's current CompiledModel deployment path as the first runtime
candidate, then test supported CPU/GPU/NPU backends device by device. Keep an
FP16/CPU fallback when an accelerator lacks required operators. Report the
actual backend selected; never claim NPU/GPU use based only on chipset name.

Model input dimensions should be bounded, with inference on selected frames
rather than every full-resolution camera frame. Retain camera metadata and
session keyframes, and fuse valid geometry outside the neural network using
bounded queues. The 3D mesh engine remains a separate deterministic module.

## Build status

Milestone 1 adds the stereo network, geometry conversion, manifest training
loader, tests, and a documented runtime contract. It does not include trained
weights, calibration assets, model conversion, Android integration, or a tested
APK. Those are explicitly later gates.
