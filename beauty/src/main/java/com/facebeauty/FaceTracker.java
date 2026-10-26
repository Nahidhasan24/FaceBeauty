package com.facebeauty;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the landmark model on its own thread. Frames are offered with {@link #submit}; while a
 * frame is in flight new ones are rejected, so the camera/render loop never waits on the model and
 * slow phones simply detect at a lower rate while rendering stays at full frame rate.
 */
public final class FaceTracker implements Closeable {

    private static final String TAG = "FaceTracker";
    private static final float MATCH_DISTANCE = 0.2f;

    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile LandmarkDetector detector;
    private volatile boolean closed;
    private volatile FaceFrame latest = FaceFrame.EMPTY;
    private volatile float detectMs;
    private volatile Throwable error;
    private volatile Listener listener;
    private volatile float minCutoff = LandmarkSmoother.DEFAULT_MIN_CUTOFF;
    private volatile float beta = LandmarkSmoother.DEFAULT_BETA;

    /** Called on the tracker thread for every processed frame, including frames with no face. */
    public interface Listener {
        void onFaces(FaceFrame frame);
    }

    // Tracker thread only.
    private final List<LandmarkSmoother> tracks = new ArrayList<>();
    private Bitmap bitmap;
    private long lastTsMs;

    public FaceTracker(Context context, BeautyConfig config) {
        Context app = context.getApplicationContext();
        thread = new HandlerThread("FaceTracker", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            try {
                detector = new LandmarkDetector(app, config, true, config.maxFaces);
            } catch (Throwable t) {
                error = t;
                Log.e(TAG, "Failed to load face model '" + config.modelAssetPath + "'", t);
            }
        });
    }

    public boolean isReady() {
        return detector != null && !closed;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * One-Euro jitter filter tuning. Lower {@code minCutoff} = steadier at rest but laggier;
     * higher {@code beta} = less lag while moving. Applies to faces that appear afterwards.
     */
    public void setSmoothing(float minCutoff, float beta) {
        this.minCutoff = minCutoff;
        this.beta = beta;
    }

    /** True when a new frame would be accepted. */
    public boolean isIdle() {
        return isReady() && !busy.get();
    }

    /**
     * Queues an RGBA frame (rows top-down). The buffer must stay untouched until
     * {@link #isIdle()} is true again.
     *
     * @return false if the tracker is busy or not ready; the frame was ignored.
     */
    public boolean submit(ByteBuffer rgba, int width, int height, long timeNs) {
        if (!isReady() || !busy.compareAndSet(false, true)) return false;
        handler.post(() -> process(rgba, width, height, timeNs));
        return true;
    }

    public FaceFrame getLatest() {
        return latest;
    }

    /** Duration of the last landmark-model run. */
    public float getDetectMs() {
        return detectMs;
    }

    /** Last model-loading or detection error, if any. */
    public Throwable getError() {
        return error;
    }

    private void process(ByteBuffer rgba, int width, int height, long timeNs) {
        FaceFrame result = null;
        try {
            LandmarkDetector det = detector;
            if (closed || det == null) return;
            if (bitmap == null || bitmap.isRecycled()
                    || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            rgba.rewind();
            bitmap.copyPixelsFromBuffer(rgba);

            long ts = Math.max(lastTsMs + 1, timeNs / 1_000_000L);
            lastTsMs = ts;
            long t0 = SystemClock.elapsedRealtimeNanos();
            float[][] raw = det.detect(bitmap, ts);
            detectMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f;
            float[][] smoothed = smooth(raw, timeNs);
            float[][] velocity = new float[smoothed.length][];
            for (int f = 0; f < smoothed.length; f++) velocity[f] = tracks.get(f).velocity();
            result = new FaceFrame(smoothed, velocity, timeNs);
            latest = result;
        } catch (Throwable t) {
            error = t;
            Log.w(TAG, "Detection failed", t);
        } finally {
            // Idle before notifying, so a listener can submit the next frame right away.
            busy.set(false);
        }
        Listener l = listener;
        if (l != null && result != null) l.onFaces(result);
    }

    /** Matches faces to existing tracks by nose position so each face keeps its own filter. */
    private float[][] smooth(float[][] raw, long timeNs) {
        List<LandmarkSmoother> next = new ArrayList<>(raw.length);
        float[][] out = new float[raw.length][];
        for (int f = 0; f < raw.length; f++) {
            LandmarkSmoother best = null;
            float bestDist = MATCH_DISTANCE;
            for (LandmarkSmoother s : tracks) {
                if (next.contains(s)) continue;
                float d = s.distanceTo(raw[f]);
                if (d < bestDist) {
                    bestDist = d;
                    best = s;
                }
            }
            if (best == null) best = new LandmarkSmoother(minCutoff, beta);
            next.add(best);
            out[f] = best.filter(raw[f], timeNs);
        }
        tracks.clear();
        tracks.addAll(next);
        return out;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        handler.post(() -> {
            if (detector != null) detector.close();
            detector = null;
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
            thread.quitSafely();
        });
    }
}
