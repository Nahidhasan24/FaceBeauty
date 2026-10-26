# Getting started

## 1. Add the dependency

See [Install](../README.md#install-jitpack) in the README. Add `beauty`; add `beauty-livekit` only if you use LiveKit.

Request `android.permission.CAMERA` at runtime before starting the camera.

## 2. Pick your integration

| You are building | Use |
|---|---|
| A LiveKit video call | `BeautyVideoProcessor` + `BeautyCameraCapturer`, see [LiveKit integration](livekit.md) |
| A camera screen (selfie, preview, capture) | `BeautyGLView` (below) |
| Editing still photos | `BeautyImageProcessor` (below) |
| Your own OpenGL / encoder pipeline | `SyncedBeautyRenderer`, see [Advanced](advanced.md) |

All of them take the same `BeautyParams`, so a look built in one place works everywhere.

## 3. Camera preview with `BeautyGLView`

`BeautyGLView` is a `GLSurfaceView`. You give it any camera stream through a `SurfaceTexture`; it shows the beautified result.

```kotlin
class CameraActivity : ComponentActivity() {
    private lateinit var beautyView: BeautyGLView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        beautyView = findViewById(R.id.beautyView)
        beautyView.setParams(BeautyParams.natural())
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val executor = ContextCompat.getMainExecutor(this)
            val preview = Preview.Builder()
                .setTargetFrameRate(Range(24, 24))      // see "Frame rate in dim rooms" below
                .build()
            preview.setSurfaceProvider(executor) { request ->
                val res = request.resolution
                beautyView.requestInputSurface(res.width, res.height) { st ->
                    val surface = Surface(st)
                    request.provideSurface(surface, executor) {
                        surface.release()
                        beautyView.releaseInputSurface(st)
                    }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() { super.onResume(); beautyView.onResume() }
    override fun onPause() { beautyView.onPause(); super.onPause() }
    override fun onDestroy() { beautyView.release(); super.onDestroy() }
}
```

Useful calls:

| Method | |
|---|---|
| `setParams(BeautyParams)` | Change effects at any time, from any thread (the params are copied) |
| `capture { bitmap -> }` | Saves the frame on screen at camera resolution, exactly as previewed |
| `setMirror(Boolean)` | Extra horizontal flip. Camera2/CameraX already mirror the front camera |
| `setScaleType(FILL / FIT)` | Centre-crop (default) or letterbox |
| `setConfig(BeautyConfig)` | Override the automatic device tier. Call before the view is shown |
| `getFps()`, `getDetectionMs()`, `hasFace()` | Live stats |

The sample app locks the activity to portrait. The view reads the camera's transform matrix, so rotation and mirroring follow the camera automatically.

## 4. Add the effect selector

```kotlin
val panel = findViewById<BeautyPanelView>(R.id.beautyPanel)
panel.setParams(BeautyParams.natural())
panel.setListener { params -> beautyView.setParams(params) }
panel.setCompareListener { original ->
    beautyView.setParams(if (original) BeautyParams.none() else panel.params)
}
```

See [Effect selector](effect-selector.md) for customizing it or building your own.

## 5. Photos with `BeautyImageProcessor`

```kotlin
val processor = BeautyImageProcessor(context)          // reuse one instance
lifecycleScope.launch(Dispatchers.Default) {
    val result: Bitmap = processor.process(bitmap, BeautyParams.glam())
    withContext(Dispatchers.Main) { imageView.setImageBitmap(result) }
}
// when done: processor.close()
```

`process` blocks, so call it off the main thread. It runs the same GPU pipeline off-screen, detects faces in IMAGE mode, and returns a new bitmap; the input is not modified. Very large photos are scaled to the GPU's maximum texture size.

## 6. Choose a look

```kotlin
val p = BeautyParams.golden()      // natural(), smooth(), glow(), fresh(), rosyLook(), fair(), golden(), glam(), none()
p.lipstick = 0.5f
p.lipColor = 0xFFC62828.toInt()
p.eyeEnlarge = 0.3f
p.lens = 0.6f
p.lensColor = 0xFF4F86C6.toInt()
beautyView.setParams(p)
```

Every field is described in the [Effects reference](effects.md).

## Frame rate in dim rooms

Many front cameras default to a 5–30 fps auto-exposure range and drop to 10–15 fps indoors. Ask for a floor of 20–24 fps:
* CameraX: `Preview.Builder().setTargetFrameRate(...)`, choosing from `cameraInfo.supportedFrameRateRanges`. The sample's `pickFrameRate` shows how.
* LiveKit: use `BeautyCameraCapturer` (it does this for you).
