# AF-Vision MAX implementation status

Updated: 2026-11-08

## Added to repository

- Initial PyTorch AFVisionMAX architecture scaffold.
- RGB + paired depth PNG dataset loader and training loop.
- Scene-disjoint dataset layout and training instructions.
- Guarded TorchAO INT4 weight-only quantization script for eligible Linear layers.
- Tensor contract test for output shapes and ranges.
- Mobile-oriented token-count reduction in the feature stem.

## Not yet completed

- No training dataset is bundled.
- No training run has been executed; no trained weights exist.
- INT4 quantization has not been run or accuracy-validated.
- The current network is single-image relative-depth only.
- Calibrated dual-camera stereo depth, metric scale recovery, visual-inertial pose tracking and multi-view fusion are not implemented by this model scaffold.
- No ONNX/TFLite model has been exported or validated.
- No AF-Vision inference adapter has been connected to the Android APK.
- No APK build or physical-device performance test has been completed.

## Release gates

1. Obtain and document dataset licenses and calibration metadata.
2. Train FP32 baseline and record validation metrics.
3. Evaluate held-out scenes and devices.
4. Apply INT4 to eligible layers and quantify accuracy/size/latency changes.
5. Export to a supported Android runtime and verify operator/delegate coverage.
6. Integrate inference with the scanner's latest-frame pipeline.
7. Validate live mesh quality, thermal stability and memory use on real devices.
