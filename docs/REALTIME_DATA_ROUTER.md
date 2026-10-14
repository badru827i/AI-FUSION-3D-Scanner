# Realtime data router

## Why it exists

The scanner processes camera frames, motion quality, object tracking and relative-depth inference on modest Android devices. If each stage owns an unbounded queue, slow inference can make the UI work on old frames and increase perceived latency.

## Packet model

Every analyzed frame is routed as one RealtimeDataBlock containing:

- sequence number and creation timestamp;
- analysis-frame width and height;
- motion / tracking-quality snapshot from gyroscope and accelerometer;
- selected-object tracking result and confidence/miss count;
- optional relative-depth payload and its timestamp;
- inference duration and total measured processing duration.

The depth array is copied when it crosses the router boundary and should be treated as read-only. This gives each consumer a coherent packet rather than a mixture of unrelated frame timestamps.

## Latest-value backpressure

The router uses a single atomic slot, not a FIFO queue. Publishing replaces the last slot. This does not make neural inference itself faster; it prevents downstream consumers from accumulating stale packets. A consumer can ask for latest() and stats() at any time. replacedSnapshots counts slots replaced since startup, not failed captures.

CameraX also uses STRATEGY_KEEP_ONLY_LATEST and a queue depth of one. Together, these are bounded backpressure strategies suitable for low-memory phones.

## Target locking

Camera analysis frames are rotated upright according to CameraX's reported rotation. Touch points are mapped through the visible FILL_CENTER crop before they are passed to the tracker. The lock button supports both flows: arm lock then tap the target, or tap the target then press Lock Target. An existing lock can be released with Unlock Target.

## Exports

A successful scan creates OBJ, GLB, PLY and scan metadata in the app's local session folder. Save buttons invoke Android's document picker and copy the chosen file to the destination selected by the user.

## Honest reconstruction boundary

The current exported geometry still comes from the latest usable single-view relative-depth map. Gyroscope readings help describe motion but are not a complete six-degree-of-freedom camera pose. A physically correct fused multi-view reconstruction requires reliable poses and depth/image alignment, so this change does not claim that problem has been solved.
