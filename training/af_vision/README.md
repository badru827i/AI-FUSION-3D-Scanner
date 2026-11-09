# AF-Vision MAX training

This folder contains the first reproducible **research scaffold** for AF-Vision MAX:
a compact RGB-to-relative-depth neural network and paired RGB/depth training loop.

It is not pretrained. No model weights are included, and this scaffold does not
yet implement calibrated stereo depth, multi-view pose estimation, or Android
runtime integration.

## 1. Dataset

Use only datasets whose licenses permit your intended research and distribution.
Good candidates to evaluate:

- NYU Depth V2: indoor RGB-D frames with depth supervision.
- ScanNet: indoor RGB-D sequences and camera poses; follow its terms.
- TartanAir: synthetic environments with depth and pose ground truth.

Build a normalized local dataset:

```text
dataset/
  train/
    rgb/
    depth/
  val/
    rgb/
    depth/
```

Each RGB image must have a depth PNG with the same filename stem. Use uint16
depth PNG where possible. Invalid depth is zero. Keep the split scene-disjoint:
frames from the same room/object/sequence must not appear in both train and val.
Record each dataset's license, source, calibration and depth units.

For AF-Vision's first baseline, this script converts valid depth per frame to
relative [0,1] targets. This means it cannot learn absolute meters from this
training recipe. Metric depth requires calibrated scale supervision and a
different loss/evaluation contract.

### Recommended first source: TartanAir V2

Use TartanAir V2 first for the initial pipeline because its official documentation
provides RGB, depth, camera poses and configurable camera models, and states the
dataset is CC BY 4.0 (attribute the dataset and cite its paper). The official
toolkit is MIT-licensed. Check the current official terms before distributing
derived data or model weights:
- Official documentation: https://tartanair.org/
- Official repository/toolkit: https://github.com/castacks/tartanair_tools

ScanNet is useful later for real indoor RGB-D sequences and camera poses, but
download requires acceptance of its Terms of Use; do not mirror its data in
this repository. NYU Depth V2 is another indoor benchmark, but verify the
current source terms and use an official split rather than inventing a random
frame split.

### Convert raw data into the training layout

Create a CSV based on `manifest.example.csv` with columns
`rgb_path,depth_path,scene_id`. Paths are relative to the source root unless
absolute. The preparation script accepts float32 `.npy` depth in meters and
single-channel PNG depth (millimeters by default), filters invalid/out-of-range
pixels, converts depth to uint16 millimeters and assigns entire scenes to only
one split:

```bash
python training/af_vision/prepare_dataset.py \
  --source-root /path/to/tartanair_export \
  --manifest /path/to/manifest.csv \
  --out /path/to/af_vision_dataset \
  --val-fraction 0.15 --seed 42
```

Review the printed pair/scene counts. Both train and validation must contain
valid pairs, and no scene may appear in both. Keep the original dataset and
license/attribution records separate from the generated normalized dataset.

## 2. Train baseline

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r training/af_vision/requirements.txt
python training/af_vision/train.py --data /path/to/dataset --epochs 30 --batch-size 8
```

The best checkpoint is written to `artifacts/af_vision_fp32.pt`.

## 3. INT4 plan

The architecture deliberately separates spatial convolution layers from
Linear projection/MLP layers. The Linear weights are candidates for INT4
weight-only quantization using a supported TorchAO/runtime combination.
Convolution weights should remain FP16/INT8 until a tested backend supports
the required INT4 convolution operators. Do not label the entire network INT4
unless every deployed weight tensor is actually quantized and verified.

Quantization acceptance requires:
- exact runtime/operator support on target Android devices;
- a measured model-size reduction;
- depth accuracy comparison against FP32/FP16;
- latency, memory and thermal measurements;
- a clean fallback when a delegate cannot execute a quantized operator.

## 4. Required next engineering work

1. Add calibrated synchronized stereo pairs and camera intrinsics/extrinsics.
2. Train stereo disparity/depth and confidence heads with scene-disjoint splits.
3. Add visual-inertial pose tracking and multi-view depth alignment outside the
   neural model; fuse point clouds before mesh extraction.
4. Export and validate a supported mobile format (e.g. ONNX or TFLite) and
   implement Android inference/delegate selection.
5. Validate sustained scanning on real Qualcomm and MediaTek devices.

## 5. Honest output labels

Until stereo calibration and scale recovery are implemented, call the output
**relative depth**, not measured distance. A single RGB image produces a
2.5D depth surface, not a complete watertight object scan.


## 5. Export a trained checkpoint to ONNX

Only run this after completing training with a licensed RGB/depth dataset:

```bash
pip install onnx
python training/af_vision/export_onnx.py \
  --checkpoint artifacts/af_vision_fp32.pt \
  --out artifacts/af_vision_max.onnx \
  --height 192 --width 256
```

The exporter requires a checkpoint containing learned weights and the
`depth_kind=relative` contract. It validates output shapes and finite values,
then runs the ONNX graph checker when the `onnx` package is installed.
The exported graph has two outputs: `relative_depth` and `confidence`.
It deliberately refuses to export a randomly initialized model as if it were
trained. ONNX export is a portability milestone, not Android integration:
a compatible Android runtime, delegate tests, and device benchmarks are still
required before adding the model to the APK.
