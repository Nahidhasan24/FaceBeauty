# FaceBeauty

Real-time, on-device face beautification for Android: skin retouch, face reshape, makeup, eye effects and colour filters, similar to Snapchat or TikTok beauty. It is built for **low-end phones** and **live video**, with a drop-in [LiveKit](https://livekit.io) integration.

* **On-device.** Nothing leaves the phone. MediaPipe's 478-point face landmarks run on the CPU; every effect is a GPU shader.
* **Fast on budget hardware.** Measured on a Helio G85 / Mali-G52 phone: 24 fps camera-in and video-out, about 10–14 ms of GPU per frame with every effect on.
* **Frame-accurate.** Makeup, lipstick and reshaping are computed from the landmarks of the *same* frame, so they never slide off the face, even when the landmark model is slower than the camera.
* **Ready-made UI.** A TikTok-style effect selector (`BeautyPanelView`), or build your own from `BeautyCatalog`.

| Category | Effects |
|---|---|
| Filters | Natural, White, Golden, Silky, Glow, Fresh, Rosy, Peach, Milk, Bronze, Mono, and custom LUT images (PNG) |
| Skin | Smooth, Whiten, Skin tone (golden, peach, porcelain, bronze, rosy), Rosy, Clarity, Dark circles, Smile lines, Highlight, Neck match |
| Reshape | Big eyes, Slim face, Small face, V chin, Chin length ±, Cheekbones, Forehead ±, Slim nose, Nose length ±, Mouth ±, Eye distance ± |
| Eyes | Bright eyes, Coloured lenses, Lashes, Eyeliner, Eyeshadow, Dark circles |
| Makeup | Lipstick (with gloss), Blush, Eyeshadow, Eyeliner, Lashes, Teeth whitening |

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.Nahidhasan24.FaceBeauty:beauty:1.0.0")
    // Only for LiveKit video calls (your app provides io.livekit:livekit-android):
    implementation("com.github.Nahidhasan24.FaceBeauty:beauty-livekit:1.0.0")
}

android {
    // Lets the library memory-map the 3.6 MB face model instead of copying it into RAM.
    androidResources { noCompress += "task" }
}
```

Requirements: `minSdk 24`, OpenGL ES 2.0+, camera permission. The face model ships inside the AAR, and R8/ProGuard rules are applied automatically.

## Quick start: LiveKit video call

```kotlin
val beauty = BeautyVideoProcessor(context, room.lkObjects.eglBase)
beauty.setParams(BeautyParams.natural())

val track = room.localParticipant.createVideoTrack(
    name = "camera",
    capturer = BeautyCameraCapturer.create(context, /* front = */ true),
    options = LocalVideoTrackOptions(position = CameraPosition.FRONT, captureParams = VideoPreset169.H720.capture),
    videoProcessor = beauty,
)
track.startCapture()
room.localParticipant.publishVideoTrack(track)

// Effect selector bound to the call:
val panel = BeautyPanelView(context)
panel.setListener { params -> beauty.setParams(params) }
panel.setCompareListener { original -> beauty.isEnabled = !original }
```

## Quick start: camera preview

```xml
<com.facebeauty.BeautyGLView
    android:id="@+id/beautyView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
beautyView.setParams(BeautyParams.glow())
preview.setSurfaceProvider(mainExecutor) { request ->           // CameraX Preview use case
    val res = request.resolution
    beautyView.requestInputSurface(res.width, res.height) { st ->
        val surface = Surface(st)
        request.provideSurface(surface, mainExecutor) {
            surface.release()
            beautyView.releaseInputSurface(st)
        }
    }
}
```

## Documentation

| Guide | |
|---|---|
| [Getting started](docs/getting-started.md) | Install, camera preview, photos, lifecycle |
| [LiveKit integration](docs/livekit.md) | Beautify a published camera track, capturer, lifecycle, settings |
| [Effects reference](docs/effects.md) | Every `BeautyParams` field, presets, filters, custom LUTs |
| [Effect selector](docs/effect-selector.md) | `BeautyPanelView` and building your own UI with `BeautyCatalog` |
| [Performance and advanced use](docs/advanced.md) | Device tiers, frame sync, custom GL pipelines, tuning |
| [Troubleshooting](docs/troubleshooting.md) | Common problems and fixes |

A runnable sample is in [`app/`](app): a CameraX preview, the LiveKit pipeline without a server, photo editing, and a model benchmark.

## Modules

| Artifact | Contents |
|---|---|
| `beauty` | Core: `BeautyGLView`, `BeautyImageProcessor`, `BeautyRenderer`, `SyncedBeautyRenderer`, `FaceTracker`, `BeautyParams`, `ColorGrade`, `BeautyConfig`, `BeautyPanelView`, `BeautyCatalog` |
| `beauty-livekit` | `BeautyVideoProcessor` (LiveKit/WebRTC `VideoProcessor`), `BeautyCameraCapturer` |

## License

Apache License 2.0, see [LICENSE](LICENSE). Third-party components (the MediaPipe Face Landmarker model and canonical face mesh, both Apache 2.0) are listed in [NOTICE](NOTICE).
