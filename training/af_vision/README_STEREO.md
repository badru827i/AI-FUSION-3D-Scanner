# AF-Vision MAX Stereo — calibrated metric-depth branch

This is a new, separate research path alongside the existing monocular
AFVisionMAX model. It is **not trained** and is not yet included in the APK.

## Why a separate stereo model?

The original monocular model predicts relative depth. Its output is useful for
building a 2.5D preview, but a single RGB frame has no reliable absolute scale
without an independent scale source. This stereo model predicts horizontal
pixel disparity from a synchronized, rectified left/right pair. Metric depth is
then derived from the camera calibration:

\`depth_m = focal_length_px * baseline_m / disparity_px\`

This provides a physically defined scale only when the focal length, physical
baseline, lens distortion, rectification, and frame synchronization are valid.
The AI estimates disparity; calibration supplies the metric scale.

## Manifest format

Create a CSV with these required columns:

\`\`\`csv
left_rgb_path,right_rgb_path,depth_path,scene_id,fx_px,baseline_m
left/room01_0001.png,right/room01_0001.png,depth/room01_0001.png,room01,320.0,0.25
left/room02_0001.png,right/room02_0001.png,depth/room02_0001.png,room02,320.0,0.25
\`\`\`

- Left/right images must be synchronized and rectified stereo views, not two
  unrelated lenses or sequential frames.
- \`fx_px\` is the rectified horizontal focal length at the original image size.
- \`baseline_m\` is the measured distance between camera optical centers in meters.
- Depth maps are single-channel uint16 PNG; default storage is millimeters.
- \`scene_id\` identifies a whole room/object/sequence. It is split as a group.
- Ensure depth and RGB are spatially registered and carry the correct camera model.

## Train

\`\`\`bash
pip install -r training/af_vision/requirements.txt
python training/af_vision/train_stereo.py \\
  --source-root /path/to/stereo_dataset \\
  --manifest /path/to/stereo_manifest.csv \\
  --epochs 30 --batch-size 4 --height 128 --width 192 \\
  --max-disparity-px 96
\`\`\`

The best checkpoint is saved to \`artifacts/af_stereo_fp32.pt\`. The validation
report includes AbsRel and RMSE in meters. These values are meaningful only
when the held-out depth maps and calibration are genuine and correctly scaled.

## Phone runtime requirements

1. Probe Camera2 physical-camera IDs, synchronization type, stream combinations,
   and whether both streams can run in the same capture session.
2. Calibrate intrinsics/extrinsics and verify rectification with a checkerboard
   or another calibration target. Do not infer baseline from camera housing.
3. Timestamp paired frames; reject pairs with excessive temporal skew or
   exposure mismatch.
4. Feed only rectified pairs into \`AFStereoDepth\`.
5. Convert disparity to depth using the exact \`fx_px\` and \`baseline_m\` for the
   resized/rectified stream. Scale \`fx_px\` when input width changes.
6. Reject low-confidence and near-zero-disparity pixels; mark them unknown.
7. Feed metric depth and camera poses to the separate multi-view fusion engine.

If dual-camera capture or calibration is unavailable, keep the existing
monocular relative-depth path and label it clearly as **relative depth**. Do not
silently describe it as a meter measurement. A phone may advertise multiple
physical cameras without guaranteeing that arbitrary streams can be captured
simultaneously; query and test each device at runtime.

## Current delivery status

- [x] New stereo disparity model architecture.
- [x] Metric conversion tied to explicit camera calibration.
- [x] Scene-disjoint manifest loader and training loop.
- [x] Unit tests for geometry, tensor shapes, gradients, and dataset metadata.
- [ ] Licensed synchronized stereo/depth dataset added and audited.
- [ ] Training run completed; FP32 checkpoint produced.
- [ ] Held-out scene and held-out phone evaluation passed.
- [ ] ONNX/LiteRT export validated on real hardware.
- [ ] Android camera synchronization, calibration, stereo inference, and mesh fusion integrated.

Until all required items pass, this is a model-development milestone, not a
ready-to-use metric-depth feature.
