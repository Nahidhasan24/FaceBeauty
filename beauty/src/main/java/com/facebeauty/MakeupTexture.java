package com.facebeauty;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Paints the built-in makeup into the canonical face UV layout (see {@link FaceMesh}): R = blush,
 * G = eyeshadow, B = eyeliner, A = eyelashes. Because it lives in UV space, it is drawn onto the tracked face
 * mesh every frame and follows expressions and head turns like real makeup. Artists can replace it
 * with their own texture painted over MediaPipe's canonical UV template.
 */
final class MakeupTexture {

    static final int SIZE = 1024;

    private static final int[] RIGHT_LID = {33, 246, 161, 160, 159, 158, 157, 173, 133};
    private static final int[] LEFT_LID = {263, 466, 388, 387, 386, 385, 384, 398, 362};

    private MakeupTexture() {
    }

    static ByteBuffer create() {
        Bitmap blush = layer(SIZE);
        Bitmap shadow = layer(SIZE);
        Bitmap liner = layer(SIZE);
        Bitmap lashes = layer(SIZE);
        paintBlush(new Canvas(blush), 50, 116, 234);
        paintBlush(new Canvas(blush), 280, 345, 454);
        paintShadow(new Canvas(shadow), RIGHT_LID, FaceRegions.RIGHT_BROW_LOWER, FaceRegions.RIGHT_EYE);
        paintShadow(new Canvas(shadow), LEFT_LID, FaceRegions.LEFT_BROW_LOWER, FaceRegions.LEFT_EYE);
        paintLiner(new Canvas(liner), RIGHT_LID);
        paintLiner(new Canvas(liner), LEFT_LID);
        paintLashes(new Canvas(lashes), RIGHT_LID);
        paintLashes(new Canvas(lashes), LEFT_LID);
        return combine(SIZE, blush, shadow, liner, lashes);
    }

