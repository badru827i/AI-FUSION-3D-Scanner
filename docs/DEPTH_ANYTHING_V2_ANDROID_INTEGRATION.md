# Depth Anything V2 Small — Android integration

## Selected pretrained model

AI-FUSION 3D Scanner uses the pretrained **Depth Anything V2 Small** checkpoint through the LiteRT Community TFLite export. No custom training is required for this integration.

- Model card: https://huggingface.co/litert-community/depth-anything-v2-small
- Artifact: `tflite/depth_anything_v2_small_wi8_afp32.tflite`
- License: Apache-2.0 for the Small model.
- Artifact size: approximately 27.73 MB.
- Input: float32 RGB, NCHW, `[1, 3, 518, 686]`.
- Output: float32 relative-depth map, `[1, 518, 686]`.
- Quantization: dynamic INT8 weights with FP32 input/output interface.

The model card specifies a fixed 686 × 518 resize, RGB values scaled to [0,1], then ImageNet channel normalization (mean 0.485/0.456/0.406 and standard deviation 0.229/0.224/0.225). The Android engine follows that tensor contract.

## Runtime policy

1. Try Android NNAPI on non-low-RAM profiles.
2. If unavailable, try the TFLite GPU delegate with FP32 precision (no reduced precision).
3. Fall back to CPU, using fewer threads on LOW_RAM devices.
4. Validate model input/output tensor shapes at startup.
5. Reject non-finite output values rather than passing corrupt depth into reconstruction.

Delegate initialization does not prove the exact hardware unit used. The UI reports the runtime delegate, not an assumed NPU claim. Actual latency, peak memory, thermal stability, and output parity must be measured on each target device family.

## Important depth limitation

Depth Anything V2 Small is a **monocular relative-depth** model. Its output is not calibrated in meters. The normalized depth map is suitable for relative-depth preview and as a depth cue for the existing mesh pipeline, but it must not be presented as measured physical distance.

Metric depth remains gated on a valid synchronized, rectified, calibrated stereo pair and the geometry equation `Z = fx × baseline / disparity`. If the phone cannot supply that pair, the scanner must remain in relative-depth mode.

## Build and validation

The Android workflow downloads the pretrained model into the APK assets and builds the debug APK. The model is not committed as a large binary in Git.

Required device validation before calling the integration complete:
- model loads and output tensor matches the declared shape;
- two different images produce finite, distinct depth maps;
- compare CPU, NNAPI, and GPU output parity where available;
- record inference latency, peak memory, FPS, temperature, and battery impact;
- test at least one Qualcomm and one MediaTek phone, including a low-RAM profile;
- verify OBJ/GLB/PLY output does not label relative depth as metric geometry.

The APK build alone does not prove that runtime inference is correct or fast on every phone.
