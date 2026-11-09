"""Export a trained AF-Vision MAX checkpoint to a portable ONNX model.

This exports learned weights only. It refuses to export random initialization.
The model predicts relative depth and confidence, not metric distance.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import torch

from model import AFVisionMAX


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("artifacts/af_vision_max.onnx"))
    parser.add_argument("--height", type=int, default=192)
    parser.add_argument("--width", type=int, default=256)
    parser.add_argument("--opset", type=int, default=17)
    args = parser.parse_args()

    if not args.checkpoint.is_file():
        raise FileNotFoundError(f"Trained checkpoint not found: {args.checkpoint}")
    if args.height < 64 or args.width < 64:
        raise ValueError("Export dimensions must be at least 64x64")

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    if not isinstance(checkpoint, dict) or "model" not in checkpoint:
        raise ValueError("Checkpoint must contain the trained 'model' state_dict")
    if checkpoint.get("depth_kind") != "relative":
        raise ValueError("Unsupported checkpoint depth contract; expected relative depth")

    config = checkpoint.get("config", {})
    model = AFVisionMAX(
        width=int(config.get("width", 48)),
        blocks=int(config.get("blocks", 2)),
    ).cpu().eval()
    model.load_state_dict(checkpoint["model"], strict=True)

    sample = torch.rand(1, 3, args.height, args.width)
    with torch.no_grad():
        depth, confidence = model(sample)
    expected = (1, 1, args.height, args.width)
    if tuple(depth.shape) != expected or tuple(confidence.shape) != expected:
        raise RuntimeError("AF-Vision output shape does not match the export contract")
    if not torch.isfinite(depth).all() or not torch.isfinite(confidence).all():
        raise RuntimeError("Model produced non-finite values during export validation")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        sample,
        str(args.out),
        input_names=["rgb"],
        output_names=["relative_depth", "confidence"],
        opset_version=args.opset,
        do_constant_folding=True,
        dynamic_axes={
            "rgb": {0: "batch", 2: "height", 3: "width"},
            "relative_depth": {0: "batch", 2: "height", 3: "width"},
            "confidence": {0: "batch", 2: "height", 3: "width"},
        },
        dynamo=False,
    )

    try:
        import onnx
        graph = onnx.load(str(args.out))
        onnx.checker.check_model(graph)
    except ImportError:
        print("onnx package not installed; graph checker skipped (install onnx to validate).")

    print(f"Exported trained AF-Vision MAX ONNX: {args.out}")
    print(f"checkpoint_epoch={checkpoint.get('epoch', 'unknown')}")
    print(f"held_out_val_loss={checkpoint.get('best_val_loss', 'unknown')}")
    print("outputs=relative_depth,confidence; metric_scale=false")


if __name__ == "__main__":
    main()
