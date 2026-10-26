package com.facebeauty;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;

import java.io.Closeable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beautifies still photos off-screen, using the same GPU pipeline as the live preview.
 * {@link #process} blocks, so call it from a background thread. Reuse one instance: the model
 * and GL context are created once.
 */
public final class BeautyImageProcessor implements Closeable {

    /** Long side of the copy handed to the landmark model (accuracy does not improve beyond this). */
    private static final int DETECT_MAX = 1280;

    private final Context context;
    private final BeautyConfig config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "BeautyImage"));
    private EglCore egl;
    private BeautyRenderer renderer;
    private LandmarkDetector detector;
    private int maxTexture;

    public BeautyImageProcessor(Context context) {
        this(context, BeautyConfig.forPhotos());
    }

    public BeautyImageProcessor(Context context, BeautyConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    /** Returns a new beautified bitmap; {@code src} is not modified. */
    public Bitmap process(Bitmap src, BeautyParams params) {
        try {
            return worker.submit(() -> processOnWorker(src, params)).get();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private Bitmap processOnWorker(Bitmap src, BeautyParams params) throws Exception {
        if (egl == null) {
            egl = new EglCore(GlUtil.supportsEs3(context));
            maxTexture = GlUtil.maxTextureSize();
            renderer = new BeautyRenderer(config, null);
            renderer.init();
            detector = new LandmarkDetector(context, config, false, config.maxFaces);
        }

        Bitmap in = src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, false);
        Bitmap fit = scaleDown(in, maxTexture);
        if (fit != in && in != src) in.recycle();
        in = fit;
        int w = in.getWidth(), h = in.getHeight();

        Bitmap det = scaleDown(in, DETECT_MAX);
        float[][] faces = detector.detect(det, 0);
        if (det != in) det.recycle();

        renderer.setFaces(new FaceFrame(faces, 0L));
        renderer.setParams(params);
        int tex = GlUtil.createTexture(in);
        if (in != src) in.recycle();

        Fbo out = new Fbo();
        out.ensure(w, h);
        BeautyRenderer.Target target = new BeautyRenderer.Target();
        target.framebuffer = out.framebuffer;
        target.width = w;
        target.height = h;
        target.flipY = true;
        renderer.render(tex, false, GlUtil.FLIP_V, w, h, target);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.framebuffer);
        Bitmap result = GlUtil.readBitmap(w, h);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        out.release();
        GlUtil.deleteTexture(tex);
        return result;
    }

    private static Bitmap scaleDown(Bitmap b, int maxSide) {
        int longSide = Math.max(b.getWidth(), b.getHeight());
        if (longSide <= maxSide) return b;
        float s = maxSide / (float) longSide;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
    }

    @Override
    public void close() {
        worker.execute(() -> {
            if (renderer != null) renderer.release();
            if (detector != null) detector.close();
            if (egl != null) egl.release();
            renderer = null;
            detector = null;
            egl = null;
        });
        worker.shutdown();
    }
}
