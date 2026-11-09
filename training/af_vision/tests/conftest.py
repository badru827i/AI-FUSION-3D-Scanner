"""Make AF-Vision training modules importable when pytest runs from repo root."""
import sys
from pathlib import Path

AF_VISION_DIR = Path(__file__).resolve().parents[1]
if str(AF_VISION_DIR) not in sys.path:
    sys.path.insert(0, str(AF_VISION_DIR))
