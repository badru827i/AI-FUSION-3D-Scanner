"""Lightweight shape/range test; run with pytest."""
import torch
from model import AFVisionMAX


def test_depth_and_confidence_contract():
    model = AFVisionMAX().eval()
    image = torch.rand(1, 3, 96, 128)
    with torch.no_grad():
        depth, confidence = model(image)
    assert depth.shape == (1, 1, 96, 128)
    assert confidence.shape == (1, 1, 96, 128)
    assert torch.isfinite(depth).all()
    assert torch.isfinite(confidence).all()
    assert depth.min() >= 0 and depth.max() <= 1
    assert confidence.min() >= 0 and confidence.max() <= 1
