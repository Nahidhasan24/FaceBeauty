# Performance and advanced use

## How a frame is processed

1. **Copy** the camera texture into a small GPU queue (one blit).
2. **Detect.** When the face model is idle, a 320–512 px copy of the newest queued frame goes to MediaPipe Face Landmarker (478 points) on a background thread. In VIDEO mode it tracks between frames instead of re-detecting.
3. **Wait** until landmarks for a queued frame's own moment exist: exact for frames the model saw, linearly interpolated between the detections before and after for the rest. A frame never waits longer than `maxSyncDelayMs`.
4. **Render** that frame with its landmarks:
   * region masks (skin, lips, eyes, mouth) rasterized from landmark polygons;
   * makeup and retouch maps drawn on the 468-vertex face mesh;
   * 4 tiny low-resolution passes for the guided-filter statistics;
   * **one** full-resolution pass that does smoothing, tone, makeup, eyes, lips, teeth, colour look and the reshape mesh warp together.

The landmark model is the slowest part (60–100 ms on a Helio G85). Thanks to step 3 it limits only how often landmarks update, not the frame rate or the alignment.

## `BeautyConfig`: device tiers

`BeautyConfig.forDevice(context)` picks a tier from RAM, CPU clock and the media performance class:

| | LOW | MEDIUM | HIGH |
|---|---|---|---|
| Typical device | ≤ 2.5 GB RAM, ≤ 4 cores, or < 1.8 GHz | Helio G85 / Snapdragon 6xx class | Snapdragon 7/8xx, ≥ 2.4 GHz |
| `maxFaces` | 1 | 1 | 2 |
| `detectionSize` (px, long side) | 320 | 416 | 512 |
| `processDownscale` (statistics passes) | 4 | 3 | 2 |
| `warpCellPx` (reshape mesh) | 16 | 12 | 8 |
| `maxRenderShortSide` (`BeautyGLView` buffer) | 540 | 720 | 1080 |
| Suggested camera | 1280×720 | 1280×720 | 1920×1080 |

All fields are public, so you can tune them:

```kotlin
val config = BeautyConfig.forTier(BeautyConfig.Tier.MEDIUM).apply {
    maxSyncDelayMs = 200
    useGpuDelegate = false     // CPU is usually best: the GPU is busy with the effects and encoder
}
```

`BeautyConfig.forPhotos()` favours quality over speed for still images.

## Measured (vivo Y17s, Helio G85, Mali-G52 MC2, dim room)

| Pipeline | Camera / output fps | GPU per frame | Landmarks | Added delay |
|---|---|---|---|---|
| LiveKit, Natural | 24 / 24 | ~9 ms | 55–75 ms | 2–3 frames |
| LiveKit, Glam (all makeup + look) | 24 / 24 | ~12 ms | 55–75 ms | 2–3 frames |
| `BeautyGLView` preview | 22 | — | 60–90 ms | 2–3 frames |

## Custom pipelines

### `SyncedBeautyRenderer`: frame-accurate (recommended for video)

Use it for any GL pipeline, for example recording with `MediaCodec`, another RTC SDK, or a custom camera:

```kotlin
// GL thread, with your EGL context current:
val tracker = FaceTracker(context, config)
val synced = SyncedBeautyRenderer(config, tracker, config.maxSyncDelayMs) {
    glHandler.post { synced.drain(target, output, false) }   // new landmarks: draw what they unblock
}
synced.setParams(BeautyParams.natural())
synced.init()

val target = BeautyRenderer.Target().apply { framebuffer = 0; width = w; height = h }
val output = SyncedBeautyRenderer.Output { timestampNs -> eglSwapWithPresentationTime(timestampNs) }

// For each camera frame (OES texture + SurfaceTexture matrix, upright size w x h):
synced.submit(oesTex, true, stMatrix, w, h, timestampNs, target, output)
synced.drain(target, output, false)

// Shutdown: synced.drain(target, output, true); synced.release(); tracker.close()
```

The texture you pass to `submit` is copied immediately. If something else can overwrite it right after (for example a camera in another context), call `glFinish()` before letting it go. `BeautyVideoProcessor` does this.

### `BeautyRenderer`: lowest latency

`BeautyRenderer(config, tracker).render(...)` draws the newest frame with the newest landmarks and extrapolates face motion. It adds no delay, but effects can trail a fast-moving face on slow phones. Use `setFaces(FaceFrame)` to supply your own landmarks (still images, or another tracker).

### Your own landmarks

`FaceFrame` holds normalized MediaPipe landmarks (x, y, origin top-left of the upright image; 468 or 478 points per face). Lenses need the 478-point (iris) model. `FaceTracker` is usable on its own: `submit(rgbaBuffer, w, h, timeNs)`, `getLatest()`, `setListener {}`.

## Size

The library adds MediaPipe tasks-vision (native `.so`, about 7–10 MB per ABI) and the 3.6 MB model. Ship App Bundles, or ABI splits with `arm64-v8a` + `armeabi-v7a`, and add `androidResources { noCompress += "task" }`.
