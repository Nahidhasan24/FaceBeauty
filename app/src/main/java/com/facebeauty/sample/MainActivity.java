package com.facebeauty.sample;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.facebeauty.BeautyConfig;
import com.facebeauty.BeautyGLView;
import com.facebeauty.BeautyImageProcessor;
import com.facebeauty.BeautyPanelView;
import com.facebeauty.BeautyParams;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {

    private final BeautyParams params = BeautyParams.natural();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private BeautyGLView beautyView;
    private TextView stats;
    private View panel;
    private View photoOverlay;
    private ImageView photoView;
    private ProcessCameraProvider cameraProvider;
    private BeautyImageProcessor imageProcessor;
    private boolean frontCamera = true;
    private boolean cameraReleased;
    private Bitmap photoBefore;
    private Bitmap photoAfter;

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasCameraPermission()) startCamera();
                else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> pickPhoto = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) processPhoto(uri);
            });

    private final Runnable statsTicker = new Runnable() {
        @Override
        public void run() {
            String text = String.format(Locale.US, "%s%s  %.0f fps\nlandmarks %.0f ms  face %s",
                    beautyView.getConfig().tier, beautyView.getConfig().useGpuDelegate ? "/gpu" : "",
                    beautyView.getFps(), beautyView.getDetectionMs(), beautyView.hasFace() ? "yes" : "no");
            stats.setText(text);
            Log.i("FaceBeautyStats", text.replace('\n', ' '));
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        beautyView = findViewById(R.id.beautyView);
        stats = findViewById(R.id.stats);
        panel = findViewById(R.id.panel);
        photoOverlay = findViewById(R.id.photoOverlay);
        photoView = findViewById(R.id.photoView);

        // Debug overrides: adb shell am start -n ... --es tier LOW --ez gpu true
        String tier = getIntent().getStringExtra("tier");
        BeautyConfig config = tier != null
                ? BeautyConfig.forTier(BeautyConfig.Tier.valueOf(tier)) : beautyView.getConfig();
        config.useGpuDelegate = getIntent().getBooleanExtra("gpu", false);
        beautyView.setConfig(config);

        beautyView.setParams(params);
        beautyView.setOnClickListener(v -> panel.setVisibility(
                panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        BeautyPanelView beautyPanel = findViewById(R.id.beautyPanel);
        beautyPanel.setParams(params);
        beautyPanel.setListener(p -> {
            params.set(p);
            beautyView.setParams(params);
        });
        beautyPanel.setCompareListener(original -> beautyView.setParams(original ? BeautyParams.none() : params));

        buildButtons();
        buildPhotoButtons();

        if (hasCameraPermission()) {
            startCamera();
        } else {
            List<String> req = new ArrayList<>();
            req.add(Manifest.permission.CAMERA);
            if (Build.VERSION.SDK_INT < 29) req.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            permissions.launch(req.toArray(new String[0]));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        beautyView.onResume();
        main.post(statsTicker);
        // Coming back from the LiveKit screen, which released the camera.
        if (cameraReleased && cameraProvider != null) {
            cameraReleased = false;
            bindCamera();
        }
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(statsTicker);
        beautyView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        beautyView.release();
        if (imageProcessor != null) imageProcessor.close();
        io.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------ camera

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        BeautyConfig config = beautyView.getConfig();
        Executor mainExecutor = ContextCompat.getMainExecutor(this);
        ResolutionSelector selector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(
                        new Size(config.previewWidth, config.previewHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
        CameraSelector selectorCam = frontCamera
                ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        Preview.Builder builder = new Preview.Builder().setResolutionSelector(selector);
        Range<Integer> fps = pickFrameRate(cameraProvider.getCameraInfo(selectorCam).getSupportedFrameRateRanges());
        if (fps != null) builder.setTargetFrameRate(fps);
        Preview preview = builder.build();
        preview.setSurfaceProvider(mainExecutor, request -> {
            Size res = request.getResolution();
            beautyView.requestInputSurface(res.getWidth(), res.getHeight(), st -> {
                Surface surface = new Surface(st);
                request.provideSurface(surface, mainExecutor, result -> {
                    surface.release();
                    beautyView.releaseInputSurface(st);
                });
            });
        });
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, selectorCam, preview);
    }

    /**
     * Cheap front cameras default to a 5-30 fps auto range and crawl at 3-10 fps indoors. Prefer a
     * range whose floor is 20-24 fps: smooth preview, still enough exposure time in dim rooms.
     */
    private static Range<Integer> pickFrameRate(Set<Range<Integer>> ranges) {
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            int lo = r.getLower();
            if (lo < 20 || lo > 24 || r.getUpper() > 30) continue;
            if (best == null || r.getUpper() > best.getUpper()
                    || (r.getUpper().equals(best.getUpper()) && lo > best.getLower())) {
                best = r;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ UI

    @SuppressLint("ClickableViewAccessibility")
    private void buildButtons() {
        LinearLayout row = findViewById(R.id.buttons);
        addButton(row, "Flip", v -> {
            frontCamera = !frontCamera;
            if (cameraProvider != null) bindCamera();
        });
        addButton(row, "Snap", v -> beautyView.capture(bmp -> io.execute(() -> {
            String name = save(bmp);
            main.post(() -> Toast.makeText(this, name != null ? "Saved " + name : "Save failed",
                    Toast.LENGTH_SHORT).show());
        })));
        addButton(row, "LiveKit", v -> {
            // Release the CameraX session first; LiveKit opens the camera itself.
            if (cameraProvider != null) cameraProvider.unbindAll();
            cameraReleased = true;
            startActivity(new Intent(this, LiveKitActivity.class));
        });
        addButton(row, "Photo", v -> pickPhoto.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
    }


    private Button addButton(LinearLayout parent, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(4));
        parent.addView(b, lp);
        return b;
    }

    // ------------------------------------------------------------ photos

    @SuppressLint("ClickableViewAccessibility")
    private void buildPhotoButtons() {
        LinearLayout row = findViewById(R.id.photoButtons);
        Button compare = addButton(row, "Hold: before", null);
        compare.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) photoView.setImageBitmap(photoBefore);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) photoView.setImageBitmap(photoAfter);
            return false;
        });
        addButton(row, "Save", v -> {
            Bitmap bmp = photoAfter;
            if (bmp == null) return;
            io.execute(() -> {
                String name = save(bmp);
                main.post(() -> Toast.makeText(this, name != null ? "Saved " + name : "Save failed",
                        Toast.LENGTH_SHORT).show());
            });
        });
        addButton(row, "Close", v -> {
            photoOverlay.setVisibility(View.GONE);
            photoView.setImageBitmap(null);
            photoBefore = null;
            photoAfter = null;
        });
    }

    private void processPhoto(Uri uri) {
        Toast.makeText(this, "Processing…", Toast.LENGTH_SHORT).show();
        BeautyParams snapshot = params.copy();
        io.execute(() -> {
            try {
                Bitmap src = decode(uri, 2560);
                if (imageProcessor == null) imageProcessor = new BeautyImageProcessor(this);
                long t0 = System.nanoTime();
                Bitmap out = imageProcessor.process(src, snapshot);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                main.post(() -> {
                    photoBefore = src;
                    photoAfter = out;
                    photoView.setImageBitmap(out);
                    photoOverlay.setVisibility(View.VISIBLE);
                    Toast.makeText(this, src.getWidth() + "x" + src.getHeight() + " in " + ms + " ms",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private Bitmap decode(Uri uri, int maxSide) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int longSide = Math.max(w, h);
                if (longSide > maxSide) {
                    float s = maxSide / (float) longSide;
                    decoder.setTargetSize(Math.round(w * s), Math.round(h * s));
                }
            });
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, d);
        }
    }

    /** Saves to Pictures/FaceBeauty; returns the file name or null. */
    private String save(Bitmap bmp) {
        String name = "beauty_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FaceBeauty");
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) return null;
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