    /**
     * Packs four ALPHA_8 layers into raw RGBA bytes (R, G, B, A = the four layers; recycles them).
     * Raw bytes, not a Bitmap, so the 4th layer is plain data and never premultiplies the others.
     */
    static ByteBuffer combine(int size, Bitmap r, Bitmap g, Bitmap b, Bitmap a) {
        int n = size * size;
        int[] pr = new int[n], pg = new int[n], pb = new int[n], pa = new int[n];
        r.getPixels(pr, 0, size, 0, 0, size, size);
        g.getPixels(pg, 0, size, 0, 0, size, size);
        b.getPixels(pb, 0, size, 0, 0, size, size);
        a.getPixels(pa, 0, size, 0, 0, size, size);
        r.recycle();
        g.recycle();
        b.recycle();
        a.recycle();
        ByteBuffer out = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < n; i++) {
            out.put((byte) (pr[i] >>> 24)).put((byte) (pg[i] >>> 24))
                    .put((byte) (pb[i] >>> 24)).put((byte) (pa[i] >>> 24));
        }
        out.rewind();
        return out;
    }

    static Bitmap layer(int size) {
        return Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
    }

    /** Tapered, outward-curling lash strokes, longer toward the outer corner. */
    private static void paintLashes(Canvas c, int[] lid) {
        int n = lid.length;
        float eyeW = (float) Math.hypot(x(lid[0]) - x(lid[n - 1]), y(lid[0]) - y(lid[n - 1]));
        float ox = (x(lid[0]) - x(lid[n - 1])) / eyeW, oy = (y(lid[0]) - y(lid[n - 1])) / eyeW;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        int strokes = 30;
        for (int k = 0; k < strokes; k++) {
            float s = (k + 0.5f) / strokes; // 0 = outer corner .. 1 = inner corner
            float fi = s * (n - 1);
            int i = Math.min(n - 2, (int) fi);
            float f = fi - i;
            float bx = x(lid[i]) + (x(lid[i + 1]) - x(lid[i])) * f;
            float by = y(lid[i]) + (y(lid[i + 1]) - y(lid[i])) * f;
            float tx = x(lid[i + 1]) - x(lid[i]), ty = y(lid[i + 1]) - y(lid[i]);
            float tl = (float) Math.hypot(tx, ty);
            float nx = ty / tl, ny = -tx / tl;
            if (ny > 0f) {
                nx = -nx;
                ny = -ny;
            }
            // Lean outward, more at the outer end; longer at the outer end.
            float lean = 0.15f + 0.55f * (1f - s);
            float dx = nx + ox * lean, dy = ny + oy * lean;
            float dl = (float) Math.hypot(dx, dy);
            dx /= dl;
            dy /= dl;
            float len = eyeW * (0.10f + 0.14f * (1f - s) * (1f - s) + 0.04f * (float) Math.sin(k * 2.3));
            float half = eyeW * 0.011f;
            float tipX = bx + dx * len + ox * len * 0.15f;
            float tipY = by + dy * len;
            Path path = new Path();
            path.moveTo(bx + tx / tl * half, by + ty / tl * half);
            path.quadTo(bx + dx * len * 0.6f, by + dy * len * 0.55f, tipX, tipY);
            path.quadTo(bx + dx * len * 0.55f - tx / tl * half, by + dy * len * 0.5f - ty / tl * half,
                    bx - tx / tl * half, by - ty / tl * half);
            path.close();
            c.drawPath(path, p);
        }
    }

    /** Soft ellipse on the apple of the cheek, swept up toward the temple. */
    private static void paintBlush(Canvas c, int apple, int cheekbone, int side) {
        float ax = x(apple), ay = y(apple);
        float cx = ax + (x(cheekbone) - ax) * 0.35f;
        float cy = ay + (y(cheekbone) - ay) * 0.35f;
        float dx = x(side) - ax, dy = y(side) - ay - 0.04f * SIZE;
        float rx = 0.40f * (float) Math.hypot(dx, dy);
        float ry = rx * 0.62f;
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(0f, 0f, rx, new int[]{0xFF000000, 0xA0000000, 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.translate(cx, cy);
        c.rotate(angle);
        c.scale(1f, ry / rx);
        c.drawCircle(0f, 0f, rx, p);
        c.restore();
    }

    /** Gradient from the lash line (strong) to just below the brow (faint). */
    private static void paintShadow(Canvas c, int[] lid, int[] browLower, int[] eyeOpening) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(SIZE * 0.012f, BlurMaskFilter.Blur.NORMAL));
        p.setColor(0x60000000);
        c.drawPath(lidBand(lid, browLower, 0.85f), p);
        p.setColor(0xB0000000);
        c.drawPath(lidBand(lid, browLower, 0.5f), p);
        p.setColor(0xFF000000);
        c.drawPath(lidBand(lid, browLower, 0.25f), p);

        // Keep the eyeball itself clean: the lash line stays crisp after the blur.
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawPath(polygon(eyeOpening), clear);
    }

    /** Lash-line liner, thicker toward the outer corner, ending in a lifted wing. */
    private static void paintLiner(Canvas c, int[] lid) {
        int n = lid.length;
        float eyeW = (float) Math.hypot(x(lid[0]) - x(lid[n - 1]), y(lid[0]) - y(lid[n - 1]));
        float[] ux = new float[n], uy = new float[n], lx = new float[n], ly = new float[n];
        for (int i = 0; i < n; i++) {
            int a = lid[Math.max(0, i - 1)], b = lid[Math.min(n - 1, i + 1)];
            float tx = x(b) - x(a), ty = y(b) - y(a);
            float len = (float) Math.hypot(tx, ty);
            float nx = ty / len, ny = -tx / len;
            if (ny > 0f) {
                nx = -nx;
                ny = -ny;
            }
            float s = i / (n - 1f); // 0 = outer corner, 1 = inner corner
            float thick = eyeW * (0.075f - 0.06f * s);
            ux[i] = x(lid[i]) + nx * thick;
            uy[i] = y(lid[i]) + ny * thick;
            lx[i] = x(lid[i]) - nx * thick * 0.25f;
            ly[i] = y(lid[i]) - ny * thick * 0.25f;
        }
        Path path = new Path();
        path.moveTo(ux[0], uy[0]);
        for (int i = 1; i < n; i++) path.lineTo(ux[i], uy[i]);
        for (int i = n - 1; i >= 0; i--) path.lineTo(lx[i], ly[i]);
        path.close();

        // Wing: from the outer corner, outward and up.
        float ox = x(lid[0]) - x(lid[n - 1]), oy = y(lid[0]) - y(lid[n - 1]);
        float olen = (float) Math.hypot(ox, oy);
        ox /= olen;
        oy /= olen;
        float wx = ox, wy = oy - 0.5f; // y grows downward, so "up" is negative
        float wlen = (float) Math.hypot(wx, wy);
        float tipX = x(lid[0]) + wx / wlen * eyeW * 0.32f;
        float tipY = y(lid[0]) + wy / wlen * eyeW * 0.32f;
        path.moveTo(ux[1], uy[1]);
        path.lineTo(tipX, tipY);
        path.lineTo(lx[0], ly[0]);
        path.close();

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(SIZE * 0.0015f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(path, p);
    }

    /** Region between the lid line and a fraction of the way up to the brow. */
    private static Path lidBand(int[] lid, int[] brow, float reach) {
        Path path = new Path();
        int n = lid.length;
        path.moveTo(x(lid[0]), y(lid[0]));
        for (int i = 1; i < n; i++) path.lineTo(x(lid[i]), y(lid[i]));
        for (int i = n - 1; i >= 0; i--) {
            float s = i / (n - 1f) * (brow.length - 1);
            int k = Math.min(brow.length - 2, (int) s);
            float f = s - k;
            float bx = x(brow[k]) + (x(brow[k + 1]) - x(brow[k])) * f;
            float by = y(brow[k]) + (y(brow[k + 1]) - y(brow[k])) * f;
            path.lineTo(x(lid[i]) + (bx - x(lid[i])) * reach, y(lid[i]) + (by - y(lid[i])) * reach);
        }
        path.close();
        return path;
    }

    private static Path polygon(int[] idx) {
        Path path = new Path();
        path.moveTo(x(idx[0]), y(idx[0]));
        for (int i = 1; i < idx.length; i++) path.lineTo(x(idx[i]), y(idx[i]));
        path.close();
        return path;
    }

    static float x(int landmark) {
        return FaceMesh.UV[2 * landmark] * SIZE;
    }

    static float y(int landmark) {
        return FaceMesh.UV[2 * landmark + 1] * SIZE;
    }
}
