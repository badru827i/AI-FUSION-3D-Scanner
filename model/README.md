# AF-VisionNet — Native AI-FUSION Vision

Original project model family. No third-party pretrained checkpoint is used.

Input: normalized RGB tensor. Initial output target: relative-depth map. Later heads may add confidence and semantic features.

Weights are generated only by the project training pipeline and installed after evaluation. No trained checkpoint is included yet; random initialization is not a functional depth model.

Metric depth requires calibrated depth ground truth. Monocular relative depth alone cannot guarantee real-world distance.
