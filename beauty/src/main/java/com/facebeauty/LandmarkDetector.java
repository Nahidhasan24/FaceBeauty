package com.facebeauty;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.List;

/** Thin wrapper over MediaPipe FaceLandmarker returning flat coordinate arrays. */
final class LandmarkDetector implements Closeable {

    private final FaceLandmarker landmarker;
    private final boolean video;
    /** Kept referenced for the landmarker's lifetime. */
    private final ByteBuffer model;

    LandmarkDetector(Context context, BeautyConfig config, boolean video, int maxFaces) throws IOException {
        this.video = video;
        model = loadModel(context.getAssets(), config.modelAssetPath);
        BaseOptions base = BaseOptions.builder()
                .setModelAssetBuffer(model)
                .setDelegate(config.useGpuDelegate ? Delegate.GPU : Delegate.CPU)
                .build();
        // VIDEO mode tracks the face between frames and skips the face detector while tracking
        // holds, which roughly halves the per-frame cost compared to IMAGE mode.
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(video ? RunningMode.VIDEO : RunningMode.IMAGE)
                .setNumFaces(maxFaces)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .build();
        landmarker = FaceLandmarker.createFromOptions(context, options);
    }

    /**
     * Memory-maps the model straight from the APK when it is stored uncompressed (no copy, pages
     * shared and evictable); falls back to a direct buffer if the app build compressed it.
     */
    private static ByteBuffer loadModel(AssetManager assets, String path) throws IOException {
        try (AssetFileDescriptor fd = assets.openFd(path);
             FileInputStream in = fd.createInputStream();
             FileChannel channel = in.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        } catch (FileNotFoundException compressed) {
            try (InputStream in = assets.open(path)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 << 20);
                byte[] chunk = new byte[64 << 10];
                for (int n; (n = in.read(chunk)) > 0; ) bytes.write(chunk, 0, n);
                ByteBuffer buf = ByteBuffer.allocateDirect(bytes.size()).order(ByteOrder.nativeOrder());
                buf.put(bytes.toByteArray()).rewind();
                return buf;
            }
        }
    }

    /** @return per face: x0,y0,x1,y1,... normalized, origin top-left. */
    float[][] detect(Bitmap bitmap, long timestampMs) {
        // Deliberately not closed: MPImage.close() recycles the wrapped Bitmap, which the caller
        // reuses for the next frame (or owns, for still photos).
        MPImage image = new BitmapImageBuilder(bitmap).build();
        FaceLandmarkerResult result = video
                ? landmarker.detectForVideo(image, timestampMs)
                : landmarker.detect(image);
        List<List<NormalizedLandmark>> faces = result.faceLandmarks();
        float[][] out = new float[faces.size()][];
        for (int f = 0; f < out.length; f++) {
            List<NormalizedLandmark> lms = faces.get(f);
            float[] xy = new float[lms.size() * 2];
            for (int i = 0, n = lms.size(); i < n; i++) {
                NormalizedLandmark lm = lms.get(i);
                xy[2 * i] = lm.x();
                xy[2 * i + 1] = lm.y();
            }
            out[f] = xy;
        }
        return out;
    }

    @Override
    public void close() {
        landmarker.close();
    }
}
