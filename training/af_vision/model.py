"""AF-Vision MAX v2: efficient multi-scale relative-depth network.

This is an architecture scaffold, not trained weights. It predicts relative
monocular depth and confidence; it does not infer metric scale or stereo depth.
"""
from __future__ import annotations

import math
import torch
from torch import nn
import torch.nn.functional as F


def _groups(channels: int) -> int:
    return math.gcd(8, channels)


class ConvNormAct(nn.Sequential):
    def __init__(self, cin: int, cout: int, stride: int = 1, kernel: int = 3):
        super().__init__(
            nn.Conv2d(cin, cout, kernel, stride=stride, padding=kernel // 2, bias=False),
            nn.GroupNorm(_groups(cout), cout),
            nn.SiLU(inplace=True),
        )


class ChannelGate(nn.Module):
    """Lightweight channel attention; Linear layers remain quantization candidates."""
    def __init__(self, channels: int, reduction: int = 4):
        super().__init__()
        hidden = max(8, channels // reduction)
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.fc1 = nn.Linear(channels, hidden)
        self.fc2 = nn.Linear(hidden, channels)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        b, c, _, _ = x.shape
        scale = self.pool(x).reshape(b, c)
        scale = torch.sigmoid(self.fc2(F.silu(self.fc1(scale)))).reshape(b, c, 1, 1)
        return x * scale


class EfficientContextBlock(nn.Module):
    """Depthwise-separable residual context block with linear channel attention."""
    def __init__(self, channels: int, expansion: int = 2):
        super().__init__()
        self.spatial = nn.Sequential(
            nn.Conv2d(channels, channels, 5, padding=2, groups=channels, bias=False),
            nn.GroupNorm(_groups(channels), channels),
            nn.SiLU(inplace=True),
            nn.Conv2d(channels, channels, 1, bias=False),
            nn.GroupNorm(_groups(channels), channels),
        )
        self.channel_gate = ChannelGate(channels)
        hidden = channels * expansion
        self.mlp = nn.Sequential(
            ConvNormAct(channels, hidden, kernel=1),
            nn.Conv2d(hidden, channels, 1, bias=False),
            nn.GroupNorm(_groups(channels), channels),
        )
        self.act = nn.SiLU(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.act(x + self.channel_gate(self.spatial(x)))
        return self.act(x + self.mlp(x))


class AFVisionMAX(nn.Module):
    """RGB -> relative depth + per-pixel confidence.

    Input: float RGB tensor [B,3,H,W], normalized to [0,1].
    Outputs: relative depth [B,1,H,W] and confidence [B,1,H,W].
    The multi-scale decoder preserves finer edges than the previous
    single-scale attention head while avoiding quadratic global attention.
    """
    def __init__(self, width: int = 48, blocks: int = 2):
        super().__init__()
        half = width // 2
        quarter = width
        deep = width * 2
        self.stem_half = nn.Sequential(
            ConvNormAct(3, half, stride=2),
            ConvNormAct(half, half),
        )
        self.stem_quarter = nn.Sequential(
            ConvNormAct(half, quarter, stride=2),
            ConvNormAct(quarter, quarter),
        )
        self.stem_eighth = nn.Sequential(
            ConvNormAct(quarter, deep, stride=2),
            *[EfficientContextBlock(deep) for _ in range(blocks)],
        )
        self.decode_quarter = nn.Sequential(
            ConvNormAct(deep + quarter, quarter),
            EfficientContextBlock(quarter),
        )
        self.decode_half = nn.Sequential(
            ConvNormAct(quarter + half, half),
            ConvNormAct(half, half),
        )
        self.depth_head = nn.Sequential(
            ConvNormAct(half, half),
            nn.Conv2d(half, 1, 1),
            nn.Softplus(),
        )
        self.confidence_head = nn.Sequential(
            ConvNormAct(half, half),
            nn.Conv2d(half, 1, 1),
            nn.Sigmoid(),
        )

    def forward(self, image: torch.Tensor):
        size = image.shape[-2:]
        half = self.stem_half(image)
        quarter = self.stem_quarter(half)
        deep = self.stem_eighth(quarter)
        up_quarter = F.interpolate(deep, size=quarter.shape[-2:], mode="bilinear", align_corners=False)
        decoded_quarter = self.decode_quarter(torch.cat((up_quarter, quarter), dim=1))
        up_half = F.interpolate(decoded_quarter, size=half.shape[-2:], mode="bilinear", align_corners=False)
        decoded_half = self.decode_half(torch.cat((up_half, half), dim=1))

        depth = F.interpolate(self.depth_head(decoded_half), size=size, mode="bilinear", align_corners=False)
        confidence = F.interpolate(self.confidence_head(decoded_half), size=size, mode="bilinear", align_corners=False)

        # Per-image normalization intentionally makes this relative, not metric, depth.
        flat = depth.flatten(1)
        lo = flat.amin(dim=1).view(-1, 1, 1, 1)
        hi = flat.amax(dim=1).view(-1, 1, 1, 1)
        depth = (depth - lo) / (hi - lo).clamp_min(1e-6)
        return depth, confidence


if __name__ == "__main__":
    model = AFVisionMAX().eval()
    sample = torch.rand(1, 3, 192, 256)
    with torch.no_grad():
        depth, confidence = model(sample)
    print("depth:", tuple(depth.shape), "confidence:", tuple(confidence.shape))
