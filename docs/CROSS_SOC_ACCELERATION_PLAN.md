# AI-FUSION 3D Scanner — Cross-SoC Acceleration Plan

## Goal
Run the scanner across Android brands without requiring a Qualcomm-only NPU. Keep the scan pipeline on-device and provide a correct CPU fallback on every supported device.

## Runtime strategy
1. **Portable baseline:** Android CPU inference with a supported ONNX Runtime or LiteRT/TFLite model. This is the compatibility path and must work when no accelerator delegate is available.
2. **Qualcomm:** attempt the Qualcomm QNN delegate only when the installed runtime, Android ABI, device driver and model are compatible. Otherwise fall back to CPU.
3. **Google Tensor:** use a supported LiteRT delegate or vendor acceleration only when available and validated on that device.
4. **MediaTek / Dimensity:** use a supported LiteRT/ONNX acceleration path when available; do not assume a public NPU delegate exists on every model or firmware.
5. **Samsung Exynos:** use supported Android GPU/runtime delegates where compatible; use NPU only through a supported and validated backend.
6. **Other SoCs:** use GPU acceleration if a compatible delegate works; otherwise CPU.

## Required runtime interface
Implement a backend abstraction such as `InferenceBackend` with:
- `CPU` as the guaranteed fallback;
- optional `GPU` and vendor/NPU backends;
- capability probing and model/delegate compatibility checks;
- bounded memory use, graceful delegate initialization failure and fallback;
- scan status that reports the backend actually running, not merely the hardware present.

## Device profile and quality
- LOW_RAM: smaller input resolution, fewer retained frames, reduced preview detail.
- BALANCED: medium resolution and bounded frame queue.
- PERFORMANCE: higher resolution only when memory and thermal conditions allow.
- Offer FAST and HD scan modes, but dynamically lower workload if the device overheats or runs low on memory.

## Scanner-specific safeguards
- Camera tracking, depth inference and multi-view fusion are separate pipeline stages; NPU acceleration alone does not create a complete 3D reconstruction.
- Preserve camera pose/frame metadata so frames can be aligned and fused.
- Report depth/model limitations honestly; do not claim metric scale, complete surfaces or watertight meshes unless implemented and validated.
- No cloud server is required for the scanning pipeline.

## Validation checklist
- Build and install on ARM64 Android.
- Test CPU-only path first.
- Test each optional delegate independently and verify fallback after initialization or inference errors.
- Record actual backend, latency, peak memory and thermal behavior.
- Validate on Snapdragon, MediaTek, Exynos and Tensor devices where physical test hardware is available.
- Do not label an NPU as active merely because the SoC includes one.
