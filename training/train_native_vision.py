"""Train AF-VisionNet from random initialization; no pretrained weights are loaded."""
from pathlib import Path
import argparse
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset

class AFVisionNet(nn.Module):
    def __init__(self, width=32):
        super().__init__()
        self.encoder = nn.Sequential(
            nn.Conv2d(3, width, 3, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(width, width, 3, stride=2, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(width, width * 2, 3, stride=2, padding=1), nn.ReLU(inplace=True),
        )
        self.decoder = nn.Sequential(
            nn.ConvTranspose2d(width * 2, width, 4, stride=2, padding=1), nn.ReLU(inplace=True),
            nn.ConvTranspose2d(width, width // 2, 4, stride=2, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(width // 2, 1, 3, padding=1), nn.Sigmoid(),
        )
    def forward(self, image): return self.decoder(self.encoder(image))

class PairedDepthDataset(Dataset):
    """Pairs: images/*.pt and depth/*.pt; tensors are CHW and 1HW."""
    def __init__(self, root):
        self.root = Path(root)
        self.images = sorted((self.root / "images").glob("*.pt"))
        if not self.images: raise RuntimeError("No images/*.pt training tensors found")
    def __len__(self): return len(self.images)
    def __getitem__(self, i):
        p = self.images[i]
        q = self.root / "depth" / p.name
        if not q.exists(): raise RuntimeError(f"Missing depth label: {q}")
        return (torch.load(p, map_location="cpu", weights_only=True).float(),
                torch.load(q, map_location="cpu", weights_only=True).float())

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--output", default="artifacts/af_visionnet.pt")
    a = ap.parse_args()
    device = "cuda" if torch.cuda.is_available() else "cpu"
    loader = DataLoader(PairedDepthDataset(a.dataset), batch_size=a.batch_size, shuffle=True)
    model = AFVisionNet().to(device)
    optimizer, loss_fn = torch.optim.AdamW(model.parameters(), lr=1e-3), nn.SmoothL1Loss()
    for epoch in range(a.epochs):
        model.train(); total = 0.0
        for image, target in loader:
            image, target = image.to(device), target.to(device)
            pred = model(image)
            if pred.shape != target.shape:
                target = nn.functional.interpolate(target, size=pred.shape[-2:], mode="bilinear", align_corners=False)
            loss = loss_fn(pred, target)
            optimizer.zero_grad(); loss.backward(); optimizer.step()
            total += loss.item()
        print(f"epoch={epoch+1} loss={total/max(1,len(loader)):.6f}")
    Path(a.output).parent.mkdir(parents=True, exist_ok=True)
    torch.save({"format":"af-visionnet-v1","state_dict":model.cpu().state_dict()}, a.output)

if __name__ == "__main__": main()
