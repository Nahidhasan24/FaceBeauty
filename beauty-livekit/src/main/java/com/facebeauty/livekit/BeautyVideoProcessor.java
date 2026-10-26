package com.facebeauty.livekit;

import android.content.Context;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.facebeauty.BeautyConfig;
import com.facebeauty.BeautyParams;
import com.facebeauty.BeautyRenderer;
import com.facebeauty.FaceTracker;
import com.facebeauty.SyncedBeautyRenderer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import livekit.org.webrtc.EglBase;
import livekit.org.webrtc.RendererCommon;
import livekit.org.webrtc.SurfaceTextureHelper;
import livekit.org.webrtc.VideoFrame;
import livekit.org.webrtc.VideoProcessor;
import livekit.org.webrtc.VideoSink;

/**
 * LiveKit/WebRTC {@link VideoProcessor} that beautifies the camera track before it is encoded.
 *
 * <pre>
 * BeautyVideoProcessor beauty = new BeautyVideoProcessor(context, room.getLkObjects().getEglBase());
 * LocalVideoTrack track = room.getLocalParticipant().createVideoTrack("camera", options, beauty);
 * beauty.setParams(BeautyParams.natural());
 * </pre>
 *
 * How it stays fast and aligned: camera frames arrive as GPU textures and never touch the CPU.
 * On a dedicated GL thread (context shared with LiveKit's) each frame is copied into a short GPU
 * queue ({@link SyncedBeautyRenderer}) and drawn once landmarks for that exact moment are known,
 * straight into the output SurfaceTexture that LiveKit encodes and previews from, with the
 * original capture timestamp. Makeup and lipstick therefore stay locked to the face even though
 * the landmark model is slower than the camera; the price is a short constant delay
 * ({@link BeautyConfig#maxSyncDelayMs} at most). An unfiltered frame is never sent while enabled.
 */
public final class BeautyVideoProcessor implements VideoProcessor {

    private static final String TAG = "BeautyVideoProcessor";

    private final BeautyConfig config;
    private final FaceTracker tracker;
    private final HandlerThread glThread;
    private final Handler glHandler;
    private final SurfaceTextureHelper output;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private volatile VideoSink sink;
    private volatile BeautyParams params = BeautyParams.natural();
    private volatile boolean enabled = true;
    private volatile boolean disposed;
    private volatile float fps;
    private volatile float inputFps;
    private volatile float frameMs;
    private int inputFrames;
    private long inputStartMs;

    /**
     * If false (default), frames are processed even while the track is unpublished, so a local
     * preview works before connecting. Set true to skip frames WebRTC would drop anyway
     * (encoder frame-rate limits, not published) and save power.
     */
    public volatile boolean allowDropping;

    // GL thread only.
    private EglBase egl;
    private SyncedBeautyRenderer synced;
    private final BeautyRenderer.Target target = new BeautyRenderer.Target();
    private final SyncedBeautyRenderer.Output swap = this::onFrameRendered;
    private final Matrix rotation = new Matrix();
    private final Matrix texMatrix = new Matrix();
    private int outW;
    private int outH;
    private boolean warnedNonTexture;
    private int frames;
    private long fpsStartMs;

    public BeautyVideoProcessor(Context context, EglBase rootEglBase) {
        this(context, rootEglBase, BeautyConfig.forDevice(context));
    }

    public BeautyVideoProcessor(Context context, EglBase rootEglBase, BeautyConfig config) {
        this.config = config;
        tracker = new FaceTracker(context, config);
        output = SurfaceTextureHelper.create("BeautyOutput", rootEglBase.getEglBaseContext());
        glThread = new HandlerThread("BeautyGL", Process.THREAD_PRIORITY_DISPLAY);
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        EglBase.Context shared = rootEglBase.getEglBaseContext();
        glHandler.post(() -> egl = createEgl(shared));
    }

