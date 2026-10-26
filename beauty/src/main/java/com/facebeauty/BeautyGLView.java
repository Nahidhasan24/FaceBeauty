package com.facebeauty;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Drop-in live preview. Ask it for a {@link SurfaceTexture}, point any camera (CameraX, Camera2,
 * a video decoder) at it, and the beautified image is drawn into this view.
 *
 * <pre>
 * view.requestInputSurface(1280, 720, st -&gt; {
 *     Surface s = new Surface(st);
 *     request.provideSurface(s, executor, r -&gt; { s.release(); view.releaseInputSurface(st); });
 * });
 * </pre>
 *
 * Forward {@code onResume}/{@code onPause} from the host and call {@link #release()} when done.
 */
public class BeautyGLView extends GLSurfaceView {

    private static final String TAG = "BeautyGLView";

    public interface SurfaceTextureCallback {
        void onSurfaceTexture(SurfaceTexture surfaceTexture);
    }

    public interface CaptureCallback {
        void onCaptured(Bitmap bitmap);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private BeautyConfig config;
    private volatile BeautyParams params = BeautyParams.natural();
    private volatile boolean mirror;
    private volatile BeautyRenderer.ScaleType scaleType = BeautyRenderer.ScaleType.FILL;
    private volatile CaptureCallback pendingCapture;
    private volatile FaceTracker tracker;
    private volatile SyncedBeautyRenderer renderer;
    private volatile float fps;

    // GL thread.
    private SurfaceTexture input;
    private int inputTex;
    private int bufferW;
    private int bufferH;
    private final List<SurfaceTexture> retired = new ArrayList<>();
    private final List<Integer> retiredTex = new ArrayList<>();
    private final float[] texMatrix = new float[16];
    private final BeautyRenderer.Target screen = new BeautyRenderer.Target();
    private final BeautyRenderer.Target captureTarget = new BeautyRenderer.Target();
    /** Latest finished (beautified, landmark-synced) frame; re-shown until a newer one is ready. */
    private final Fbo display = new Fbo();
    private final BeautyRenderer.Target displayTarget = new BeautyRenderer.Target();
    private final SyncedBeautyRenderer.Output onFrame = ts -> {
        hasDisplay = true;
        countFps();
    };
    private boolean hasDisplay;
    private long lastFrameTs = Long.MIN_VALUE;
    private Fbo captureFbo;
    private int surfaceW;
    private int surfaceH;
    private int frames;
    private long fpsStartMs;

    public BeautyGLView(Context context) {
        this(context, null);
    }

    public BeautyGLView(Context context, AttributeSet attrs) {
        super(context, attrs);
        config = BeautyConfig.forDevice(context);
        setEGLContextClientVersion(GlUtil.supportsEs3(context) ? 3 : 2);
        setPreserveEGLContextOnPause(true);
        setRenderer(new GLRenderer());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** Override the auto-detected config. Only effective before the view is first shown. */
    public void setConfig(BeautyConfig config) {
        this.config = config;
    }

    public BeautyConfig getConfig() {
        return config;
    }

    public void setParams(BeautyParams p) {
        BeautyParams copy = p.copy();
        params = copy;
        SyncedBeautyRenderer r = renderer;
        if (r != null) r.setParams(copy);
        requestRender();
    }

    /** Horizontal flip of the output (front-camera frames from Camera2/CameraX are already mirrored). */
    public void setMirror(boolean mirror) {
        this.mirror = mirror;
    }

    public void setScaleType(BeautyRenderer.ScaleType type) {
        scaleType = type;
    }

    /** Rendered frames per second. */
    public float getFps() {
        return fps;
    }

    /** Duration of the last landmark-model run, ms. */
    public float getDetectionMs() {
        FaceTracker t = tracker;
        return t != null ? t.getDetectMs() : 0f;
    }

    /** Whether the tracker currently sees a face. */
    public boolean hasFace() {
        FaceTracker t = tracker;
        return t != null && t.getLatest().faces.length > 0;
    }

    /**
     * Creates a new input SurfaceTexture of the given buffer size (as the producer reports it,
     * e.g. CameraX {@code SurfaceRequest#getResolution()}); the callback runs on the main thread.
     * The previous input stays alive until {@link #releaseInputSurface} is called for it.
     */
    public void requestInputSurface(int width, int height, SurfaceTextureCallback callback) {
        queueEvent(() -> {
            int tex = GlUtil.createExternalTexture();
            SurfaceTexture st = new SurfaceTexture(tex);
            st.setDefaultBufferSize(width, height);
            st.setOnFrameAvailableListener(s -> requestRender());
            if (input != null) {
                retired.add(input);
                retiredTex.add(inputTex);
            }
            input = st;
            inputTex = tex;
            bufferW = width;
            bufferH = height;
            main.post(() -> callback.onSurfaceTexture(st));
        });
    }

    /** Call once the producer no longer writes to {@code st}. */
    public void releaseInputSurface(SurfaceTexture st) {
        queueEvent(() -> {
            int i = retired.indexOf(st);
            if (i >= 0) {
                st.release();
                GlUtil.deleteTexture(retiredTex.get(i));
                retired.remove(i);
                retiredTex.remove(i);
            } else if (st == input) {
                st.release();
                GlUtil.deleteTexture(inputTex);
                input = null;
                inputTex = 0;
            }
        });
    }

    /** Captures the next frame at camera resolution, exactly as previewed. Callback on main thread. */
    public void capture(CaptureCallback callback) {
        pendingCapture = callback;
        requestRender();
    }

    /** Frees GL objects and stops the tracker. The view is unusable afterwards. */
    public void release() {
        queueEvent(this::releaseGl);
        FaceTracker t = tracker;
        if (t != null) t.close();
        tracker = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        FaceTracker t = tracker;
        if (t != null) t.close();
        tracker = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Render into a smaller buffer on big screens; the display compositor upscales for free.
        int shortSide = Math.min(w, h);
        if (shortSide > config.maxRenderShortSide) {
            float s = config.maxRenderShortSide / (float) shortSide;
            getHolder().setFixedSize(Math.round(w * s), Math.round(h * s));
        } else {
            getHolder().setSizeFromLayout();
        }
    }

    private void releaseGl() {
        SyncedBeautyRenderer r = renderer;
        if (r != null) r.release();
        renderer = null;
        display.release();
        hasDisplay = false;
        if (captureFbo != null) captureFbo.release();
        captureFbo = null;
        for (int i = 0; i < retired.size(); i++) {
            retired.get(i).release();
            GlUtil.deleteTexture(retiredTex.get(i));
        }
        retired.clear();
        retiredTex.clear();
        if (input != null) {
            input.release();
            GlUtil.deleteTexture(inputTex);
            input = null;
        }
    }

    private final class GLRenderer implements GLSurfaceView.Renderer {

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig eglConfig) {
            if (tracker == null) tracker = new FaceTracker(getContext(), config);
            // Each camera frame waits (briefly) for its own landmarks, so effects never trail
            // the face; new landmarks wake the GL thread to show the frames they complete.
            SyncedBeautyRenderer r = new SyncedBeautyRenderer(config, tracker, config.maxSyncDelayMs,
                    BeautyGLView.this::requestRender);
            r.setParams(params);
            r.init();
            renderer = r;
            // A new context means the old external texture is gone; the producer must re-request.
            input = null;
            captureFbo = null;
            display.framebuffer = 0;
            hasDisplay = false;
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            surfaceW = width;
            surfaceH = height;
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            SyncedBeautyRenderer r = renderer;
            if (input == null || r == null) {
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                return;
            }
            try {
                input.updateTexImage();
            } catch (RuntimeException e) {
                Log.w(TAG, "updateTexImage failed", e);
                return;
            }
            input.getTransformMatrix(texMatrix);
            long ts = input.getTimestamp();
            // A 90/270 degree transform swaps the buffer's width and height on screen.
            boolean rotated = Math.abs(texMatrix[0]) < Math.abs(texMatrix[4]);
            int inW = rotated ? bufferH : bufferW;
            int inH = rotated ? bufferW : bufferH;

            display.ensure(inW, inH);
            displayTarget.framebuffer = display.framebuffer;
            displayTarget.width = inW;
            displayTarget.height = inH;
            // Woken by new landmarks rather than a new camera frame: don't queue a duplicate.
            if (ts != lastFrameTs) {
                lastFrameTs = ts;
                r.submit(inputTex, true, texMatrix, inW, inH, ts, displayTarget, onFrame);
            }
            r.drain(displayTarget, onFrame, false);

            screen.framebuffer = 0;
            screen.width = surfaceW;
            screen.height = surfaceH;
            screen.mirror = mirror;
            screen.flipY = false;
            screen.scaleType = scaleType;
            if (hasDisplay) {
                r.present(display.texture, inW, inH, screen);
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }

            CaptureCallback cb = pendingCapture;
            if (cb != null && hasDisplay) {
                pendingCapture = null;
                capture(r, cb, inW, inH);
            }
        }

        /** Saves the frame currently on screen, at camera resolution. */
        private void capture(SyncedBeautyRenderer r, CaptureCallback cb, int inW, int inH) {
            if (captureFbo == null) captureFbo = new Fbo();
            captureFbo.ensure(inW, inH);
            captureTarget.framebuffer = captureFbo.framebuffer;
            captureTarget.width = inW;
            captureTarget.height = inH;
            captureTarget.mirror = mirror;
            captureTarget.flipY = true;
            captureTarget.scaleType = BeautyRenderer.ScaleType.FILL;
            r.present(display.texture, inW, inH, captureTarget);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFbo.framebuffer);
            Bitmap bmp = GlUtil.readBitmap(inW, inH);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            main.post(() -> cb.onCaptured(bmp));
        }
    }

    private void countFps() {
        long now = SystemClock.uptimeMillis();
        if (fpsStartMs == 0) fpsStartMs = now;
        frames++;
        if (now - fpsStartMs >= 1000) {
            fps = frames * 1000f / (now - fpsStartMs);
            frames = 0;
            fpsStartMs = now;
        }
    }
}
