"""AF-Vision MAX research model.

This is an untrained architecture scaffold. It predicts relative depth and
confidence from RGB input; it is not a metric-depth or stereo model yet.
"""
from __future__ import annotations

import torch
from torch import nn
import torch.nn.functional as F


class ConvNormAct(nn.Sequential):
    def __init__(self, cin: int, cout: int, stride: int = 1):
        super().__init__(
            nn.Conv2d(cin, cout, 3, stride=stride, padding=1, bias=False),
            nn.BatchNorm2d(cout),
            nn.SiLU(inplace=True),
        )


class AFVisionBlock(nn.Module):
    """Spatially local transformer block; Linear weights are INT4 candidates."""
    def __init__(self, dim: int, expansion: int = 2):
        super().__init__()
        self.norm1 = nn.LayerNorm(dim)
        self.qkv = nn.Linear(dim, dim * 3)
        self.proj = nn.Linear(dim, dim)
        self.norm2 = nn.LayerNorm(dim)
        self.mlp = nn.Sequential(
            nn.Linear(dim, dim * expansion),
            nn.GELU(),
            nn.Linear(dim * expansion, dim),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        b, c, h, w = x.shape
        tokens = x.flatten(2).transpose(1, 2)
        z = self.norm1(tokens)
        q, k, v = self.qkv(z).chunk(3, dim=-1)
        scale = q.shape[-1] ** -0.5
        attn = torch.softmax((q @ k.transpose(-2, -1)) * scale, dim=-1)
        tokens = tokens + self.proj(attn @ v)
        tokens = tokens + self.mlp(self.norm2(tokens))
        return tokens.transpose(1, 2).reshape(b, c, h, w)


class AFVisionMAX(nn.Module):
    """Compact RGB -> relative depth + confidence network.

    Input: float RGB tensor [B,3,H,W], normalized to [0,1].
    Outputs: relative depth [B,1,H,W] and confidence [B,1,H,W].
    """
    def __init__(self, width: int = 48, blocks: int = 2):
        super().__init__()
        self.stem = nn.Sequential(
            ConvNormAct(3, width // 2, 2),
            ConvNormAct(width // 2, width, 2),
            ConvNormAct(width, width, 2),  # keep attention token count bounded for mobile inference
        )
        self.context = nn.Sequential(*[AFVisionBlock(width) for _ in range(blocks)])
        self.depth_head = nn.Sequential(
            ConvNormAct(width, width // 2),
            nn.Conv2d(width // 2, 1, 1),
            nn.Softplus(),
        )
        self.confidence_head = nn.Sequential(
            ConvNormAct(width, width // 2),
            nn.Conv2d(width // 2, 1, 1),
            nn.Sigmoid(),
        )

    def forward(self, image: torch.Tensor):
        size = image.shape[-2:]
        features = self.context(self.stem(image))
        depth = F.interpolate(self.depth_head(features), size=size, mode="bilinear", align_corners=False)
        confidence = F.interpolate(self.confidence_head(features), size=size, mode="bilinear", align_corners=False)
        # Normalize per image: this is relative depth, not metric distance.
        flat = depth.flatten(1)
        lo = flat.amin(dim=1).view(-1, 1, 1, 1)
        hi = flat.amax(dim=1).view(-1, 1, 1, 1)
        depth = (depth - lo) / (hi - lo).clamp_min(1e-6)
        return depth, confidence


if __name__ == "__main__":
    model = AFVisionMAX().eval()
    sample = torch.rand(1, 3, 192, 256)
    d, c = model(sample)
    print("depth:", tuple(d.shape), "confidence:", tuple(c.shape))