    /** Thread-safe; takes effect on the next frame. */
    public void setParams(BeautyParams p) {
        BeautyParams copy = p.copy();
        params = copy;
        SyncedBeautyRenderer r = synced;
        if (r != null) r.setParams(copy);
    }

    /** When disabled, camera frames pass through untouched (zero cost). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Frames per second actually processed and sent. */
    public float getFps() {
        return fps;
    }

    /** Frames per second arriving from the camera. */
    public float getInputFps() {
        return inputFps;
    }

    /** GL time per processed frame, ms. */
    public float getFrameMs() {
        return frameMs;
    }

    /** Duration of the last landmark-model run, ms. */
    public float getDetectionMs() {
        return tracker.getDetectMs();
    }

    public boolean hasFace() {
        return tracker.getLatest().faces.length > 0;
    }

    /** Last face-model error (loading or detection), or null. */
    public Throwable getTrackerError() {
        return tracker.getError();
    }

    public BeautyConfig getConfig() {
        return config;
    }

    // ------------------------------------------------------------ VideoProcessor

    @Override
    public void setSink(VideoSink sink) {
        this.sink = sink;
    }

    @Override
    public void onCapturerStarted(boolean started) {
        if (!started || disposed) return;
        output.stopListening();
        output.startListening(frame -> {
            VideoSink s = sink;
            if (s != null) s.onFrame(frame);
        });
    }

    @Override
    public void onCapturerStopped() {
        output.stopListening();
    }

    @Override
    public void onFrameCaptured(VideoFrame frame, FrameAdaptationParameters parameters) {
        if (allowDropping) {
            VideoProcessor.super.onFrameCaptured(frame, parameters);
            return;
        }
        // Same as LiveKit's NoDropVideoProcessor: apply crop/scale but ignore the drop flag.
        VideoFrame adapted = VideoProcessor.applyFrameAdaptationParameters(frame, parameters);
        if (adapted != null) {
            onFrameCaptured(adapted);
            adapted.release();
        } else {
            onFrameCaptured(frame);
        }
    }

    @Override
    public void onFrameCaptured(VideoFrame frame) {
        countInput();
        VideoSink s = sink;
        if (s == null || disposed) return;
        if (!(frame.getBuffer() instanceof VideoFrame.TextureBuffer)) {
            if (!warnedNonTexture) {
                warnedNonTexture = true;
                Log.w(TAG, "Non-texture frames are passed through unprocessed; use a texture capturer");
            }
            s.onFrame(frame);
            return;
        }
        // The GL thread has not copied the previous frame yet: drop this one. The camera can't
        // deliver a new frame until we release the current one anyway.
        if (!busy.compareAndSet(false, true)) return;
        frame.retain();
        if (!glHandler.post(() -> process(frame))) {
            frame.release();
            busy.set(false);
        }
    }

    // ------------------------------------------------------------ GL thread

    private static EglBase createEgl(EglBase.Context shared) {
        // GLES3 enables async (PBO) readback for the landmark model; fall back to GLES2.
        try {
            int[] es3 = EglBase.configBuilder().setOpenGlesVersion(3).setSupportsPixelBuffer(true)
                    .createConfigAttributes();
            return EglBase.create(shared, es3);
        } catch (RuntimeException e) {
            Log.i(TAG, "GLES3 shared context unavailable, using GLES2", e);
            int[] es2 = EglBase.configBuilder().setOpenGlesVersion(2).setSupportsPixelBuffer(true)
                    .createConfigAttributes();
            return EglBase.create(shared, es2);
        }
    }

