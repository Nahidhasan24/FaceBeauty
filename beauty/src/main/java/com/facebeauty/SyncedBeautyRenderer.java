package com.facebeauty;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Frame-accurate beauty rendering for video calls and recording.
 *
 * <p>The landmark model is slower than the camera on low-end phones (60-100 ms vs 33-40 ms per
 * frame). Drawing the newest frame with the newest landmarks makes lipstick and makeup trail the
 * face. Instead, each camera frame is copied into a small GPU queue and drawn only once landmarks
 * for its own moment exist: exact for frames the model saw, interpolated between the detections
 * before and after for the others. Effects stay locked to the face during fast head turns, at the
 * cost of a short constant delay (about one to two detection times).
 *
 * <p>GL thread only, except {@link #setParams}. Usage per camera frame: {@link #submit}, release
 * the camera frame, then {@link #drain}. Also call {@link #drain} when the tracker reports a
 * result ({@link FaceTracker#setListener}), since that is what makes queued frames ready.
 */
public final class SyncedBeautyRenderer {

    /** Receives each finished frame right after it is drawn into the target. */
    public interface Output {
        void onFrameRendered(long timestampNs);
    }

    private static final int MAX_RESULTS = 16;

    private final BeautyConfig config;
    private final FaceTracker tracker;
    private final BeautyRenderer renderer;
    private final long maxDelayNs;
    private final ConcurrentLinkedQueue<FaceFrame> incoming = new ConcurrentLinkedQueue<>();

    // GL thread.
    private final Fbo[] slots;
    private final long[] slotTs;
    private final long[] slotArrival;
    private final boolean[] slotFed;
    private int head;
    private int count;
    private int width;
    private int height;
    private final ArrayList<FaceFrame> results = new ArrayList<>();
    private final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /**
     * @param maxDelayMs longest a frame may wait for its landmarks before it is drawn with the
     *                   latest ones available (bounds the added latency if the model stalls)
     */
    public SyncedBeautyRenderer(BeautyConfig config, FaceTracker tracker, int maxDelayMs) {
        this(config, tracker, maxDelayMs, null);
    }

    /**
     * @param onLandmarks optional, called on the tracker thread whenever new landmarks arrive;
     *                    use it to schedule {@link #drain} on the GL thread
     */
    public SyncedBeautyRenderer(BeautyConfig config, FaceTracker tracker, int maxDelayMs, Runnable onLandmarks) {
        this.config = config;
        this.tracker = tracker;
        this.maxDelayNs = maxDelayMs * 1_000_000L;
        renderer = new BeautyRenderer(config, null);
        // Interpolation already smooths over time; keep the jitter filter light so it adds no lag.
        tracker.setSmoothing(3f, 40f);
        tracker.setListener(frame -> {
            incoming.add(frame);
            if (onLandmarks != null) onLandmarks.run();
        });
        // Enough slots for max delay at 30 fps plus headroom.
        int n = Math.max(3, (int) Math.ceil(maxDelayMs / 33.0) + 2);
        slots = new Fbo[n];
        slotTs = new long[n];
        slotArrival = new long[n];
        slotFed = new boolean[n];
        for (int i = 0; i < n; i++) slots[i] = new Fbo();
    }

    public void setParams(BeautyParams p) {
        renderer.setParams(p);
    }

    public void init() {
        renderer.init();
        for (Fbo f : slots) f.framebuffer = 0;
        head = count = 0;
        results.clear();
    }

    /** Draws a finished frame (e.g. the target texture of {@link #drain}) onto the screen. */
    void present(int texture, int w, int h, BeautyRenderer.Target out) {
        renderer.present(texture, w, h, out);
    }

    /** Frames waiting for landmarks. */
    public int getQueuedFrames() {
        return count;
    }

    /**
     * Copies a camera frame into the queue and hands a small copy to the landmark model when it
     * is idle. The source texture is not used after this returns.
     *
     * @param out used only if the queue is full and the oldest frame must be drawn early
     */
    public void submit(int texture, boolean oes, float[] texMatrix, int w, int h, long timestampNs,
                       BeautyRenderer.Target target, Output out) {
        if (w != width || h != height) {
            // Resolution change: flush what we have, then resize the slots.
            drain(target, out, true);
            width = w;
            height = h;
        }
        if (count == slots.length) drainOne(target, out, true);
        int slot = (head + count) % slots.length;
        slots[slot].ensure(w, h);
        renderer.copyFrame(texture, oes, texMatrix, slots[slot]);
        slotTs[slot] = timestampNs;
        slotArrival[slot] = SystemClock.elapsedRealtimeNanos();
        slotFed[slot] = false;
        count++;
        feedNewestIfIdle();
    }

    /** Gives the model the newest frame it has not seen, as soon as it is free. */
    private void feedNewestIfIdle() {
        if (count == 0 || !tracker.isIdle()) return;
        int slot = (head + count - 1) % slots.length;
        if (slotFed[slot]) return;
        slotFed[slot] = true;
        renderer.feedTracker(tracker, slots[slot].texture, false, identity, width, height, slotTs[slot]);
    }

    /**
     * Draws every queued frame whose landmarks are known (or that waited too long), oldest first.
     *
     * @param force draw everything now, e.g. before stopping
     */
    public void drain(BeautyRenderer.Target target, Output out, boolean force) {
        feedNewestIfIdle();
        while (count > 0 && drainOne(target, out, force)) {
            // keep going
        }
    }

    private boolean drainOne(BeautyRenderer.Target target, Output out, boolean force) {
        collectResults();
        int slot = head;
        long ts = slotTs[slot];
        FaceFrame before = null;
        FaceFrame after = null;
        for (FaceFrame r : results) {
            if (r.timestampNs <= ts) before = r;
            if (r.timestampNs >= ts && after == null) after = r;
        }
        boolean waitedTooLong = SystemClock.elapsedRealtimeNanos() - slotArrival[slot] > maxDelayNs;
        boolean modelDown = !tracker.isReady();
        if (after == null && !force && !waitedTooLong && !modelDown) return false;

        FaceFrame faces = after == null ? (before != null ? before : FaceFrame.EMPTY)
                : interpolate(before != null ? before : after, after, ts);
        renderer.setSyncedFaces(faces);
        renderer.render(slots[slot].texture, false, identity, width, height, target);
        out.onFrameRendered(ts);

        head = (head + 1) % slots.length;
        count--;
        pruneResults(count > 0 ? slotTs[head] : ts);
        return true;
    }

    private void collectResults() {
        FaceFrame f;
        while ((f = incoming.poll()) != null) {
            results.add(f);
            if (results.size() > MAX_RESULTS) results.remove(0);
        }
    }

    /** Keep the newest result at or before {@code oldestNeeded} and everything after it. */
    private void pruneResults(long oldestNeeded) {
        int keepFrom = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).timestampNs <= oldestNeeded) keepFrom = i;
        }
        if (keepFrom > 0) results.subList(0, keepFrom).clear();
    }

    /** Linear blend of two detections at time {@code ts}; nearest one if the faces differ. */
    private static FaceFrame interpolate(FaceFrame a, FaceFrame b, long ts) {
        if (a == b || a.timestampNs == b.timestampNs) return b;
        float t = (ts - a.timestampNs) / (float) (b.timestampNs - a.timestampNs);
        if (a.faces.length != b.faces.length) return t < 0.5f ? a : b;
        float[][] out = new float[b.faces.length][];
        for (int f = 0; f < out.length; f++) {
            float[] fa = a.faces[f], fb = b.faces[f];
            if (fa.length != fb.length) return t < 0.5f ? a : b;
            float[] o = new float[fb.length];
            for (int i = 0; i < o.length; i++) o[i] = fa[i] + (fb[i] - fa[i]) * t;
            out[f] = o;
        }
        return new FaceFrame(out, ts);
    }

    public void release() {
        tracker.setListener(null);
        renderer.release();
        for (Fbo f : slots) f.release();
        count = 0;
    }
}
