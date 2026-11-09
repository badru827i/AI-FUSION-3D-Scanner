"""AF-Vision MAX Stereo: compact rectified-stereo disparity network.

The network predicts pixel disparity and a confidence map. Metric depth is
computed separately from measured camera calibration: Z = fx_px * baseline_m
/ disparity_px. Inputs MUST be synchronized, rectified left/right RGB frames.
This model is an untrained research implementation until trained weights exist.
"""
from __future__ import annotations

import math

import torch
from torch import nn
import torch.nn.functional as F


class ConvNormAct(nn.Sequential):
    def __init__(self, cin: int, cout: int, stride: int = 1, groups: int = 1):
        super().__init__(
            nn.Conv2d(cin, cout, kernel_size=3, stride=stride, padding=1,
                      groups=groups, bias=False),
            nn.GroupNorm(math.gcd(8, cout), cout),
            nn.SiLU(inplace=True),
        )


class DepthwiseResidual(nn.Module):
    def __init__(self, channels: int, expansion: int = 2):
        super().__init__()
        hidden = channels * expansion
        self.block = nn.Sequential(
            ConvNormAct(channels, channels, groups=channels),
            nn.Conv2d(channels, hidden, 1, bias=False),
            nn.GroupNorm(math.gcd(8, hidden), hidden),
            nn.SiLU(inplace=True),
            nn.Conv2d(hidden, channels, 1, bias=False),
            nn.GroupNorm(math.gcd(8, channels), channels),
        )
        self.act = nn.SiLU(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.act(x + self.block(x))


class StereoFeatureEncoder(nn.Module):
    """Shared encoder; output stride is four pixels."""
    def __init__(self, channels: int = 24):
        super().__init__()
        self.net = nn.Sequential(
            ConvNormAct(3, channels // 2, stride=2),
            ConvNormAct(channels // 2, channels, stride=2),
            DepthwiseResidual(channels),
            DepthwiseResidual(channels),
        )

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        return F.normalize(self.net(image), p=2, dim=1, eps=1e-6)


class AFStereoDepth(nn.Module):
    """RGB stereo pair -> disparity in input-image pixels + confidence.

    Inputs:
        left_rgb, right_rgb: float [B,3,H,W], RGB in [0,1], already
            undistorted, rectified, synchronized, and identically resized.
    Outputs:
        disparity_px: [B,1,H,W], horizontal disparity in input pixels.
        confidence: [B,1,H,W], softmax peak confidence in [0,1].
    """
    feature_stride = 4

    def __init__(self, max_disparity_px: int = 96, channels: int = 24):
        super().__init__()
        if max_disparity_px < self.feature_stride:
            raise ValueError("max_disparity_px must be at least 4")
        if channels < 8 or channels % 8 != 0:
            raise ValueError("channels must be a positive multiple of 8")
        self.max_disparity_px = int(max_disparity_px)
        self.encoder = StereoFeatureEncoder(channels)
        disparities = self.max_disparity_px // self.feature_stride + 1
        self.regularizer = nn.Sequential(
            ConvNormAct(disparities, 32),
            ConvNormAct(32, 32),
            nn.Conv2d(32, disparities, kernel_size=3, padding=1),
        )

    @staticmethod
    def _cost_volume(left: torch.Tensor, right: torch.Tensor, count: int) -> torch.Tensor:
        _, _, _, width = left.shape
        costs = []
        scale = math.sqrt(left.shape[1])
        for disparity in range(count):
            if disparity == 0:
                shifted = right
            elif disparity >= width:
                shifted = torch.full_like(right, -1.0)
            else:
                shifted = F.pad(right[..., :-disparity], (disparity, 0), value=0.0)
            correlation = (left * shifted).sum(dim=1) / scale
            if disparity > 0:
                invalid = torch.zeros_like(correlation)
                invalid[..., :disparity] = -1.0
                correlation = torch.where(invalid < 0, invalid, correlation)
            costs.append(correlation)
        return torch.stack(costs, dim=1)

    def forward(self, left_rgb: torch.Tensor, right_rgb: torch.Tensor):
        if left_rgb.ndim != 4 or right_rgb.ndim != 4:
            raise ValueError("stereo inputs must have shape [B,3,H,W]")
        if left_rgb.shape != right_rgb.shape or left_rgb.shape[1] != 3:
            raise ValueError("left/right inputs must have identical [B,3,H,W] shapes")
        if left_rgb.shape[-2] < 8 or left_rgb.shape[-1] < 8:
            raise ValueError("stereo input dimensions must be at least 8x8")

        input_size = left_rgb.shape[-2:]
        left_features = self.encoder(left_rgb)
        right_features = self.encoder(right_rgb)
        disparity_count = self.max_disparity_px // self.feature_stride + 1
        cost = self._cost_volume(left_features, right_features, disparity_count)
        logits = self.regularizer(cost)
        probabilities = torch.softmax(logits, dim=1)
        disparity_indices = torch.arange(
            disparity_count, device=left_rgb.device, dtype=probabilities.dtype
        ).view(1, disparity_count, 1, 1)
        coarse_disparity = (probabilities * disparity_indices).sum(dim=1, keepdim=True)
        coarse_confidence = probabilities.amax(dim=1, keepdim=True)

        disparity_px = F.interpolate(
            coarse_disparity * self.feature_stride,
            size=input_size,
            mode="bilinear",
            align_corners=False,
        )
        confidence = F.interpolate(
            coarse_confidence,
            size=input_size,
            mode="bilinear",
            align_corners=False,
        ).clamp(0.0, 1.0)
        return disparity_px, confidence


def disparity_to_depth_m(
    disparity_px: torch.Tensor,
    focal_length_px: torch.Tensor | float,
    baseline_m: torch.Tensor | float,
    min_disparity_px: float = 0.25,
    max_depth_m: float = 100.0,
) -> torch.Tensor:
    """Convert calibrated rectified disparity to metric depth in meters.

    This is geometry, not a learned scale estimate. Invalid/near-zero disparity
    maps to max_depth_m. Calibration values must be positive.
    """
    if min_disparity_px <= 0 or max_depth_m <= 0:
        raise ValueError("min_disparity_px and max_depth_m must be positive")
    fx = torch.as_tensor(focal_length_px, dtype=disparity_px.dtype, device=disparity_px.device)
    baseline = torch.as_tensor(baseline_m, dtype=disparity_px.dtype, device=disparity_px.device)
    if torch.any(fx <= 0) or torch.any(baseline <= 0):
        raise ValueError("focal_length_px and baseline_m must be positive")
    while fx.ndim < disparity_px.ndim:
        fx = fx.unsqueeze(-1)
    while baseline.ndim < disparity_px.ndim:
        baseline = baseline.unsqueeze(-1)
    safe_disparity = disparity_px.clamp_min(min_disparity_px)
    depth = (fx * baseline) / safe_disparity
    return depth.clamp(min=0.0, max=max_depth_m)


def depth_m_to_disparity_px(
    depth_m: torch.Tensor,
    focal_length_px: torch.Tensor | float,
    baseline_m: torch.Tensor | float,
) -> torch.Tensor:
    """Create disparity supervision from metric depth and stereo calibration."""
    fx = torch.as_tensor(focal_length_px, dtype=depth_m.dtype, device=depth_m.device)
    baseline = torch.as_tensor(baseline_m, dtype=depth_m.dtype, device=depth_m.device)
    if torch.any(fx <= 0) or torch.any(baseline <= 0):
        raise ValueError("focal_length_px and baseline_m must be positive")
    while fx.ndim < depth_m.ndim:
        fx = fx.unsqueeze(-1)
    while baseline.ndim < depth_m.ndim:
        baseline = baseline.unsqueeze(-1)
    return (fx * baseline) / depth_m.clamp_min(1e-6)
