import pytest
import torch

from stereo_model import AFStereoDepth, depth_m_to_disparity_px, disparity_to_depth_m


def test_metric_geometry_round_trip():
    depth = torch.tensor([[[[1.0, 2.0], [4.0, 8.0]]]])
    disparity = depth_m_to_disparity_px(depth, focal_length_px=500.0, baseline_m=0.08)
    recovered = disparity_to_depth_m(disparity, focal_length_px=500.0, baseline_m=0.08)
    assert torch.allclose(recovered, depth, atol=1e-5)


def test_zero_disparity_is_bounded_not_infinite():
    disparity = torch.zeros(1, 1, 2, 2)
    depth = disparity_to_depth_m(disparity, 500.0, 0.08, max_depth_m=12.0)
    assert torch.isfinite(depth).all()
    assert torch.all(depth == 12.0)


def test_invalid_calibration_rejected():
    with pytest.raises(ValueError):
        disparity_to_depth_m(torch.ones(1, 1, 2, 2), 0.0, 0.08)
    with pytest.raises(ValueError):
        depth_m_to_disparity_px(torch.ones(1, 1, 2, 2), 500.0, -0.08)


def test_stereo_model_output_and_gradient():
    model = AFStereoDepth(max_disparity_px=32, channels=8)
    left = torch.rand(1, 3, 64, 96, requires_grad=True)
    right = torch.rand(1, 3, 64, 96, requires_grad=True)
    disparity, confidence = model(left, right)
    assert disparity.shape == (1, 1, 64, 96)
    assert confidence.shape == (1, 1, 64, 96)
    assert torch.isfinite(disparity).all()
    assert torch.isfinite(confidence).all()
    assert torch.all(disparity >= 0)
    assert torch.all((confidence >= 0) & (confidence <= 1))
    (disparity.mean() + confidence.mean()).backward()
    assert left.grad is not None and torch.isfinite(left.grad).all()


def test_stereo_model_rejects_unpaired_shapes():
    model = AFStereoDepth(max_disparity_px=32, channels=8)
    with pytest.raises(ValueError):
        model(torch.rand(1, 3, 32, 32), torch.rand(1, 3, 32, 31))
