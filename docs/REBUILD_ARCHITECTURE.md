# Native AI + Dual Camera Architecture

Pipeline: camera capability probe -> synchronized capture when supported -> camera calibration -> exposure normalization -> stereo matching and motion estimation -> AF-VisionNet relative-depth inference -> confidence-aware fusion -> point cloud -> mesh -> OBJ/GLB.

## 12MP + 50MP policy
Megapixel labels do not guarantee simultaneous access. Android Camera2 capabilities, logical/physical camera IDs, supported stream combinations, frame timestamps and calibration must be verified on the actual phone. Use simultaneous fusion only after those checks pass; otherwise use sequential multi-view capture.

## Model ownership and validation
AF-VisionNet is initialized and trained from scratch in this repository. Training requires paired RGB/depth data and documented train/validation/test splits. Evaluate AbsRel, RMSE, delta thresholds, edge consistency and device latency before shipping weights.

## 3D accuracy
A single monocular relative-depth frame is only a 2.5D surface. Full metric reconstruction needs calibrated scale, camera poses, view alignment, outlier rejection and surface fusion.
