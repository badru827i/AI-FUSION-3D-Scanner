from pathlib import Path

import numpy as np
from PIL import Image
import pytest

from train_stereo import StereoMetricDataset, read_manifest, split_by_scene


def _make_scene(root: Path, name: str, fx: float = 100.0, baseline: float = 0.05):
    rgb = np.zeros((16, 24, 3), dtype=np.uint8)
    rgb[..., 0] = 120
    depth = np.full((16, 24), 2000, dtype=np.uint16)
    for suffix in ("left", "right"):
        Image.fromarray(rgb).save(root / f"{name}_{suffix}.png")
    Image.fromarray(depth).save(root / f"{name}_depth.png")
    return {
        "left_rgb_path": f"{name}_left.png",
        "right_rgb_path": f"{name}_right.png",
        "depth_path": f"{name}_depth.png",
        "scene_id": name,
        "fx_px": str(fx),
        "baseline_m": str(baseline),
    }


def test_dataset_resize_scales_focal_length(tmp_path):
    row = _make_scene(tmp_path, "scene_a", fx=100.0, baseline=0.06)
    dataset = StereoMetricDataset([row], tmp_path, (8, 12), 1000.0, 10.0)
    left, right, depth, mask, fx, baseline = dataset[0]
    assert left.shape == right.shape == (3, 8, 12)
    assert depth.shape == mask.shape == (1, 8, 12)
    assert fx.item() == pytest.approx(50.0)
    assert baseline.item() == pytest.approx(0.06)
    assert depth.mean().item() == pytest.approx(2.0)
    assert mask.mean().item() == pytest.approx(1.0)


def test_scene_split_never_leaks_scene_ids():
    rows = [
        {"scene_id": "a"}, {"scene_id": "a"},
        {"scene_id": "b"}, {"scene_id": "b"},
        {"scene_id": "c"},
    ]
    train, val = split_by_scene(rows, 0.34, 42)
    train_scenes = {r["scene_id"] for r in train}
    val_scenes = {r["scene_id"] for r in val}
    assert train_scenes and val_scenes
    assert train_scenes.isdisjoint(val_scenes)


def test_manifest_requires_calibration_columns(tmp_path):
    manifest = tmp_path / "manifest.csv"
    manifest.write_text(
        "left_rgb_path,right_rgb_path,depth_path,scene_id\\na,b,c,s1\\n",
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="missing columns"):
        read_manifest(manifest)
