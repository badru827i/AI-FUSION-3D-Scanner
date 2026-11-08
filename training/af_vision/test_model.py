"""Shape, range, odd-resolution and gradient tests for AF-Vision MAX."""
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


def test_odd_input_resolution_is_preserved():
    model = AFVisionMAX(width=32, blocks=1).eval()
    image = torch.rand(1, 3, 65, 97)
    with torch.no_grad():
        depth, confidence = model(image)
    assert depth.shape == (1, 1, 65, 97)
    assert confidence.shape == (1, 1, 65, 97)


def test_training_loss_has_finite_gradients():
    model = AFVisionMAX(width=32, blocks=1).train()
    image = torch.rand(2, 3, 64, 80)
    depth, confidence = model(image)
    loss = depth.mean() + confidence.mean()
    loss.backward()
    grads = [p.grad for p in model.parameters() if p.requires_grad]
    assert grads and all(g is not None and torch.isfinite(g).all() for g in grads)
