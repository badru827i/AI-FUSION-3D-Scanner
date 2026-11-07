"""Apply TorchAO INT4 weight-only quantization to eligible Linear layers.

Convolution layers intentionally remain floating point in this first pass.
This is a mixed-precision checkpoint, not a claim that the entire network is
INT4. Runtime export and device support must be validated separately.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import torch
from torch import nn

from model import AFVisionMAX


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("artifacts/af_vision_int4.pt"))
    parser.add_argument("--group-size", type=int, default=128)
    args = parser.parse_args()

    try:
        from torchao.quantization import Int4WeightOnlyConfig, quantize_
    except Exception as exc:
        raise SystemExit(
            "TorchAO INT4 APIs are unavailable in this environment. "
            "Install a TorchAO build compatible with your PyTorch version; "
            "do not silently substitute a different quantization format."
        ) from exc

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    model = AFVisionMAX(**{
        "width": checkpoint.get("config", {}).get("width", 48),
        "blocks": checkpoint.get("config", {}).get("blocks", 2),
    })
    model.load_state_dict(checkpoint["model"])
    model.eval()

    config = Int4WeightOnlyConfig(group_size=args.group_size)
    quantize_(
        model,
        config,
        filter_fn=lambda module, fqn: isinstance(module, nn.Linear),
    )

    args.out.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "model": model.state_dict(),
        "architecture": "AFVisionMAX",
        "quantization": "INT4 weight-only on eligible nn.Linear layers; convolution and other tensors remain floating point",
        "group_size": args.group_size,
        "input": checkpoint.get("config", {}).get("input", [3, 192, 256]),
        "source_checkpoint": str(args.checkpoint),
        "runtime_export_validated": False,
    }, args.out)
    print(f"Saved experimental mixed-precision INT4 checkpoint: {args.out}")
    print("Next: compare output accuracy/latency, then export only for a runtime with matching INT4 kernels.")


if __name__ == "__main__":
    main()