    private void process(VideoFrame frame) {
        long t0 = SystemClock.elapsedRealtimeNanos();
        boolean released = false;
        try {
            if (disposed || egl == null) return;
            int w = frame.getRotatedWidth();
            int h = frame.getRotatedHeight();
            if (w != outW || h != outH || !egl.hasSurface()) {
                if (synced != null && egl.hasSurface()) {
                    egl.makeCurrent();
                    synced.drain(target, swap, true);
                }
                output.setTextureSize(w, h);
                if (egl.hasSurface()) egl.releaseSurface();
                egl.createSurface(output.getSurfaceTexture());
                outW = w;
                outH = h;
            }
            egl.makeCurrent();
            target.framebuffer = 0;
            target.width = w;
            target.height = h;
            if (synced == null) {
                synced = new SyncedBeautyRenderer(config, tracker, config.maxSyncDelayMs,
                        () -> glHandler.post(this::drainReady));
                synced.setParams(params);
                synced.init();
            }

            if (!enabled) {
                // Pass-through: flush queued (filtered) frames first so timestamps stay in order.
                synced.drain(target, swap, true);
                sink.onFrame(frame);
                return;
            }

            // Upright uv -> texture coords: undo the frame rotation, then the buffer's own
            // transform (same math as WebRTC's VideoFrameDrawer).
            VideoFrame.TextureBuffer buffer = (VideoFrame.TextureBuffer) frame.getBuffer();
            rotation.reset();
            rotation.preTranslate(0.5f, 0.5f);
            rotation.preRotate(frame.getRotation());
            rotation.preTranslate(-0.5f, -0.5f);
            texMatrix.set(buffer.getTransformMatrix());
            texMatrix.preConcat(rotation);
            float[] glMatrix = RendererCommon.convertMatrixFromAndroidGraphicsMatrix(texMatrix);

            synced.submit(buffer.getTextureId(), buffer.getType() == VideoFrame.TextureBuffer.Type.OES,
                    glMatrix, w, h, frame.getTimestampNs(), target, swap);
            // The camera reuses this buffer once released; make sure our copy has executed.
            GLES20.glFinish();
            frame.release();
            released = true;
            busy.set(false);

            synced.drain(target, swap, false);
            frameMs = frameMs * 0.9f + (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f * 0.1f;
        } catch (RuntimeException e) {
            Log.e(TAG, "Frame processing failed", e);
        } finally {
            if (!released) {
                frame.release();
                busy.set(false);
            }
        }
    }

    /** New landmarks arrived: draw any queued frames they unblock. */
    private void drainReady() {
        if (disposed || egl == null || synced == null || !egl.hasSurface()) return;
        try {
            egl.makeCurrent();
            synced.drain(target, swap, false);
        } catch (RuntimeException e) {
            Log.e(TAG, "Drain failed", e);
        }
    }

    private void onFrameRendered(long timestampNs) {
        egl.swapBuffers(timestampNs);
        long now = SystemClock.uptimeMillis();
        if (fpsStartMs == 0) fpsStartMs = now;
        frames++;
        if (now - fpsStartMs >= 1000) {
            fps = frames * 1000f / (now - fpsStartMs);
            frames = 0;
            fpsStartMs = now;
        }
    }

    /** Frames currently waiting for their landmarks. */
    public int getQueuedFrames() {
        SyncedBeautyRenderer s = synced;
        return s != null ? s.getQueuedFrames() : 0;
    }

    private void countInput() {
        long now = SystemClock.uptimeMillis();
        if (inputStartMs == 0) inputStartMs = now;
        inputFrames++;
        if (now - inputStartMs >= 1000) {
            inputFps = inputFrames * 1000f / (now - inputStartMs);
            inputFrames = 0;
            inputStartMs = now;
        }
    }

    /** Releases everything. Stop/dispose the video track first. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        CountDownLatch done = new CountDownLatch(1);
        glHandler.post(() -> {
            try {
                if (egl != null) {
                    if (egl.hasSurface()) egl.makeCurrent();
                    if (synced != null) synced.release();
                    egl.release();
                }
            } finally {
                synced = null;
                egl = null;
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        glThread.quitSafely();
        output.stopListening();
        output.dispose();
        tracker.close();
    }
}
