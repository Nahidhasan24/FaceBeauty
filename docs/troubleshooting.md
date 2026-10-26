# Troubleshooting

### Effects work in debug, but no face is ever found in release

R8 removed something the face model loads by reflection. The library's consumer rules keep MediaPipe, protobuf and Flogger. If you merge or override consumer rules, make sure these survive:

```
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.common.flogger.** { *; }
```

The usual symptom is `ExceptionInInitializerError` in `Graph.<clinit>`. Check `BeautyVideoProcessor.getTrackerError()` / `FaceTracker.getError()`: some phones (vivo, for example) hide logcat output from non-debuggable apps.

### Low frame rate indoors (10–15 fps)

That's the camera, not the filter: it lengthens exposure in dim light. With LiveKit, use `BeautyCameraCapturer`. With CameraX, set a frame-rate range whose floor is 20–24 fps; see [Getting started](getting-started.md#frame-rate-in-dim-rooms). `getInputFps()` vs `getFps()` on `BeautyVideoProcessor` tells you which side is slow.

### Lipstick or makeup trails the face

Use the synced pipelines: `BeautyGLView`, `BeautyVideoProcessor` or `SyncedBeautyRenderer`. If you lowered `maxSyncDelayMs`, raise it again (default 250 ms). If `getQueuedFrames()` keeps hitting the cap, the phone is too slow for the current tier: use `BeautyConfig.Tier.LOW`.

### "face no" although a face is visible

* The model is still loading for the first ~0.5 s.
* Faces that are very small (far away), extremely dark or strongly turned may not be detected.
* Raise `detectionSize`, or improve the lighting.

### The face model is copied into RAM

Add `androidResources { noCompress += "task" }` to your app module. Compressed assets are still loaded (into a 3.6 MB direct buffer), but uncompressed ones are memory-mapped with zero copy.

### Lenses do nothing

Lenses need the iris landmarks of the 478-point model (the bundled `face_landmarker.task` has them). If you supply your own `FaceFrame` with 468 points, lenses are skipped.

### LiveKit: two camera tracks, or no beauty on the remote side

Don't call `setCameraEnabled(true)`. Create the track with `createVideoTrack(..., videoProcessor = beauty)` and publish that track. See [LiveKit integration](livekit.md).

### `BeautyGLView` stays black after the app returns from the background

If the GL context was lost, the camera must provide a new surface. CameraX does this automatically when the use case is re-bound.
