"""Prepare a scene-disjoint AF-Vision RGB/depth dataset from a CSV manifest.

CSV columns: rgb_path,depth_path,scene_id
Paths are relative to --source-root unless absolute. Depth may be a uint16 PNG
(in millimeters by default) or a float32 .npy array in meters. Output depth is
always uint16 millimeters; zero denotes invalid depth.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import random
from pathlib import Path

import numpy as np
from PIL import Image


def scene_split(scene_id: str, val_fraction: float, seed: int) -> str:
    digest = hashlib.sha256(f"{seed}:{scene_id}".encode("utf-8")).digest()
    value = int.from_bytes(digest[:8], "big") / float(2**64)
    return "val" if value < val_fraction else "train"


def read_depth(path: Path, png_scale: float, max_depth_m: float) -> np.ndarray:
    if path.suffix.lower() == ".npy":
        depth = np.load(path).astype(np.float32)
    else:
        depth = np.asarray(Image.open(path)).astype(np.float32) / png_scale
    if depth.ndim == 3:
        raise ValueError(f"Expected single-channel depth map: {path}")
    valid = np.isfinite(depth) & (depth > 0) & (depth <= max_depth_m)
    out = np.zeros(depth.shape, dtype=np.uint16)
    out[valid] = np.clip(np.rint(depth[valid] * 1000.0), 1, 65535).astype(np.uint16)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-root", type=Path, required=True)
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--val-fraction", type=float, default=0.15)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--png-depth-scale", type=float, default=1000.0,
                    help="Stored PNG units per meter; default assumes millimeters")
    ap.add_argument("--max-depth-m", type=float, default=10.0)
    ap.add_argument("--min-valid-fraction", type=float, default=0.05)
    args = ap.parse_args()

    if not 0.0 < args.val_fraction < 1.0:
        ap.error("--val-fraction must be between 0 and 1")
    if args.png_depth_scale <= 0 or args.max_depth_m <= 0:
        ap.error("depth scales must be positive")

    rows = []
    with args.manifest.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if not row.get("rgb_path") or not row.get("depth_path") or not row.get("scene_id"):
                continue
            rows.append(row)
    if not rows:
        raise SystemExit("Manifest has no valid rows")

    scenes = sorted({row["scene_id"] for row in rows})
    if len(scenes) < 2:
        raise SystemExit("Need at least two distinct scene_id values for a scene-disjoint train/val split")
    split_by_scene = {scene: scene_split(scene, args.val_fraction, args.seed) for scene in scenes}
    # Guarantee both splits when the hash distribution is very small or skewed.
    if len(set(split_by_scene.values())) < 2:
        ordered = sorted(scenes, key=lambda s: hashlib.sha256(f"{args.seed}:{s}".encode()).hexdigest())
        val_count = min(len(scenes) - 1, max(1, round(len(scenes) * args.val_fraction)))
        val_scenes = set(ordered[:val_count])
        split_by_scene = {scene: ("val" if scene in val_scenes else "train") for scene in scenes}

    counts = {"train": 0, "val": 0}
    skipped = 0
    for row in rows:
        rgb_path = Path(row["rgb_path"])
        depth_path = Path(row["depth_path"])
        if not rgb_path.is_absolute():
            rgb_path = args.source_root / rgb_path
        if not depth_path.is_absolute():
            depth_path = args.source_root / depth_path
        if not rgb_path.is_file() or not depth_path.is_file():
            skipped += 1
            continue
        split = split_by_scene[row["scene_id"]]
        try:
            rgb = Image.open(rgb_path).convert("RGB")
            depth = read_depth(depth_path, args.png_depth_scale, args.max_depth_m)
        except Exception as exc:
            print(f"skip {rgb_path}: {exc}")
            skipped += 1
            continue
        if depth.size == 0 or np.count_nonzero(depth) / depth.size < args.min_valid_fraction:
            skipped += 1
            continue
        # Keep the original RGB resolution; the training loader resizes both.
        stem = f"{row['scene_id'].replace('/', '_')}_{rgb_path.stem}"
        rgb_out = args.out / split / "rgb" / f"{stem}.jpg"
        depth_out = args.out / split / "depth" / f"{stem}.png"
        rgb_out.parent.mkdir(parents=True, exist_ok=True)
        depth_out.parent.mkdir(parents=True, exist_ok=True)
        rgb.save(rgb_out, quality=95)
        Image.fromarray(depth, mode="I;16").save(depth_out)
        counts[split] += 1

    print(f"train_pairs={counts['train']} val_pairs={counts['val']} skipped={skipped}")
    print(f"train_scenes={sum(v == 'train' for v in split_by_scene.values())} "
          f"val_scenes={sum(v == 'val' for v in split_by_scene.values())}")
    if not counts["train"] or not counts["val"]:
        raise SystemExit("Both splits need at least one valid RGB/depth pair")


if __name__ == "__main__":
    main()
