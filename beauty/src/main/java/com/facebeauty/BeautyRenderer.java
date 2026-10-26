package com.facebeauty;

import android.graphics.Color;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GPU beauty pipeline. All methods except {@link #setParams} / {@link #setFaces} must run on the
 * thread that owns the GL context.
 *
 * <p>Per frame:
 * <ol>
 * <li>(when the tracker is idle) copy the frame into a small buffer for the landmark model,
 *     read back asynchronously through a PBO on GLES3;</li>
 * <li>rasterize feathered face-region masks from the landmarks (no blur passes needed);</li>
 * <li>low-res Gaussian mean + local deviation of the frame (4 tiny passes);</li>
 * <li>one full-res pass on a warped mesh that does smoothing, whitening, sharpening, lipstick,
 *     teeth/eyes and geometry together.</li>
 * </ol>
 */
public final class BeautyRenderer {

    public enum ScaleType { FILL, FIT }

    /** Where and how to draw the result. */
    public static final class Target {
        /** Framebuffer id, 0 for the window surface. */
        public int framebuffer;
        public int width;
        public int height;
        public boolean mirror;
        /** Draw upside down, so glReadPixels returns rows top-down. */
        public boolean flipY;
        public ScaleType scaleType = ScaleType.FILL;
    }

    private static final float FADE_IN_S = 0.12f;
    private static final float FADE_OUT_S = 0.3f;
    private static final long STALE_NS = 1_000_000_000L;
    private static final float MAX_PREDICT_S = 0.15f;
    private static final float PREDICT_GAIN = 0.9f;
    private static final int MAX_OPS = Shaders.MAX_OPS;
    private static final int MAX_FACES = Shaders.MAX_BODY;
    private static final int MAX_CMDS = 16 * MAX_FACES;
    private static final int MODE_ADD = 0;
    private static final int MODE_CUT = 1;

    private final BeautyConfig config;
    private final FaceTracker tracker;
    private volatile BeautyParams params = BeautyParams.natural();
    private volatile FaceFrame fixedFaces;
    /** Per-frame landmarks from {@link SyncedBeautyRenderer} (GL thread only). */
    private FaceFrame syncedFaces;

    private boolean glReady;
    private boolean es3;
    private final GlProgram[] copyProg = new GlProgram[2];
    private final GlProgram[] blurInProg = new GlProgram[2];
    private final GlProgram[] devProg = new GlProgram[2];
    private final GlProgram[] compProg = new GlProgram[2];
    private GlProgram blurProg;
    private GlProgram maskProg;
    private GlProgram makeupProg;
    private GlProgram presentProg;
    private final Fbo makeup = new Fbo();
    private int makeupTex;
    private int regionTex;
    private final Fbo regions = new Fbo();
    private boolean regionsDrawn;
    private final float[] iris = new float[MAX_FACES * 2 * 4];
    private int irisCount;
    private int meshIbo;
    private FloatBuffer meshVerts;
    private boolean makeupDrawn;
    private int lutTex;
    private Object lutKey;
    private final float[] lutP = new float[4];
    private final Fbo tmp = new Fbo();
    private final Fbo mean = new Fbo();
    private final Fbo dev = new Fbo();
    private final Fbo mask = new Fbo();
    private final Fbo detect = new Fbo();
    private FloatBuffer quad;
    private int gridVbo;
    private int gridIbo;
    private int gridIndexCount;
    private int gridInW;
    private int gridInH;

    // Detection readback.
    private ByteBuffer readBuf;
    private int pbo;
    private int pboSize;
    private boolean readPending;
    private int readW;
    private int readH;
    private long readTimeNs;

    // Face state.
    private float[][] faces = new float[0][];
    private float[][] predicted = new float[0][];
    private float presence;
    private long lastFrameNs;
    private float maxFaceWidthPx;

    // Per-frame uniforms.
    private final float[] opA = new float[MAX_OPS * 4];
    private final float[] opB = new float[MAX_OPS * 4];
    private int opCount;
    private final float[] bodyA = new float[MAX_FACES * 4];
    private final float[] bodyB = new float[MAX_FACES * 4];
    private int bodyCount;

    // Mask geometry: x, y, value per vertex, drawn as a list of commands.
    private float[] maskVerts = new float[4096];
    private int maskFloats;
    private final int[] cmdStart = new int[MAX_CMDS];
    private final int[] cmdCount = new int[MAX_CMDS];
    private final int[] cmdMode = new int[MAX_CMDS];
    private final float[] cmdColor = new float[MAX_CMDS * 4];
    private int cmdTotal;
    private FloatBuffer maskBuffer;
    private final float[] pts = new float[80];

    private final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /**
     * @param tracker live landmark source, or null when faces are supplied via {@link #setFaces}.
     */
    public BeautyRenderer(BeautyConfig config, FaceTracker tracker) {
        this.config = config;
        this.tracker = tracker;
    }

    /** Thread-safe; the params are copied. */
    public void setParams(BeautyParams p) {
        params = p.copy();
    }

    /** Use these landmarks instead of the tracker (still images). Pass null to go back. */
    public void setFaces(FaceFrame frame) {
        fixedFaces = frame;
    }

    /** Landmarks for the next {@link #render} call; enables fade but no prediction. */
    void setSyncedFaces(FaceFrame frame) {
        syncedFaces = frame;
    }

    /** Copies the input into {@code dst} upright (identity texture matrix afterwards). */
    void copyFrame(int texture, boolean oes, float[] texMatrix, Fbo dst) {
        int v = oes ? 1 : 0;
        int target = oes ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        dst.bind();
        GlProgram prog = program(copyProg, v, Shaders.QUAD_VS, Shaders.COPY_FS);
        prog.use();
        prog.setMat4("uTexMatrix", texMatrix);
        prog.set1f("uFlipY", 1f);
        bindTexture(0, target, texture);
        prog.set1i("uInput", 0);
        drawQuad();
        GLES20.glBindTexture(target, 0);
    }

    /** Feeds the landmark model from an arbitrary texture (used by the synced pipeline). */
    void feedTracker(FaceTracker t, int texture, boolean oes, float[] texMatrix, int inW, int inH, long timeNs) {
        int target = oes ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        feedTracker(t, texture, target, oes ? 1 : 0, texMatrix, inW, inH, timeNs, true);
        GLES20.glBindTexture(target, 0);
    }

    /** Creates GL objects. Call once the context is current (again after a context loss). */
    public void init() {
        glReady = false;
        es3 = GlUtil.isEs3Context();
        for (int i = 0; i < 2; i++) {
            copyProg[i] = blurInProg[i] = devProg[i] = compProg[i] = null;
        }
        blurProg = new GlProgram(Shaders.QUAD_VS, Shaders.FRAG_PRECISION + Shaders.BLUR_FS);
        maskProg = new GlProgram(Shaders.MASK_VS, Shaders.MASK_FS);
        makeupProg = new GlProgram(Shaders.MAKEUP_VS, Shaders.MAKEUP_FS);
        presentProg = new GlProgram(Shaders.PRESENT_VS, Shaders.PRESENT_FS);
        quad = GlUtil.floatBuffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
        tmp.framebuffer = mean.framebuffer = dev.framebuffer = mask.framebuffer = detect.framebuffer = 0;
        makeup.framebuffer = 0;
        regions.framebuffer = 0;
        makeupTex = regionTex = meshIbo = lutTex = 0;
        lutKey = null;
        gridVbo = gridIbo = 0;
        gridInW = gridInH = 0;
        pbo = 0;
        readPending = false;
        glReady = true;
    }

    /**
     * Renders one frame.
     *
     * @param texture   input texture (camera OES texture or a GL_TEXTURE_2D)
     * @param oes       true for GL_TEXTURE_EXTERNAL_OES
     * @param texMatrix maps upright uv to the texture (SurfaceTexture#getTransformMatrix)
     * @param inW       upright input width in px
     * @param inH       upright input height in px
     */
    public void render(int texture, boolean oes, float[] texMatrix, int inW, int inH, Target out) {
        if (!glReady || inW <= 0 || inH <= 0) return;
        BeautyParams p = params;
        int v = oes ? 1 : 0;
        int target = oes ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;
        long now = SystemClock.elapsedRealtimeNanos();

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        ensureBuffers(inW, inH);
        if (tracker != null && fixedFaces == null) {
            feedTracker(tracker, texture, target, v, texMatrix, inW, inH, now, false);
        }
        updateFaces(now);
        buildFaceGeometry(p, inW, inH);
        drawMask();
        makeupDrawn = drawMakeup(p, inW, inH);
        regionsDrawn = drawRegions(p, inW, inH);
        updateLut(p);

        float spread = maxFaceWidthPx > 0f
                ? clamp(0.03f * maxFaceWidthPx / config.processDownscale / 3.23f, 0.7f, 4f) : 1f;
        drawStatistics(texture, target, v, texMatrix, spread, p.smoothing > 0f);
        drawComposite(texture, target, v, texMatrix, inW, inH, p, out);

        GLES20.glBindTexture(target, 0);
        lastFrameNs = now;
    }

    // ---------------------------------------------------------------- setup

    private GlProgram program(GlProgram[] cache, int v, String vs, String fsBody) {
        if (cache[v] == null) cache[v] = new GlProgram(vs, Shaders.inputHeader(v == 1) + fsBody);
        return cache[v];
    }

    private void ensureBuffers(int inW, int inH) {
        int lw = Math.max(16, Math.round(inW / config.processDownscale));
        int lh = Math.max(16, Math.round(inH / config.processDownscale));
        tmp.ensure(lw, lh);
        mean.ensure(lw, lh);
        dev.ensure(lw, lh);
        // Masks never need to be coarser than half resolution; they are cheap geometry.
        float md = Math.min(config.processDownscale, 2f);
        mask.ensure(Math.max(16, Math.round(inW / md)), Math.max(16, Math.round(inH / md)));
        if (inW != gridInW || inH != gridInH) buildGrid(inW, inH);
    }

    private void buildGrid(int inW, int inH) {
        int cols = Math.max(16, Math.min(160, Math.round(inW / (float) config.warpCellPx)));
        int rows = Math.max(16, Math.min(160, Math.round(inH / (float) config.warpCellPx)));
        float[] verts = new float[(cols + 1) * (rows + 1) * 2];
        int k = 0;
        for (int y = 0; y <= rows; y++) {
            for (int x = 0; x <= cols; x++) {
                verts[k++] = x / (float) cols;
                verts[k++] = y / (float) rows;
            }
        }
        short[] idx = new short[cols * rows * 6];
        k = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int a = y * (cols + 1) + x;
                int b = a + cols + 1;
                idx[k++] = (short) a;
                idx[k++] = (short) (a + 1);
                idx[k++] = (short) b;
                idx[k++] = (short) b;
                idx[k++] = (short) (a + 1);
                idx[k++] = (short) (b + 1);
            }
        }
        if (gridVbo == 0) {
            int[] ids = new int[2];
            GLES20.glGenBuffers(2, ids, 0);
            gridVbo = ids[0];
            gridIbo = ids[1];
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.length * 4, GlUtil.floatBuffer(verts), GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, gridIbo);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.length * 2, GlUtil.shortBuffer(idx), GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        gridIndexCount = idx.length;
        gridInW = inW;
        gridInH = inH;
    }

    // ---------------------------------------------------------------- detection feed

    private void feedTracker(FaceTracker t, int texture, int target, int v, float[] texMatrix, int inW, int inH, long now,
                             boolean syncRead) {
        if (readPending) {
            // Finish last frame's async readback: by now the GPU is done, so mapping won't stall.
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo);
            Buffer mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, readW * readH * 4,
                    GLES30.GL_MAP_READ_BIT);
            boolean ok = mapped instanceof ByteBuffer;
            if (ok) {
                readBuf.clear();
                readBuf.put((ByteBuffer) mapped);
                readBuf.flip();
            }
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            readPending = false;
            if (ok) t.submit(readBuf, readW, readH, readTimeNs);
            return;
        }
        if (!t.isIdle()) return;

        int size = config.detectionSize;
        int dw, dh;
        if (inW >= inH) {
            dw = Math.min(size, inW);
            dh = Math.round(dw * inH / (float) inW);
        } else {
            dh = Math.min(size, inH);
            dw = Math.round(dh * inW / (float) inH);
        }
        dw = Math.max(2, dw & ~1);
        dh = Math.max(2, dh & ~1);
        detect.ensure(dw, dh);
        detect.bind();
        GlProgram prog = program(copyProg, v, Shaders.QUAD_VS, Shaders.COPY_FS);
        prog.use();
        prog.setMat4("uTexMatrix", texMatrix);
        prog.set1f("uFlipY", -1f);
        bindTexture(0, target, texture);
        prog.set1i("uInput", 0);
        drawQuad();

        int bytes = dw * dh * 4;
        if (readBuf == null || readBuf.capacity() < bytes) {
            readBuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        readBuf.clear();
        readBuf.limit(bytes);
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
        if (es3 && !syncRead) {
            if (pbo == 0) {
                int[] ids = new int[1];
                GLES30.glGenBuffers(1, ids, 0);
                pbo = ids[0];
                pboSize = 0;
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo);
            if (pboSize != bytes) {
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ);
                pboSize = bytes;
            }
            GLES30.glReadPixels(0, 0, dw, dh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            readPending = true;
            readW = dw;
            readH = dh;
            readTimeNs = now;
        } else {
            GLES20.glReadPixels(0, 0, dw, dh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf);
            t.submit(readBuf, dw, dh, now);
        }
    }

    private void updateFaces(long now) {
        FaceFrame fixed = fixedFaces;
        if (fixed != null) {
            faces = fixed.faces;
            presence = 1f;
            return;
        }
        float dt = lastFrameNs == 0 ? 0f : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        FaceFrame s = syncedFaces;
        if (s != null) {
            // Landmarks computed for exactly this frame: no prediction or staleness needed.
            if (s.faces.length > 0) {
                faces = s.faces;
                presence = Math.min(1f, presence + dt / FADE_IN_S);
            } else {
                presence = Math.max(0f, presence - dt / FADE_OUT_S);
                if (presence == 0f) faces = FaceFrame.EMPTY.faces;
            }
            return;
        }
        FaceFrame f = tracker != null ? tracker.getLatest() : FaceFrame.EMPTY;
        // Landmarks older than this no longer match the image; fade out rather than mis-draw.
        boolean fresh = now - f.timestampNs < STALE_NS;
        if (f.faces.length > 0 && fresh) {
            faces = predict(f, now);
            presence = Math.min(1f, presence + dt / FADE_IN_S);
        } else {
            // Keep the last landmarks while fading out so a dropped detection never pops.
            presence = Math.max(0f, presence - dt / FADE_OUT_S);
            if (presence == 0f) faces = FaceFrame.EMPTY.faces;
        }
    }

    /**
     * Landmarks arrive 50-150 ms after their frame on low-end phones. Extrapolating along the
     * smoothed velocity keeps masks and warps on a moving face instead of trailing behind it.
     */
    private float[][] predict(FaceFrame f, long now) {
        if (f.velocities == null) return f.faces;
        float ahead = Math.min(MAX_PREDICT_S, Math.max(0f, (now - f.timestampNs) / 1e9f)) * PREDICT_GAIN;
        if (predicted.length != f.faces.length) predicted = new float[f.faces.length][];
        for (int i = 0; i < f.faces.length; i++) {
            float[] src = f.faces[i];
            float[] vel = f.velocities[i];
            if (predicted[i] == null || predicted[i].length != src.length) predicted[i] = new float[src.length];
            float[] dst = predicted[i];
            for (int j = 0; j < src.length; j++) dst[j] = src[j] + vel[j] * ahead;
        }
        return predicted;
    }

    // ---------------------------------------------------------------- geometry

    private void buildFaceGeometry(BeautyParams p, int w, int h) {
        opCount = 0;
        bodyCount = 0;
        irisCount = 0;
        cmdTotal = 0;
        maskFloats = 0;
        maxFaceWidthPx = 0f;
        if (presence <= 0f) return;

        float eye = clamp01(p.eyeEnlarge) * presence;
        float slim = clamp01(p.faceSlim) * presence;
        float chin = clamp01(p.chinSlim) * presence;
        float nose = clamp01(p.noseSlim) * presence;

        for (float[] lm : faces) {
            if (bodyCount >= MAX_FACES) break;
            if (lm == null || lm.length < FaceRegions.MIN_LANDMARKS * 2) continue;

            // Pixel space, v up (same space the warp shader works in).
            float faceW = dist(lm, FaceRegions.RIGHT_CHEEK, FaceRegions.LEFT_CHEEK, w, h);
            float faceH = dist(lm, FaceRegions.FOREHEAD, FaceRegions.CHIN, w, h);
            if (faceW < 8f || faceH < 8f) continue;
            maxFaceWidthPx = Math.max(maxFaceWidthPx, faceW);
            float chinX = px(lm, FaceRegions.CHIN, w), chinY = py(lm, FaceRegions.CHIN, h);
            float upX = (px(lm, FaceRegions.FOREHEAD, w) - chinX) / faceH;
            float upY = (py(lm, FaceRegions.FOREHEAD, h) - chinY) / faceH;
            float axX = px(lm, FaceRegions.LEFT_EYE_OUTER, w) - px(lm, FaceRegions.RIGHT_EYE_OUTER, w);
            float axY = py(lm, FaceRegions.LEFT_EYE_OUTER, h) - py(lm, FaceRegions.RIGHT_EYE_OUTER, h);
            float axLen = (float) Math.hypot(axX, axY);
            if (axLen < 1e-3f) continue;
            axX /= axLen;
            axY /= axLen;

            // Neck/ears region: ellipse centred a bit below the face centre.
            int b = bodyCount * 4;
            float cx = (px(lm, FaceRegions.FOREHEAD, w) + chinX) * 0.5f - upX * 0.35f * faceH;
            float cy = (py(lm, FaceRegions.FOREHEAD, h) + chinY) * 0.5f - upY * 0.35f * faceH;
            bodyA[b] = cx;
            bodyA[b + 1] = cy;
            bodyA[b + 2] = axX;
            bodyA[b + 3] = axY;
            bodyB[b] = 1f / (0.9f * faceW);
            bodyB[b + 1] = 1f / (1.05f * faceH);
            bodyB[b + 2] = presence;
            bodyB[b + 3] = 0f;
            bodyCount++;

            if (eye > 0f) {
                addEyeOp(lm, FaceRegions.RIGHT_EYE, FaceRegions.RIGHT_EYE_OUTER, FaceRegions.RIGHT_EYE_INNER, eye, w, h);
                addEyeOp(lm, FaceRegions.LEFT_EYE, FaceRegions.LEFT_EYE_OUTER, FaceRegions.LEFT_EYE_INNER, eye, w, h);
            }
            if (chin > 0f) {
                // Shorter chin: lift the chin tip; narrower chin: pull its sides to the midline.
                addMoveOp(chinX, chinY, upX * chin * 0.07f * faceH, upY * chin * 0.07f * faceH, 0.38f * faceW);
                for (int idx : FaceRegions.CHIN_SIDES) {
                    addInwardOp(lm, idx, chinX, chinY, upX, upY, chin * 0.04f * faceW, 0.25f * faceW, w, h);
                }
            }
            if (slim > 0f) {
                for (int i = 0; i < FaceRegions.JAW_PAIRS.length; i++) {
                    float amount = slim * 0.055f * faceW * FaceRegions.JAW_WEIGHTS[i];
                    for (int idx : FaceRegions.JAW_PAIRS[i]) {
                        addInwardOp(lm, idx, chinX, chinY, upX, upY, amount, 0.35f * faceW, w, h);
                    }
                }
            }
            if (nose > 0f) {
                float rx = px(lm, FaceRegions.RIGHT_NOSE_WING, w), ry = py(lm, FaceRegions.RIGHT_NOSE_WING, h);
                float lx = px(lm, FaceRegions.LEFT_NOSE_WING, w), ly = py(lm, FaceRegions.LEFT_NOSE_WING, h);
                float noseW = (float) Math.hypot(lx - rx, ly - ry);
                float mx = (lx + rx) * 0.5f, my = (ly + ry) * 0.5f;
                float k = nose * 0.12f;
                addMoveOp(rx, ry, (mx - rx) * k, (my - ry) * k, noseW * 0.55f);
                addMoveOp(lx, ly, (mx - lx) * k, (my - ly) * k, noseW * 0.55f);
            }
            addReshapeOps(p, lm, faceW, faceH, chinX, chinY, upX, upY, axX, axY, w, h);
            addIris(lm, w, h);

            buildMasks(lm, upX, upY);
        }
    }

    /** Small face, cheekbones, chin length, forehead, nose length, mouth size, eye distance. */
    private void addReshapeOps(BeautyParams p, float[] lm, float faceW, float faceH, float chinX, float chinY,
                               float upX, float upY, float axX, float axY, int w, int h) {
        float pr = presence;
        float small = clamp01(p.faceSmall) * pr;
        if (small > 0f) {
            // Shrink everything inside a big soft circle around the face centre.
            float cx = (px(lm, FaceRegions.FOREHEAD, w) + chinX) * 0.5f;
            float cy = (py(lm, FaceRegions.FOREHEAD, h) + chinY) * 0.5f;
            addOp(cx, cy, faceH * 0.9f, -small * 0.12f, 0f, 0f);
        }
        float cheek = clamp01(p.cheekbone) * pr;
        if (cheek > 0f) {
            addInwardOp(lm, FaceRegions.RIGHT_CHEEK, chinX, chinY, upX, upY, cheek * 0.05f * faceW, 0.3f * faceW, w, h);
            addInwardOp(lm, FaceRegions.LEFT_CHEEK, chinX, chinY, upX, upY, cheek * 0.05f * faceW, 0.3f * faceW, w, h);
        }
        float chinLen = clamp(p.chinLength, -1f, 1f) * pr;
        if (chinLen != 0f) {
            // Longer = move the chin away from the forehead.
            float d = -chinLen * 0.07f * faceH;
            addMoveOp(chinX, chinY, upX * d, upY * d, 0.4f * faceW);
        }
        float fore = clamp(p.forehead, -1f, 1f) * pr;
        if (fore != 0f) {
            float d = fore * 0.07f * faceH;
            addMoveOp(px(lm, FaceRegions.FOREHEAD, w), py(lm, FaceRegions.FOREHEAD, h), upX * d, upY * d, 0.45f * faceW);
        }
        float noseLen = clamp(p.noseLength, -1f, 1f) * pr;
        if (noseLen != 0f) {
            float noseW = dist(lm, FaceRegions.RIGHT_NOSE_WING, FaceRegions.LEFT_NOSE_WING, w, h);
            float d = -noseLen * 0.25f * noseW;
            addMoveOp(px(lm, FaceRegions.NOSE_TIP, w), py(lm, FaceRegions.NOSE_TIP, h), upX * d, upY * d, noseW * 1.2f);
        }
        float mouth = clamp(p.mouthSize, -1f, 1f) * pr;
        if (mouth != 0f) {
            float mx = (px(lm, FaceRegions.UPPER_LIP, w) + px(lm, FaceRegions.LOWER_LIP, w)) * 0.5f;
            float my = (py(lm, FaceRegions.UPPER_LIP, h) + py(lm, FaceRegions.LOWER_LIP, h)) * 0.5f;
            float mouthW = dist(lm, FaceRegions.MOUTH_RIGHT, FaceRegions.MOUTH_LEFT, w, h);
            addOp(mx, my, mouthW * 0.9f, mouth * 0.18f, 0f, 0f);
        }
        float eyeDist = clamp(p.eyeDistance, -1f, 1f) * pr;
        if (eyeDist != 0f) {
            // The eye axis points from the right eye to the left eye; wider = each eye outward.
            float eyeW = dist(lm, FaceRegions.RIGHT_EYE_OUTER, FaceRegions.RIGHT_EYE_INNER, w, h);
            float d = eyeDist * 0.1f * eyeW;
            float rcx = centroidX(lm, FaceRegions.RIGHT_EYE, w), rcy = centroidY(lm, FaceRegions.RIGHT_EYE, h);
            float lcx = centroidX(lm, FaceRegions.LEFT_EYE, w), lcy = centroidY(lm, FaceRegions.LEFT_EYE, h);
            addMoveOp(rcx, rcy, -axX * d, -axY * d, eyeW * 1.1f);
            addMoveOp(lcx, lcy, axX * d, axY * d, eyeW * 1.1f);
        }
    }

    /** Iris circles (needs the 478-point model): centre and radius in pixel space. */
    private void addIris(float[] lm, int w, int h) {
        if (lm.length < FaceRegions.IRIS_LANDMARKS * 2 || irisCount >= MAX_FACES * 2) return;
        for (int c : FaceRegions.IRIS_CENTERS) {
            float cx = px(lm, c, w), cy = py(lm, c, h);
            float r = 0f;
            for (int k = 1; k <= 4; k++) r += (float) Math.hypot(px(lm, c + k, w) - cx, py(lm, c + k, h) - cy);
            // Fade the lens out as the eye closes (blinks, laughing) so no colour line is left
            // on the lid. Openness = lid gap / eye width of the eye this iris belongs to.
            float dr = Math.abs(cx - centroidX(lm, FaceRegions.RIGHT_EYE, w));
            float dl = Math.abs(cx - centroidX(lm, FaceRegions.LEFT_EYE, w));
            float open = dr < dl
                    ? dist(lm, FaceRegions.RIGHT_UPPER_LID, FaceRegions.RIGHT_LOWER_LID, w, h)
                    / Math.max(1f, dist(lm, FaceRegions.RIGHT_EYE_OUTER, FaceRegions.RIGHT_EYE_INNER, w, h))
                    : dist(lm, FaceRegions.LEFT_UPPER_LID, FaceRegions.LEFT_LOWER_LID, w, h)
                    / Math.max(1f, dist(lm, FaceRegions.LEFT_EYE_OUTER, FaceRegions.LEFT_EYE_INNER, w, h));
            float openFade = clamp01((open - 0.12f) / 0.1f);
            int i = irisCount * 4;
            iris[i] = cx;
            iris[i + 1] = cy;
            iris[i + 2] = r / 4f;
            iris[i + 3] = presence * openFade;
            irisCount++;
        }
    }

    private static float centroidX(float[] lm, int[] idx, int w) {
        float s = 0f;
        for (int i : idx) s += px(lm, i, w);
        return s / idx.length;
    }

    private static float centroidY(float[] lm, int[] idx, int h) {
        float s = 0f;
        for (int i : idx) s += py(lm, i, h);
        return s / idx.length;
    }

    private void addEyeOp(float[] lm, int[] contour, int outer, int inner, float amount, int w, int h) {
        float cx = 0f, cy = 0f;
        for (int i : contour) {
            cx += px(lm, i, w);
            cy += py(lm, i, h);
        }
        cx /= contour.length;
        cy /= contour.length;
        float eyeW = dist(lm, outer, inner, w, h);
        addOp(cx, cy, eyeW * 1.0f, amount * 0.2f, 0f, 0f);
    }

    /** Moves landmark {@code idx} toward the face's vertical midline (and slightly up). */
    private void addInwardOp(float[] lm, int idx, float ax, float ay, float upX, float upY,
                             float amount, float radius, int w, int h) {
        float x = px(lm, idx, w), y = py(lm, idx, h);
        float t = (x - ax) * upX + (y - ay) * upY;
        float dx = ax + upX * t - x;
        float dy = ay + upY * t - y;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) return;
        dx = dx / len + upX * 0.2f;
        dy = dy / len + upY * 0.2f;
        addMoveOp(x, y, dx * amount, dy * amount, radius);
    }

    private void addMoveOp(float x, float y, float dx, float dy, float radius) {
        // Keep the displacement well inside the bump so the mesh can never fold over.
        float len = (float) Math.hypot(dx, dy);
        float max = radius * 0.4f;
        if (len > max) {
            dx *= max / len;
            dy *= max / len;
        }
        if (len > 0.05f) addOp(x, y, radius, 0f, dx, dy);
    }

    private void addOp(float x, float y, float radius, float scale, float dx, float dy) {
        if (opCount >= MAX_OPS || radius < 1f) return;
        int i = opCount * 4;
        opA[i] = x;
        opA[i + 1] = y;
        opA[i + 2] = 1f / (radius * radius);
        opA[i + 3] = scale;
        opB[i] = dx;
        opB[i + 1] = dy;
        opB[i + 2] = 0f;
        opB[i + 3] = 0f;
        opCount++;
    }

    /** Builds the feathered region polygons for one face (mask space = upright uv). */
    private void buildMasks(float[] lm, float upX, float upY) {
        float pr = presence;

        // Skin: the face oval, with the forehead pushed up toward the hairline (the mesh stops
        // mid-forehead; without this smoothing/whitening ends in a visible line).
        int n = gather(lm, FaceRegions.FACE_OVAL, pts);
        float cx = 0f, cy = 0f;
        for (int i = 0; i < n; i++) {
            cx += pts[2 * i];
            cy += pts[2 * i + 1];
        }
        cx /= n;
        cy /= n;
        for (int i = 0; i < n; i++) {
            float dx = pts[2 * i] - cx, dy = pts[2 * i + 1] - cy;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-6f) continue;
            // up vector is in pixel space; in uv space only its direction matters here.
            float up = (dx * upX + dy * upY) / len;
            if (up > 0f) {
                float s = 1f + 0.22f * up;
                pts[2 * i] = cx + dx * s;
                pts[2 * i + 1] = cy + dy * s;
            }
        }
        beginCmd(MODE_ADD, pr, 0f, 0f, 0f);
        emitPolygon(pts, n, 0.97f, 0.1f);
        endCmd();

        // Holes in skin: eyes (tight, so no unwhitened ring shows around them), brows, lips.
        beginCmd(MODE_CUT, 1f, 0f, 0f, 0f);
        emitPolygon(pts, gather(lm, FaceRegions.RIGHT_EYE, pts), 1.1f, 0.15f);
        emitPolygon(pts, gather(lm, FaceRegions.LEFT_EYE, pts), 1.1f, 0.15f);
        emitPolygon(pts, gather(lm, FaceRegions.LIPS_OUTER, pts), 1.08f, 0.12f);
        emitStrip(lm, FaceRegions.RIGHT_BROW_UPPER, FaceRegions.RIGHT_BROW_LOWER);
        emitStrip(lm, FaceRegions.LEFT_BROW_UPPER, FaceRegions.LEFT_BROW_LOWER);
        endCmd();

        // Lips = outer contour minus the open mouth.
        beginCmd(MODE_ADD, 0f, pr, 0f, 0f);
        emitPolygon(pts, gather(lm, FaceRegions.LIPS_OUTER, pts), 1.0f, 0.1f);
        endCmd();
        beginCmd(MODE_CUT, 0f, 1f, 0f, 0f);
        emitPolygon(pts, gather(lm, FaceRegions.LIPS_INNER, pts), 1.0f, 0.12f);
        endCmd();

        // Eyes (B): brightening, plus the tone fill for the skin hole cut above.
        beginCmd(MODE_ADD, 0f, 0f, pr, 0f);
        emitPolygon(pts, gather(lm, FaceRegions.RIGHT_EYE, pts), 1.15f, 0.25f);
        emitPolygon(pts, gather(lm, FaceRegions.LEFT_EYE, pts), 1.15f, 0.25f);
        endCmd();
        beginCmd(MODE_ADD, 0f, 0f, 0f, pr);
        emitPolygon(pts, gather(lm, FaceRegions.LIPS_INNER, pts), 0.95f, 0.15f);
        endCmd();
    }

    private int gather(float[] lm, int[] idx, float[] out) {
        for (int i = 0; i < idx.length; i++) {
            out[2 * i] = lm[2 * idx[i]];
            out[2 * i + 1] = 1f - lm[2 * idx[i] + 1];
        }
        return idx.length;
    }

    private void beginCmd(int mode, float r, float g, float b, float a) {
        if (cmdTotal >= MAX_CMDS) return;
        cmdStart[cmdTotal] = maskFloats / 3;
        cmdMode[cmdTotal] = mode;
        int c = cmdTotal * 4;
        cmdColor[c] = r;
        cmdColor[c + 1] = g;
        cmdColor[c + 2] = b;
        cmdColor[c + 3] = a;
    }

    private void endCmd() {
        if (cmdTotal >= MAX_CMDS) return;
        cmdCount[cmdTotal] = maskFloats / 3 - cmdStart[cmdTotal];
        if (cmdCount[cmdTotal] > 0) cmdTotal++;
    }

    /**
     * Fan from the centroid (value 1) to an inner ring, then a feather band fading to 0. Works
     * for the star-shaped contours used here (oval, eyes, lips).
     */
    private void emitPolygon(float[] p, int n, float scale, float feather) {
        if (n < 3) return;
        float cx = 0f, cy = 0f;
        for (int i = 0; i < n; i++) {
            cx += p[2 * i];
            cy += p[2 * i + 1];
        }
        cx /= n;
        cy /= n;
        float si = Math.max(0f, scale - feather);
        float so = scale + feather;
        ensureMaskCapacity(n * 9 * 3);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = p[2 * i] - cx, ay = p[2 * i + 1] - cy;
            float bx = p[2 * j] - cx, by = p[2 * j + 1] - cy;
            float aix = cx + ax * si, aiy = cy + ay * si, aox = cx + ax * so, aoy = cy + ay * so;
            float bix = cx + bx * si, biy = cy + by * si, box = cx + bx * so, boy = cy + by * so;
            vert(cx, cy, 1f);
            vert(aix, aiy, 1f);
            vert(bix, biy, 1f);
            vert(aix, aiy, 1f);
            vert(aox, aoy, 0f);
            vert(bix, biy, 1f);
            vert(bix, biy, 1f);
            vert(aox, aoy, 0f);
            vert(box, boy, 0f);
        }
    }

    /** Solid band between two landmark rows (brows are curved, so no centroid fan). */
    private void emitStrip(float[] lm, int[] upper, int[] lower) {
        ensureMaskCapacity(upper.length * 6 * 3);
        for (int i = 0; i + 1 < upper.length; i++) {
            float ux0 = lm[2 * upper[i]], uy0 = 1f - lm[2 * upper[i] + 1];
            float ux1 = lm[2 * upper[i + 1]], uy1 = 1f - lm[2 * upper[i + 1] + 1];
            float lx0 = lm[2 * lower[i]], ly0 = 1f - lm[2 * lower[i] + 1];
            float lx1 = lm[2 * lower[i + 1]], ly1 = 1f - lm[2 * lower[i + 1] + 1];
            vert(ux0, uy0, 1f);
            vert(lx0, ly0, 1f);
            vert(ux1, uy1, 1f);
            vert(ux1, uy1, 1f);
            vert(lx0, ly0, 1f);
            vert(lx1, ly1, 1f);
        }
    }

    private void vert(float x, float y, float val) {
        maskVerts[maskFloats++] = x;
        maskVerts[maskFloats++] = y;
        maskVerts[maskFloats++] = val;
    }

    private void ensureMaskCapacity(int extra) {
        if (maskFloats + extra <= maskVerts.length) return;
        float[] bigger = new float[Math.max(maskVerts.length * 2, maskFloats + extra)];
        System.arraycopy(maskVerts, 0, bigger, 0, maskFloats);
        maskVerts = bigger;
    }

    // ---------------------------------------------------------------- passes

    private void drawMask() {
        mask.bind();
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (cmdTotal == 0) return;

        if (maskBuffer == null || maskBuffer.capacity() < maskFloats) {
            maskBuffer = GlUtil.floatBuffer(maskVerts.length);
        }
        maskBuffer.clear();
        maskBuffer.put(maskVerts, 0, maskFloats).position(0);

        maskProg.use();
        GLES20.glEnable(GLES20.GL_BLEND);
        maskBuffer.position(0);
        GLES20.glVertexAttribPointer(GlUtil.ATTR_POS, 2, GLES20.GL_FLOAT, false, 12, maskBuffer);
        GLES20.glEnableVertexAttribArray(GlUtil.ATTR_POS);
        maskBuffer.position(2);
        GLES20.glVertexAttribPointer(GlUtil.ATTR_VAL, 1, GLES20.GL_FLOAT, false, 12, maskBuffer);
        GLES20.glEnableVertexAttribArray(GlUtil.ATTR_VAL);
        int colorLoc = maskProg.loc("uColor");
        for (int i = 0; i < cmdTotal; i++) {
            if (cmdMode[i] == MODE_ADD) {
                // Union: dst = src + dst * (1 - src)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR);
            } else {
                // Cut: dst = dst * (1 - src)
                GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_COLOR);
            }
            int c = i * 4;
            GLES20.glUniform4f(colorLoc, cmdColor[c], cmdColor[c + 1], cmdColor[c + 2], cmdColor[c + 3]);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, cmdStart[i], cmdCount[i]);
        }
        GLES20.glDisableVertexAttribArray(GlUtil.ATTR_VAL);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Low-res Gaussian mean of the frame and (optionally) its local deviation. */
    private void drawStatistics(int texture, int target, int v, float[] m, float spread, boolean withDev) {
        float sx = spread / tmp.width;
        float sy = spread / tmp.height;
        // Step vectors mapped into input-texture space (handles camera rotation).
        float hx = m[0] * sx, hy = m[1] * sx;

        // Mean, horizontal (reads the full-res input directly: downsample + blur in one pass).
        tmp.bind();
        GlProgram prog = program(blurInProg, v, Shaders.QUAD_VS, Shaders.BLUR_INPUT_FS);
        prog.use();
        prog.setMat4("uTexMatrix", m);
        prog.set1f("uFlipY", 1f);
        prog.set2f("uStepIn", hx, hy);
        bindTexture(0, target, texture);
        prog.set1i("uInput", 0);
        drawQuad();

        // Mean, vertical.
        mean.bind();
        blur(tmp.texture, 0f, sy);

        if (!withDev) return;
        // Deviation, horizontal.
        tmp.bind();
        prog = program(devProg, v, Shaders.QUAD_VS, Shaders.DEV_FS);
        prog.use();
        prog.setMat4("uTexMatrix", m);
        prog.set1f("uFlipY", 1f);
        prog.set2f("uStepIn", hx, hy);
        prog.set2f("uStep", sx, 0f);
        bindTexture(0, target, texture);
        prog.set1i("uInput", 0);
        bindTexture(1, GLES20.GL_TEXTURE_2D, mean.texture);
        prog.set1i("uMean", 1);
        drawQuad();

        // Deviation, vertical.
        dev.bind();
        blur(tmp.texture, 0f, sy);
    }

    private void blur(int texture, float sx, float sy) {
        blurProg.use();
        blurProg.setMat4("uTexMatrix", identity);
        blurProg.set1f("uFlipY", 1f);
        blurProg.set2f("uStep", sx, sy);
        bindTexture(0, GLES20.GL_TEXTURE_2D, texture);
        blurProg.set1i("uTex", 0);
        drawQuad();
    }

    /** Output placement: centre-crop or letterbox, mirror, flip. */
    private static void setPlacement(GlProgram prog, int inW, int inH, Target out) {
        float inAspect = inW / (float) inH;
        float outAspect = out.width / (float) out.height;
        float scaleX = 1f, scaleY = 1f;
        if ((inAspect > outAspect) == (out.scaleType == ScaleType.FILL)) {
            scaleX = inAspect / outAspect;
        } else {
            scaleY = outAspect / inAspect;
        }
        if (out.mirror) scaleX = -scaleX;
        if (out.flipY) scaleY = -scaleY;
        prog.set4f("uPosXform", scaleX, scaleY, 0f, 0f);
    }

    /** Draws an already-finished upright frame (2D texture) into {@code out}. */
    void present(int texture, int w, int h, Target out) {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.framebuffer);
        GLES20.glViewport(0, 0, out.width, out.height);
        if (out.scaleType == ScaleType.FIT) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        presentProg.use();
        setPlacement(presentProg, w, h, out);
        bindTexture(0, GLES20.GL_TEXTURE_2D, texture);
        presentProg.set1i("uTex", 0);
        drawQuad();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void drawComposite(int texture, int target, int v, float[] m, int inW, int inH,
                               BeautyParams p, Target out) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, out.framebuffer);
        GLES20.glViewport(0, 0, out.width, out.height);
        if (out.scaleType == ScaleType.FIT) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        GlProgram prog = program(compProg, v, Shaders.COMPOSITE_VS, Shaders.COMPOSITE_FS);
        prog.use();

        setPlacement(prog, inW, inH, out);
        prog.set2f("uWarpSize", inW, inH);
        prog.set1i("uOpCount", opCount);
        prog.set4fv("uOpA[0]", opA, opCount);
        prog.set4fv("uOpB[0]", opB, opCount);

        bindTexture(0, target, texture);
        prog.set1i("uInput", 0);
        bindTexture(1, GLES20.GL_TEXTURE_2D, mean.texture);
        prog.set1i("uMean", 1);
        bindTexture(2, GLES20.GL_TEXTURE_2D, dev.texture);
        prog.set1i("uDev", 2);
        bindTexture(3, GLES20.GL_TEXTURE_2D, mask.texture);
        prog.set1i("uMask", 3);

        prog.setMat4("uTexMatrix", m);
        prog.set2f("uInStepX", m[0] / inW, m[1] / inW);
        prog.set2f("uInStepY", m[4] / inH, m[5] / inH);
        prog.set2f("uSize", inW, inH);

        float smooth = clamp01(p.smoothing);
        prog.set1f("uSmoothMix", Math.min(1f, smooth * 1.6f) * 0.95f);
        prog.set1f("uEps", 0.0004f + 0.006f * smooth * smooth);
        prog.set1f("uSharpen", clamp01(p.sharpen));
        float white = clamp01(p.whitening);
        float bm1 = 2f + 6f * white;
        prog.set1f("uWhiteMix", Math.min(1f, white * 1.5f));
        prog.set1f("uWhiteBm1", bm1);
        prog.set1f("uWhiteInvLog", (float) (1.0 / Math.log(1.0 + bm1)));
        prog.set1f("uRosy", clamp01(p.rosy));
        int lc = p.lipColor;
        prog.set4f("uLip", Color.red(lc) / 255f, Color.green(lc) / 255f, Color.blue(lc) / 255f,
                clamp01(p.lipstick));
        prog.set1f("uGloss", clamp01(p.lipGloss));
        prog.set1f("uTeeth", clamp01(p.teethWhiten));
        prog.set1f("uEyeBright", clamp01(p.eyeBrighten));
        prog.set1f("uBodySkin", clamp01(p.bodySkin));
        prog.set1i("uBodyCount", bodyCount);
        prog.set4fv("uBodyA[0]", bodyA, bodyCount);
        prog.set4fv("uBodyB[0]", bodyB, bodyCount);

        bindTexture(4, GLES20.GL_TEXTURE_2D, makeupDrawn ? makeup.texture : 0);
        prog.set1i("uMakeup", 4);
        setColor(prog, "uBlush", p.blushColor, makeupDrawn ? clamp01(p.blush) : 0f);
        setColor(prog, "uShadow", p.eyeshadowColor, makeupDrawn ? clamp01(p.eyeshadow) : 0f);
        setColor(prog, "uLiner", p.eyelinerColor, makeupDrawn ? clamp01(p.eyeliner) : 0f);
        setColor(prog, "uLash", p.eyelashColor, makeupDrawn ? clamp01(p.eyelashes) : 0f);
        bindTexture(6, GLES20.GL_TEXTURE_2D, regionsDrawn ? regions.texture : 0);
        prog.set1i("uRegions", 6);
        if (regionsDrawn) {
            prog.set4f("uRetouch", clamp01(p.darkCircles), clamp01(p.smileLines), clamp01(p.highlight),
                    clamp01(p.eyeBrighten));
        } else {
            prog.set4f("uRetouch", 0f, 0f, 0f, 0f);
        }
        setColor(prog, "uSkinTint", p.skinTint, clamp01(p.skinTintAmount));
        setColor(prog, "uLens", p.lensColor, regionsDrawn ? clamp01(p.lens) : 0f);
        prog.set1i("uIrisCount", irisCount);
        prog.set4fv("uIris[0]", iris, irisCount);
        bindTexture(5, GLES20.GL_TEXTURE_2D, lutTex);
        prog.set1i("uLut", 5);
        prog.set4f("uLutP", lutP[0], lutP[1], lutP[2], lutP[3]);
        prog.set1f("uGrade", lutKey != null ? clamp01(p.gradeStrength) : 0f);

        if (opCount > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo);
            GLES20.glVertexAttribPointer(GlUtil.ATTR_POS, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glEnableVertexAttribArray(GlUtil.ATTR_POS);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, gridIbo);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, gridIndexCount, GLES20.GL_UNSIGNED_SHORT, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        } else {
            drawQuad();
        }
        for (int i = 6; i >= 1; i--) bindTexture(i, GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private static void setColor(GlProgram prog, String name, int color, float amount) {
        prog.set4f(name, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, amount);
    }

    /**
     * Draws the canonical face mesh, textured with the UV-space makeup, into the makeup map
     * (input space, like the region masks, so it follows the warp). Back-facing triangles are
     * culled so the far cheek never paints over the nose when the head turns.
     */
    private boolean drawMakeup(BeautyParams p, int inW, int inH) {
        if (!p.hasMakeup() || presence <= 0f || faces.length == 0) return false;
        if (makeupTex == 0) makeupTex = GlUtil.createMipmappedTexture(MakeupTexture.create(), MakeupTexture.SIZE);
        // Liner and lashes need full resolution; low-tier devices use half.
        int div = config.tier == BeautyConfig.Tier.LOW ? 2 : 1;
        return drawMeshMap(makeupTex, makeup, Math.max(16, inW / div), Math.max(16, inH / div));
    }

    /** Retouch regions (under-eye, eye openings, highlight, folds) on the tracked mesh. */
    private boolean drawRegions(BeautyParams p, int inW, int inH) {
        if (!p.hasRegions() || presence <= 0f || faces.length == 0) return false;
        if (regionTex == 0) regionTex = GlUtil.createMipmappedTexture(RegionTexture.create(), RegionTexture.SIZE);
        // Eye openings clip the lenses: keep them sharp except on low-tier devices.
        int div = config.tier == BeautyConfig.Tier.LOW ? 2 : 1;
        return drawMeshMap(regionTex, regions, Math.max(16, inW / div), Math.max(16, inH / div));
    }

    private boolean drawMeshMap(int texture, Fbo fbo, int w, int h) {
        if (meshIbo == 0) {
            int[] ids = new int[1];
            GLES20.glGenBuffers(1, ids, 0);
            meshIbo = ids[0];
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshIbo);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, FaceMesh.TRIANGLES.length * 2,
                    GlUtil.shortBuffer(FaceMesh.TRIANGLES), GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            meshVerts = GlUtil.floatBuffer(FaceMesh.VERTEX_COUNT * 4);
        }
        fbo.ensure(w, h);
        fbo.bind();
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        makeupProg.use();
        bindTexture(0, GLES20.GL_TEXTURE_2D, texture);
        makeupProg.set1i("uTex", 0);
        makeupProg.set1f("uFade", presence);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshIbo);
        GLES20.glEnableVertexAttribArray(GlUtil.ATTR_POS);
        GLES20.glEnableVertexAttribArray(GlUtil.ATTR_VAL);
        boolean drawn = false;
        for (float[] lm : faces) {
            if (lm == null || lm.length < FaceMesh.VERTEX_COUNT * 2) continue;
            meshVerts.clear();
            for (int i = 0; i < FaceMesh.VERTEX_COUNT; i++) {
                meshVerts.put(lm[2 * i]).put(1f - lm[2 * i + 1])
                        .put(FaceMesh.UV[2 * i]).put(FaceMesh.UV[2 * i + 1]);
            }
            // Mesh triangles all wind clockwise-negative in UV space; whether the screen keeps or
            // flips that depends on the image (mirrored or not), so compare one big triangle.
            float screen = area(lm[2 * FaceRegions.FOREHEAD], 1f - lm[2 * FaceRegions.FOREHEAD + 1],
                    lm[2 * FaceRegions.RIGHT_CHEEK], 1f - lm[2 * FaceRegions.RIGHT_CHEEK + 1],
                    lm[2 * FaceRegions.LEFT_CHEEK], 1f - lm[2 * FaceRegions.LEFT_CHEEK + 1]);
            float uv = area(FaceMesh.UV[2 * FaceRegions.FOREHEAD], FaceMesh.UV[2 * FaceRegions.FOREHEAD + 1],
                    FaceMesh.UV[2 * FaceRegions.RIGHT_CHEEK], FaceMesh.UV[2 * FaceRegions.RIGHT_CHEEK + 1],
                    FaceMesh.UV[2 * FaceRegions.LEFT_CHEEK], FaceMesh.UV[2 * FaceRegions.LEFT_CHEEK + 1]);
            boolean frontIsCcw = (screen * uv) < 0f; // UV triangles are negative
            GLES20.glFrontFace(frontIsCcw ? GLES20.GL_CCW : GLES20.GL_CW);
            meshVerts.position(0);
            GLES20.glVertexAttribPointer(GlUtil.ATTR_POS, 2, GLES20.GL_FLOAT, false, 16, meshVerts);
            meshVerts.position(2);
            GLES20.glVertexAttribPointer(GlUtil.ATTR_VAL, 2, GLES20.GL_FLOAT, false, 16, meshVerts);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, FaceMesh.TRIANGLES.length, GLES20.GL_UNSIGNED_SHORT, 0);
            drawn = true;
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glDisableVertexAttribArray(GlUtil.ATTR_VAL);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glFrontFace(GLES20.GL_CCW);
        return drawn;
    }

    private static float area(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
    }

    /** Uploads the colour look when it changes: a custom LUT bitmap, or a baked {@link ColorGrade}. */
    private void updateLut(BeautyParams p) {
        Object key = p.lut != null ? p.lut : (p.grade != null ? p.grade.key() : null);
        if (key == null) {
            lutKey = null;
            return;
        }
        if (key.equals(lutKey)) return;
        android.graphics.Bitmap bmp = p.lut != null ? p.lut : p.grade.bake();
        int w = bmp.getWidth(), h = bmp.getHeight();
        int n = Math.round((float) Math.cbrt((double) w * h));
        if (n < 2 || n * n * n != w * h || w % n != 0) {
            android.util.Log.w("BeautyRenderer", "Unsupported LUT size " + w + "x" + h);
            lutKey = null;
            return;
        }
        GlUtil.deleteTexture(lutTex);
        lutTex = GlUtil.createTexture(bmp);
        if (bmp != p.lut) bmp.recycle();
        lutP[0] = n;
        lutP[1] = w / n;
        lutP[2] = 1f / w;
        lutP[3] = 1f / h;
        lutKey = key;
    }

    private void drawQuad() {
        quad.position(0);
        GLES20.glVertexAttribPointer(GlUtil.ATTR_POS, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(GlUtil.ATTR_POS);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void bindTexture(int unit, int target, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(target, texture);
    }

    /** Frees GL objects; call on the GL thread while the context is still current. */
    public void release() {
        if (!glReady) return;
        glReady = false;
        for (int i = 0; i < 2; i++) {
            if (copyProg[i] != null) copyProg[i].release();
            if (blurInProg[i] != null) blurInProg[i].release();
            if (devProg[i] != null) devProg[i].release();
            if (compProg[i] != null) compProg[i].release();
            copyProg[i] = blurInProg[i] = devProg[i] = compProg[i] = null;
        }
        blurProg.release();
        maskProg.release();
        makeupProg.release();
        presentProg.release();
        makeup.release();
        regions.release();
        GlUtil.deleteTexture(regionTex);
        GlUtil.deleteTexture(makeupTex);
        GlUtil.deleteTexture(lutTex);
        if (meshIbo != 0) GLES20.glDeleteBuffers(1, new int[]{meshIbo}, 0);
        makeupTex = regionTex = lutTex = meshIbo = 0;
        lutKey = null;
        tmp.release();
        mean.release();
        dev.release();
        mask.release();
        detect.release();
        if (gridVbo != 0) GLES20.glDeleteBuffers(2, new int[]{gridVbo, gridIbo}, 0);
        if (pbo != 0) GLES20.glDeleteBuffers(1, new int[]{pbo}, 0);
        gridVbo = gridIbo = pbo = 0;
        gridInW = gridInH = 0;
    }

    // ---------------------------------------------------------------- math

    private static float px(float[] lm, int i, int w) {
        return lm[2 * i] * w;
    }

    private static float py(float[] lm, int i, int h) {
        return (1f - lm[2 * i + 1]) * h;
    }

    private static float dist(float[] lm, int a, int b, int w, int h) {
        return (float) Math.hypot(px(lm, a, w) - px(lm, b, w), py(lm, a, h) - py(lm, b, h));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }
}
