# LiveKit integration

`beauty-livekit` beautifies a LiveKit camera track before it is encoded. Remote participants and your local preview both see the beautified video.

```kotlin
implementation("io.livekit:livekit-android:<your version>")       // tested with 2.28.1
implementation("com.github.YOUR_GITHUB_USER.FaceBeauty:beauty-livekit:1.0.0")
```

`beauty-livekit` uses LiveKit as `compileOnly`, so your app's LiveKit version is the one in use.

## Publish a beautified camera track

```kotlin
class CallViewModel(app: Application) : AndroidViewModel(app) {
    val room = LiveKit.create(app)
    val beauty = BeautyVideoProcessor(app, room.lkObjects.eglBase)
    private var track: LocalVideoTrack? = null

    suspend fun join(url: String, token: String) {
        room.connect(url, token)
        beauty.setParams(BeautyParams.natural())

        val options = LocalVideoTrackOptions(
            position = CameraPosition.FRONT,
            captureParams = VideoPreset169.H720.capture,    // H540 on low-end phones
        )
        val camera = room.localParticipant.createVideoTrack(
            name = "camera",
            capturer = BeautyCameraCapturer.create(getApplication(), true),
            options = options,
            videoProcessor = beauty,
        )
        camera.startCapture()
        room.localParticipant.publishVideoTrack(camera)
        track = camera
    }

    fun flipCamera(front: Boolean) {
        track?.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK)
    }

    override fun onCleared() {
        track?.let { it.stopCapture(); it.dispose() }
        beauty.dispose()            // after the track is stopped
        room.disconnect()
        room.release()
    }
}
```

Show the local preview with LiveKit's renderer as usual. It receives the processed frames:

```kotlin
room.initVideoRenderer(renderer)       // SurfaceViewRenderer / TextureViewRenderer
renderer.setMirror(true)               // selfie-style mirror, front camera only
track.addRenderer(renderer)
```

### Do not use `setCameraEnabled(true)`

`localParticipant.setCameraEnabled(true)` creates LiveKit's own camera track with no processor. Create and publish your track as shown above. To mute and unmute, use `track.stopCapture()` / `track.startCapture()`, or unpublish the track.

## `BeautyVideoProcessor`

| Member | |
|---|---|
| `BeautyVideoProcessor(context, eglBase[, config])` | `eglBase` must be `room.lkObjects.eglBase` (shared GL context) |
| `setParams(BeautyParams)` | Thread-safe; next frame |
| `isEnabled` / `setEnabled(false)` | Pass-through. Ideal for a hold-to-compare button |
| `getFps()`, `getInputFps()`, `getFrameMs()`, `getDetectionMs()`, `getQueuedFrames()`, `hasFace()` | Live stats |
| `getTrackerError()` | Last face-model error. Some phones hide logs from release apps |
| `allowDropping` | `true` = skip frames WebRTC would drop anyway (saves power when unpublished) |
| `dispose()` | Release everything, after stopping the track |

How it works:
1. Camera frames arrive as GPU textures and never touch the CPU.
2. On its own GL thread, which shares LiveKit's context, each frame is copied into a short queue (`SyncedBeautyRenderer`).
3. A copy is handed to the face model when the model is free.
4. Each frame is drawn once landmarks *for that frame* exist (exact, or interpolated between neighbouring detections). It goes straight into the surface LiveKit encodes and previews from, keeping the original timestamp for audio/video sync.

The cost is a short constant delay: 2–3 frames (~100–125 ms) on a Helio G85, capped by `BeautyConfig.maxSyncDelayMs`.

## `BeautyCameraCapturer` (recommended)

WebRTC's built-in camera capturers always choose the widest auto-exposure range (often 5–30 fps). In a dim room the camera stretches its exposure and the call drops to 10–15 fps. `BeautyCameraCapturer` is a Camera2 capturer that:
* keeps a frame-rate floor of 20–24 fps (`minFps`, default 20),
* meters exposure on faces (face-priority scene mode) when the camera supports it,
* emits frames exactly like WebRTC's own `Camera2Session` (sensor-oriented texture with rotation metadata, front camera un-mirrored), so LiveKit's `switchCamera`, simulcast and renderers work unchanged.

```kotlin
BeautyCameraCapturer.create(context, frontFacing = true)
BeautyCameraCapturer(context, cameraId = "1", minFps = 15)    // custom
```

Measured on the same phone in a dim room: the stock capturer gave 14 fps; `BeautyCameraCapturer` gave a steady 24 fps.

## Settings for calls

```kotlin
val config = BeautyConfig.forDevice(context).apply {
    maxFaces = 1              // calls are usually one face; faster tracking
    maxSyncDelayMs = 250      // max wait for landmarks; lower = less delay, may misalign on slow phones
}
val beauty = BeautyVideoProcessor(context, room.lkObjects.eglBase, config)
```

Use `VideoPreset169.H540` on `BeautyConfig.Tier.LOW` devices.

## Testing without a server

Track creation, capture and local rendering all work before `room.connect()`, so you can test the full pipeline offline. The sample's `LiveKitActivity` does exactly this:

```
adb shell am start -n com.facebeauty.sample/.LiveKitActivity --es preset glam
```
