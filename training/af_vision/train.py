"""Train AF-Vision MAX on paired RGB/depth PNG files.

Dataset layout:
  DATA_ROOT/
    train/rgb/<scene>_<frame>.jpg
    train/depth/<scene>_<frame>.png
    val/rgb/<scene>_<frame>.jpg
    val/depth/<scene>_<frame>.png

Depth PNG is read as uint16 by default. Set --depth_scale to convert stored
integer values into meters (e.g. 1000 for millimeters). Invalid zero pixels
are masked. Split by scene/object, never randomly by adjacent frames.
"""
from __future__ import annotations

import argparse
import random
from pathlib import Path

import numpy as np
from PIL import Image
import torch
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

from model import AFVisionMAX


class PairedDepthDataset(Dataset):
    def __init__(self, root: Path, split: str, size: tuple[int, int], depth_scale: float):
        self.rgb_dir = root / split / "rgb"
        self.depth_dir = root / split / "depth"
        self.size = size
        self.depth_scale = depth_scale
        self.items = []
        for rgb in sorted(self.rgb_dir.glob("*")):
            if rgb.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
                continue
            depth = self.depth_dir / (rgb.stem + ".png")
            if depth.exists():
                self.items.append((rgb, depth))
        if not self.items:
            raise RuntimeError(f"No paired RGB/depth files found for split={split} under {root}")

    def __len__(self):
        return len(self.items)

    def __getitem__(self, index):
        rgb_path, depth_path = self.items[index]
        h, w = self.size
        rgb = Image.open(rgb_path).convert("RGB").resize((w, h), Image.Resampling.BILINEAR)
        depth = Image.open(depth_path)
        depth = np.asarray(depth).astype(np.float32) / self.depth_scale
        depth = Image.fromarray(depth).resize((w, h), Image.Resampling.NEAREST)
        depth = np.asarray(depth).astype(np.float32)
        valid = np.isfinite(depth) & (depth > 0)
        if valid.any():
            # Relative-depth baseline: normalize valid target values per frame.
            lo, hi = np.percentile(depth[valid], [2, 98])
            depth = np.clip((depth - lo) / max(float(hi - lo), 1e-6), 0, 1)
        else:
            depth[:] = 0
        image = np.asarray(rgb).astype(np.float32) / 255.0
        image = torch.from_numpy(image).permute(2, 0, 1)
        target = torch.from_numpy(depth).unsqueeze(0)
        mask = torch.from_numpy(valid.astype(np.float32)).unsqueeze(0)
        return image, target, mask


def seed_everything(seed: int):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def masked_silog(pred, target, mask):
    count = mask.sum().clamp_min(1.0)
    diff = (torch.log(pred.clamp_min(1e-4)) - torch.log(target.clamp_min(1e-4))) * mask
    mean_sq = (diff.square().sum() / count)
    mean = diff.sum() / count
    return torch.sqrt((mean_sq - 0.85 * mean.square()).clamp_min(1e-8))


def run_eval(model, loader, device):
    model.eval()
    total, batches = 0.0, 0
    with torch.no_grad():
        for image, target, mask in loader:
            image, target, mask = image.to(device), target.to(device), mask.to(device)
            depth, _ = model(image)
            per_pixel = F.smooth_l1_loss(depth, target, reduction="none")
            total += (per_pixel * mask).sum().item() / mask.sum().clamp_min(1.0).item()
            batches += 1
    return total / max(batches, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=Path("artifacts/af_vision_fp32.pt"))
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--height", type=int, default=192)
    ap.add_argument("--width", type=int, default=256)
    ap.add_argument("--depth-scale", type=float, default=1000.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    seed_everything(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    size = (args.height, args.width)
    train_ds = PairedDepthDataset(args.data, "train", size, args.depth_scale)
    val_ds = PairedDepthDataset(args.data, "val", size, args.depth_scale)
    train_loader = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True, num_workers=2, pin_memory=device.type == "cuda")
    val_loader = DataLoader(val_ds, batch_size=args.batch_size, shuffle=False, num_workers=2)

    model = AFVisionMAX().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    best = float("inf")
    args.out.parent.mkdir(parents=True, exist_ok=True)

    for epoch in range(1, args.epochs + 1):
        model.train()
        running, count = 0.0, 0
        for image, target, mask in train_loader:
            image, target, mask = image.to(device), target.to(device), mask.to(device)
            pred, confidence = model(image)
            valid_count = mask.sum().clamp_min(1.0)
            depth_error = (pred - target).abs()
            depth_loss = (F.smooth_l1_loss(pred, target, reduction="none") * mask).sum() / valid_count
            # Confidence learns whether the predicted depth is accurate, rather
            # than trivially learning that most dataset pixels are valid.
            with torch.no_grad():
                confidence_target = torch.exp(-5.0 * depth_error) * mask
            confidence_error = F.binary_cross_entropy(
                confidence.clamp(1e-5, 1.0 - 1e-5),
                confidence_target,
                reduction="none",
            )
            confidence_weights = mask + 0.1 * (1.0 - mask)
            confidence_loss = (confidence_error * confidence_weights).sum() / confidence_weights.sum().clamp_min(1.0)
            loss = depth_loss + 0.05 * confidence_loss
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            running += loss.item()
            count += 1

        val_loss = run_eval(model, val_loader, device)
        print(f"epoch={epoch} train={running / max(count,1):.5f} val={val_loss:.5f}")
        if val_loss < best:
            best = val_loss
            torch.save({
                "model": model.state_dict(),
                "config": {"width": 48, "blocks": 2, "input": [3, args.height, args.width]},
                "best_val_loss": best,
                "epoch": epoch,
                "depth_kind": "relative",
            }, args.out)
            print(f"saved best checkpoint: {args.out}")

    print("Training complete. This checkpoint is an experimental relative-depth model; validate on held-out devices/scenes.")


if __name__ == "__main__":
    main()
