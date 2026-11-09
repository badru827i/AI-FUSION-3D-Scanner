"""Train AF-Vision MAX Stereo using rectified RGB pairs and metric depth.

CSV manifest columns:
  left_rgb_path,right_rgb_path,depth_path,scene_id,fx_px,baseline_m

Paths are relative to --source-root unless absolute. Depth PNG is uint16 and
uses --depth-scale stored units per meter (default millimeters). Images must
be synchronized, rectified, and captured with the calibration in the row.
Scene-disjoint splitting is enforced to reduce leakage.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import random
from pathlib import Path

import numpy as np
from PIL import Image
import torch
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

from stereo_model import AFStereoDepth, depth_m_to_disparity_px, disparity_to_depth_m


REQUIRED_COLUMNS = {"left_rgb_path", "right_rgb_path", "depth_path", "scene_id", "fx_px", "baseline_m"}


def read_manifest(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        missing = REQUIRED_COLUMNS - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"manifest missing columns: {', '.join(sorted(missing))}")
        rows = [row for row in reader if all(row.get(k) for k in REQUIRED_COLUMNS)]
    if len(rows) < 2:
        raise ValueError("manifest needs at least two complete stereo rows")
    if len({row["scene_id"] for row in rows}) < 2:
        raise ValueError("manifest needs at least two distinct scene_id values")
    return rows


def split_by_scene(rows: list[dict[str, str]], val_fraction: float, seed: int):
    scenes = sorted({row["scene_id"] for row in rows})
    if not 0.0 < val_fraction < 1.0:
        raise ValueError("val_fraction must be between 0 and 1")
    values = {
        scene: int.from_bytes(hashlib.sha256(f"{seed}:{scene}".encode()).digest()[:8], "big") / 2**64
        for scene in scenes
    }
    val_scenes = {scene for scene, value in values.items() if value < val_fraction}
    if not val_scenes:
        val_scenes = {min(scenes, key=lambda scene: values[scene])}
    if len(val_scenes) == len(scenes):
        val_scenes.remove(max(scenes, key=lambda scene: values[scene]))
    train_rows = [row for row in rows if row["scene_id"] not in val_scenes]
    val_rows = [row for row in rows if row["scene_id"] in val_scenes]
    return train_rows, val_rows


class StereoMetricDataset(Dataset):
    def __init__(
        self,
        rows: list[dict[str, str]],
        source_root: Path,
        size: tuple[int, int],
        depth_scale: float,
        max_depth_m: float,
    ):
        self.rows = rows
        self.root = source_root
        self.height, self.width = size
        self.depth_scale = depth_scale
        self.max_depth_m = max_depth_m
        if depth_scale <= 0 or max_depth_m <= 0:
            raise ValueError("depth_scale and max_depth_m must be positive")

    def __len__(self):
        return len(self.rows)

    def _resolve(self, value: str) -> Path:
        path = Path(value)
        return path if path.is_absolute() else self.root / path

    def __getitem__(self, index: int):
        row = self.rows[index]
        left_path = self._resolve(row["left_rgb_path"])
        right_path = self._resolve(row["right_rgb_path"])
        depth_path = self._resolve(row["depth_path"])
        for path in (left_path, right_path, depth_path):
            if not path.is_file():
                raise FileNotFoundError(path)

        left = Image.open(left_path).convert("RGB")
        right = Image.open(right_path).convert("RGB")
        if left.size != right.size:
            raise ValueError(f"left/right size mismatch for scene {row['scene_id']}")
        original_width, original_height = left.size
        depth = np.asarray(Image.open(depth_path)).astype(np.float32) / self.depth_scale
        if depth.ndim != 2 or depth.shape != (original_height, original_width):
            raise ValueError(f"depth dimensions must match RGB pair for scene {row['scene_id']}")

        target_size = (self.width, self.height)
        left = left.resize(target_size, Image.Resampling.BILINEAR)
        right = right.resize(target_size, Image.Resampling.BILINEAR)
        depth_image = Image.fromarray(depth.astype(np.float32))
        depth = np.asarray(depth_image.resize(target_size, Image.Resampling.NEAREST), dtype=np.float32)
        valid = np.isfinite(depth) & (depth > 0.05) & (depth <= self.max_depth_m)
        depth = np.where(valid, depth, 0.0).astype(np.float32)

        left_tensor = torch.from_numpy(np.asarray(left).copy()).permute(2, 0, 1).float() / 255.0
        right_tensor = torch.from_numpy(np.asarray(right).copy()).permute(2, 0, 1).float() / 255.0
        depth_tensor = torch.from_numpy(depth).unsqueeze(0)
        mask_tensor = torch.from_numpy(valid.astype(np.float32)).unsqueeze(0)

        fx_original = float(row["fx_px"])
        baseline = float(row["baseline_m"])
        if fx_original <= 0 or baseline <= 0:
            raise ValueError("fx_px and baseline_m must be positive")
        fx_resized = fx_original * (self.width / original_width)
        return (
            left_tensor,
            right_tensor,
            depth_tensor,
            mask_tensor,
            torch.tensor(fx_resized, dtype=torch.float32),
            torch.tensor(baseline, dtype=torch.float32),
        )


def seed_everything(seed: int):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def evaluate(model, loader, device, max_depth_m: float):
    model.eval()
    abs_rel_sum = 0.0
    squared_sum = 0.0
    valid_count = 0
    with torch.no_grad():
        for left, right, target_depth, mask, fx, baseline in loader:
            left, right = left.to(device), right.to(device)
            target_depth, mask = target_depth.to(device), mask.to(device)
            fx, baseline = fx.to(device), baseline.to(device)
            disparity, _ = model(left, right)
            predicted_depth = disparity_to_depth_m(disparity, fx, baseline, max_depth_m=max_depth_m)
            valid = (mask > 0.5) & (target_depth > 0) & (target_depth <= max_depth_m)
            if not valid.any():
                continue
            error = (predicted_depth - target_depth).abs()[valid]
            abs_rel_sum += (error / target_depth[valid].clamp_min(1e-6)).sum().item()
            squared_sum += error.square().sum().item()
            valid_count += int(valid.sum().item())
    if valid_count == 0:
        return float("inf"), float("inf")
    return abs_rel_sum / valid_count, (squared_sum / valid_count) ** 0.5


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-root", type=Path, required=True)
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=Path("artifacts/af_stereo_fp32.pt"))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch-size", type=int, default=4)
    ap.add_argument("--height", type=int, default=128)
    ap.add_argument("--width", type=int, default=192)
    ap.add_argument("--depth-scale", type=float, default=1000.0)
    ap.add_argument("--max-depth-m", type=float, default=10.0)
    ap.add_argument("--max-disparity-px", type=int, default=96)
    ap.add_argument("--val-fraction", type=float, default=0.15)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    seed_everything(args.seed)
    rows = read_manifest(args.manifest)
    train_rows, val_rows = split_by_scene(rows, args.val_fraction, args.seed)
    train_ds = StereoMetricDataset(train_rows, args.source_root, (args.height, args.width), args.depth_scale, args.max_depth_m)
    val_ds = StereoMetricDataset(val_rows, args.source_root, (args.height, args.width), args.depth_scale, args.max_depth_m)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    train_loader = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True, num_workers=0)
    val_loader = DataLoader(val_ds, batch_size=args.batch_size, shuffle=False, num_workers=0)
    model = AFStereoDepth(max_disparity_px=args.max_disparity_px).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    best_abs_rel = float("inf")
    args.out.parent.mkdir(parents=True, exist_ok=True)

    for epoch in range(1, args.epochs + 1):
        model.train()
        running = 0.0
        batches = 0
        for left, right, depth, mask, fx, baseline in train_loader:
            left, right = left.to(device), right.to(device)
            depth, mask = depth.to(device), mask.to(device)
            fx, baseline = fx.to(device), baseline.to(device)
            target_disparity = depth_m_to_disparity_px(depth, fx, baseline)
            valid = (mask > 0.5) & (depth > 0) & (target_disparity > 0.25)
            valid &= target_disparity <= args.max_disparity_px
            if not valid.any():
                continue

            predicted_disparity, confidence = model(left, right)
            disparity_error = (predicted_disparity - target_disparity).abs()
            depth_loss = F.smooth_l1_loss(
                predicted_disparity / args.max_disparity_px,
                target_disparity / args.max_disparity_px,
                reduction="none",
            )
            depth_loss = (depth_loss * valid.float()).sum() / valid.float().sum().clamp_min(1.0)
            confidence_target = torch.exp(-disparity_error.detach() / 2.0)
            confidence_loss = F.binary_cross_entropy(
                confidence.clamp(1e-5, 1.0 - 1e-5), confidence_target, reduction="none"
            )
            confidence_loss = (confidence_loss * valid.float()).sum() / valid.float().sum().clamp_min(1.0)
            loss = depth_loss + 0.03 * confidence_loss
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            running += loss.item()
            batches += 1

        if batches == 0:
            raise RuntimeError(
                "No valid stereo training pixels fell inside the configured disparity range; "
                "check depth units, focal length, baseline, and --max-disparity-px."
            )
        val_abs_rel, val_rmse = evaluate(model, val_loader, device, args.max_depth_m)
        print(f"epoch={epoch} train={running / batches:.5f} abs_rel={val_abs_rel:.5f} rmse_m={val_rmse:.4f}")
        if val_abs_rel < best_abs_rel:
            best_abs_rel = val_abs_rel
            torch.save({
                "model": model.state_dict(),
                "config": {
                    "max_disparity_px": args.max_disparity_px,
                    "channels": 24,
                    "input": [3, args.height, args.width],
                },
                "best_val_abs_rel": best_abs_rel,
                "val_rmse_m": val_rmse,
                "depth_kind": "metric_from_calibrated_stereo",
                "calibration_required": True,
            }, args.out)
            print(f"saved best checkpoint: {args.out}")

    print("Training finished. Do not deploy until held-out real-device calibration and accuracy tests pass.")


if __name__ == "__main__":
    main()
