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

### Recorded video

For frames of a recorded clip, use `processVideoFrame` with increasing timestamps and one processor per clip. Faces are tracked between frames and jitter-filtered, so makeup and reshaping stay steady. Processing each frame as a photo would shimmer.

```kotlin
BeautyImageProcessor(context).use { processor ->
    frames.forEachIndexed { i, frame ->               // decoded in order, e.g. with MediaMetadataRetriever
        val out = processor.processVideoFrame(frame, params, timestampMs = i * 33L)
        encoder.write(out)
    }
}
```

The README demo was made exactly this way (see `DemoRenderActivity` in the sample).

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

## Java

The library is written in Java, so every API works the same from Java; `BeautyParams` fields are plain public fields. The sample's [`MainActivity.java`](../app/src/main/java/com/facebeauty/sample/MainActivity.java) is a complete Java camera screen.

```java
public class CameraActivity extends ComponentActivity {
    private final BeautyParams params = BeautyParams.natural();
    private BeautyGLView beautyView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        beautyView = findViewById(R.id.beautyView);
        beautyView.setParams(params);

        BeautyPanelView panel = findViewById(R.id.beautyPanel);
        panel.setParams(params);
        panel.setListener(p -> {
            params.set(p);
            beautyView.setParams(params);
        });
        panel.setCompareListener(original -> beautyView.setParams(original ? BeautyParams.none() : params));

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Executor main = ContextCompat.getMainExecutor(this);
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(main, request -> {
                    Size res = request.getResolution();
                    beautyView.requestInputSurface(res.getWidth(), res.getHeight(), st -> {
                        Surface surface = new Surface(st);
                        request.provideSurface(surface, main, result -> {
                            surface.release();
                            beautyView.releaseInputSurface(st);
                        });
                    });
                });
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview);
            } catch (Exception e) {
                Log.e("Camera", "start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    void snap() {
        beautyView.capture(bitmap -> { /* save bitmap */ });
    }

    @Override protected void onResume() { super.onResume(); beautyView.onResume(); }
    @Override protected void onPause() { beautyView.onPause(); super.onPause(); }
    @Override protected void onDestroy() { beautyView.release(); super.onDestroy(); }
}
```

Photos and looks from Java:

```java
BeautyImageProcessor processor = new BeautyImageProcessor(context);    // reuse; close() when done
executor.execute(() -> {
    BeautyParams p = BeautyParams.golden();
    p.lipstick = 0.5f;
    p.lipColor = 0xFFC62828;
    p.lens = 0.6f;
    p.lensColor = 0xFF4F86C6;
    Bitmap result = processor.process(bitmap, p);
    runOnUiThread(() -> imageView.setImageBitmap(result));
});
```

## Frame rate in dim rooms

Many front cameras default to a 5–30 fps auto-exposure range and drop to 10–15 fps indoors. Ask for a floor of 20–24 fps:
* CameraX: `Preview.Builder().setTargetFrameRate(...)`, choosing from `cameraInfo.supportedFrameRateRanges`. The sample's `pickFrameRate` shows how.
* LiveKit: use `BeautyCameraCapturer` (it does this for you).
