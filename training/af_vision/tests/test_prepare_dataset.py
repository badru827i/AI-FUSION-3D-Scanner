from pathlib import Path
import importlib.util

import numpy as np
from PIL import Image

MODULE = Path(__file__).resolve().parents[1] / "prepare_dataset.py"
SPEC = importlib.util.spec_from_file_location("prepare_dataset", MODULE)
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


def test_scene_split_is_deterministic_and_scene_based():
    assert prepare.scene_split("room-01", 0.2, 42) == prepare.scene_split("room-01", 0.2, 42)


def test_read_depth_converts_meters_to_uint16_mm(tmp_path):
    depth = np.array([[1.25, 0.0], [3.0, np.nan]], dtype=np.float32)
    path = tmp_path / "depth.npy"
    np.save(path, depth)
    result = prepare.read_depth(path, png_scale=1000.0, max_depth_m=10.0)
    assert result.dtype == np.uint16
    assert result.tolist() == [[1250, 0], [3000, 0]]


def test_read_depth_png_uses_configured_scale(tmp_path):
    path = tmp_path / "depth.png"
    Image.fromarray(np.array([[1000, 0]], dtype=np.uint16)).save(path)
    result = prepare.read_depth(path, png_scale=1000.0, max_depth_m=10.0)
    assert result.tolist() == [[1000, 0]]
