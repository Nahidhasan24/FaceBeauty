# Java guide

FaceBeauty is written in Java, so every class works from Java with no Kotlin needed. The only Kotlin-first API you will touch is LiveKit's; `BeautyLiveKit` wraps it for Java.

Contents:
1. [Install](#1-install)
2. [Camera preview screen](#2-camera-preview-screen-beautyglview--camerax)
3. [Effect selector](#3-effect-selector-beautypanelview)
4. [Choosing effects in code](#4-choosing-effects-in-code-beautyparams)
5. [Photos and recorded video](#5-photos-and-recorded-video-beautyimageprocessor)
6. [LiveKit video call](#6-livekit-video-call)
7. [Custom selector UI](#7-custom-selector-ui-beautycatalog)
8. [Performance settings](#8-performance-settings-beautyconfig)

Complete, runnable Java screens in the sample app:
* [`MainActivity.java`](../app/src/main/java/com/facebeauty/sample/MainActivity.java): CameraX preview, effect panel, capture, photo editing
* [`LiveKitJavaActivity.java`](../app/src/main/java/com/facebeauty/sample/LiveKitJavaActivity.java): beautified LiveKit camera track, panel, connect and publish

## 1. Install

`settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

`app/build.gradle`:

```groovy
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17     // lambdas and method references
        targetCompatibility JavaVersion.VERSION_17
    }
    androidResources {
        noCompress 'task'                              // memory-map the face model
    }
}

dependencies {
    implementation 'com.github.Nahidhasan24.FaceBeauty:beauty:1.0.1'
    // Only for LiveKit calls (your app also adds io.livekit:livekit-android):
    implementation 'com.github.Nahidhasan24.FaceBeauty:beauty-livekit:1.0.1'
}
```

`AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

## 2. Camera preview screen (`BeautyGLView` + CameraX)

Layout `res/layout/activity_camera.xml`:

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.facebeauty.BeautyGLView
        android:id="@+id/beautyView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <com.facebeauty.BeautyPanelView
        android:id="@+id/beautyPanel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom" />
</FrameLayout>
```

Activity:

```java
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.facebeauty.BeautyGLView;
import com.facebeauty.BeautyPanelView;
import com.facebeauty.BeautyParams;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

public class CameraActivity extends ComponentActivity {

    private final BeautyParams params = BeautyParams.natural();
    private BeautyGLView beautyView;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else finish();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Executor main = ContextCompat.getMainExecutor(this);
                Preview preview = new Preview.Builder().build();
                // Hand the camera a SurfaceTexture owned by BeautyGLView.
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
                Log.e("Camera", "Failed to start camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** Saves the frame on screen at camera resolution. */
    private void takePhoto() {
        beautyView.capture(bitmap -> {
            // save with MediaStore, share, ...
        });
    }

    @Override protected void onResume() { super.onResume(); beautyView.onResume(); }
    @Override protected void onPause() { beautyView.onPause(); super.onPause(); }
    @Override protected void onDestroy() { beautyView.release(); super.onDestroy(); }
}
```

CameraX needs `androidx.camera:camera-camera2`, `camera-lifecycle` and `camera-core`. For a steady frame rate indoors, see [Frame rate in dim rooms](getting-started.md#frame-rate-in-dim-rooms).

## 3. Effect selector (`BeautyPanelView`)

```java
BeautyPanelView panel = findViewById(R.id.beautyPanel);   // or new BeautyPanelView(context)
panel.setParams(BeautyParams.natural());                  // initial state (copied)
panel.setDefaults(BeautyParams.natural());                // what the reset button restores
panel.setListener(params -> beautyView.setParams(params));
panel.setCompareListener(showOriginal ->
        beautyView.setParams(showOriginal ? BeautyParams.none() : panel.getParams()));
panel.selectCategory(BeautyCatalog.Category.MAKEUP);      // open a tab in code
```

See [Effect selector](effect-selector.md) for the details.

## 4. Choosing effects in code (`BeautyParams`)

Every effect is a public field; start from a preset and change what you need. All values are `0..1`; reshape fields marked ± are `-1..1`.

```java
BeautyParams p = BeautyParams.golden();   // natural(), smooth(), glow(), fresh(), rosyLook(), fair(), golden(), glam(), none()

// skin
p.smoothing = 0.6f;
p.whitening = 0.3f;
p.darkCircles = 0.5f;

// reshape
p.eyeEnlarge = 0.3f;
p.chinSlim = 0.3f;        // V chin
p.faceSlim = 0.3f;
p.noseLength = -0.2f;     // ± shorter

// makeup
p.lipstick = 0.5f;
p.lipColor = 0xFFC62828;  // ARGB int
p.blush = 0.3f;
p.eyeliner = 0.6f;

// eyes
p.lens = 0.6f;
p.lensColor = 0xFF4F86C6;
p.eyelashes = 0.5f;

// colour filter
p.grade = ColorGrade.golden();             // or a custom LUT: p.lut = BitmapFactory.decodeStream(...)
p.gradeStrength = 0.8f;

beautyView.setParams(p);                   // any thread; copied internally
```

Every field is described in the [Effects reference](effects.md).

## 5. Photos and recorded video (`BeautyImageProcessor`)

```java
private final ExecutorService worker = Executors.newSingleThreadExecutor();
private BeautyImageProcessor processor;

void beautifyPhoto(Bitmap photo) {
    if (processor == null) processor = new BeautyImageProcessor(this);   // reuse one instance
    worker.execute(() -> {
        Bitmap result = processor.process(photo, BeautyParams.glam());  // blocks; never on the main thread
        runOnUiThread(() -> imageView.setImageBitmap(result));
    });
}

@Override protected void onDestroy() {
    if (processor != null) processor.close();
    worker.shutdown();
    super.onDestroy();
}
```

Recorded video: use one processor per clip, frames in order, increasing timestamps:

```java
try (BeautyImageProcessor video = new BeautyImageProcessor(context)) {
    for (int i = 0; i < frames.size(); i++) {
        Bitmap out = video.processVideoFrame(frames.get(i), params, i * 33L);   // tracked, steady
        encoder.write(out);
    }
}
```

## 6. LiveKit video call

LiveKit's Android SDK is written in Kotlin; its `connect` and `publishVideoTrack` are `suspend` functions that Java cannot call directly. `BeautyLiveKit` provides them with callbacks on the main thread.

```java
import com.facebeauty.BeautyPanelView;
import com.facebeauty.BeautyParams;
import com.facebeauty.livekit.BeautyLiveKit;
import com.facebeauty.livekit.BeautyVideoProcessor;

import io.livekit.android.LiveKit;
import io.livekit.android.LiveKitOverrides;
import io.livekit.android.RoomOptions;
import io.livekit.android.room.Room;
import io.livekit.android.room.track.CameraPosition;
import io.livekit.android.room.track.LocalVideoTrack;
import io.livekit.android.room.track.VideoPreset169;
import livekit.org.webrtc.SurfaceViewRenderer;

public class CallActivity extends ComponentActivity {

    private Room room;
    private BeautyVideoProcessor beauty;
    private SurfaceViewRenderer renderer;
    private LocalVideoTrack track;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);         // a SurfaceViewRenderer + a BeautyPanelView

        room = LiveKit.INSTANCE.create(getApplicationContext(), new RoomOptions(), new LiveKitOverrides());

        // 1. The beauty processor shares LiveKit's GL context.
        beauty = new BeautyVideoProcessor(this, room.getLkObjects().getEglBase());
        beauty.setParams(BeautyParams.natural());

        // 2. Local preview (shows exactly what remote participants receive).
        renderer = findViewById(R.id.renderer);
        room.initVideoRenderer(renderer);
        renderer.setMirror(true);

        // 3. Beautified front camera (>= 20 fps in dim rooms); capture starts now.
        track = BeautyLiveKit.createCameraTrack(this, room, beauty, true, VideoPreset169.H720.getCapture());
        track.addRenderer(renderer);

        // 4. Effect selector drives the call.
        BeautyPanelView panel = findViewById(R.id.beautyPanel);
        panel.setListener(beauty::setParams);
        panel.setCompareListener(original -> beauty.setEnabled(!original));

        // 5. Join and publish.
        BeautyLiveKit.connect(room, SERVER_URL, token, new BeautyLiveKit.Callback<Object>() {
            @Override
            public void onSuccess(Object unused) {
                BeautyLiveKit.publish(room, track, new BeautyLiveKit.Callback<Boolean>() {
                    @Override public void onSuccess(Boolean published) { /* you are live */ }
                    @Override public void onError(Throwable error) { Log.e("Call", "publish failed", error); }
                });
            }

            @Override
            public void onError(Throwable error) {
                Log.e("Call", "connect failed", error);
            }
        });
    }

    void switchCamera(boolean front) {
        track.switchCamera(null, front ? CameraPosition.FRONT : CameraPosition.BACK);
        renderer.setMirror(front);
    }

    @Override
    protected void onDestroy() {
        track.removeRenderer(renderer);
        track.stopCapture();
        track.dispose();
        beauty.dispose();          // after the track is stopped
        renderer.release();
        room.disconnect();
        room.release();
        super.onDestroy();
    }
}
```

Useful `BeautyVideoProcessor` methods: `setParams`, `setEnabled`, `getFps`, `getInputFps`, `getQueuedFrames`, `hasFace`, `getTrackerError`, `dispose`. See [LiveKit integration](livekit.md) for how it works and the settings for calls.

Don't call `room.getLocalParticipant().setCameraEnabled(true)`: it creates a second, un-beautified camera track.

## 7. Custom selector UI (`BeautyCatalog`)

```java
for (BeautyCatalog.Item item : BeautyCatalog.items(BeautyCatalog.Category.RESHAPE)) {
    String label = item.label;          // "Big eyes", "V chin", ...
    String icon = item.icon;            // emoji; swap for your own artwork by item.id
    boolean twoWay = item.bipolar;      // slider -1..1 instead of 0..1
    float value = item.get(params);

    // on slider change:
    item.set(params, newValue);
    beautyView.setParams(params);

    // colour effects (lipstick, lenses, ...):
    if (item.hasColors()) {
        int[] palette = item.colors;
        item.setColor(params, palette[2]);
    }
}

// Filters: item.isLook(), item.previewColor() for the swatch, item.set(params, strength) to apply.
```

## 8. Performance settings (`BeautyConfig`)

```java
BeautyConfig config = BeautyConfig.forDevice(context);   // LOW / MEDIUM / HIGH chosen automatically
config.maxFaces = 1;              // faster tracking for one person
config.maxSyncDelayMs = 250;      // max time a frame waits for its own landmarks

beautyView.setConfig(config);                                                  // before the view is shown
BeautyVideoProcessor beauty = new BeautyVideoProcessor(context, eglBase, config);
BeautyImageProcessor photos = new BeautyImageProcessor(context, BeautyConfig.forPhotos());
```

See [Performance and advanced use](advanced.md).
