package com.facebeauty;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.nio.ByteBuffer;

/**
 * Retouch regions in the canonical face UV layout, drawn on the tracked mesh like the makeup:
 * R = under-eye (dark circles), G = eye opening (lens clipping, eye-white brightening),
 * B = highlight zones (nose bridge, cheekbones, chin, forehead), A = nasolabial folds.
 */
final class RegionTexture {

    static final int SIZE = 512;

    private static final int[] RIGHT_LOWER_LID = {33, 7, 163, 144, 145, 153, 154, 155, 133};
    private static final int[] LEFT_LOWER_LID = {263, 249, 390, 373, 374, 380, 381, 382, 362};

    private RegionTexture() {
    }

    static ByteBuffer create() {
        Bitmap under = MakeupTexture.layer(SIZE);
        Bitmap eyes = MakeupTexture.layer(SIZE);
        Bitmap high = MakeupTexture.layer(SIZE);
        Bitmap folds = MakeupTexture.layer(SIZE);
        paintUnderEye(new Canvas(under), RIGHT_LOWER_LID);
        paintUnderEye(new Canvas(under), LEFT_LOWER_LID);
        paintEyeOpening(new Canvas(eyes), FaceRegions.RIGHT_EYE);
        paintEyeOpening(new Canvas(eyes), FaceRegions.LEFT_EYE);
        paintHighlights(new Canvas(high));
        paintFold(new Canvas(folds), 98, 61, -1f);
        paintFold(new Canvas(folds), 327, 291, 1f);
        return MakeupTexture.combine(SIZE, under, eyes, high, folds);
    }

    /** Crescent below the lower lid, strongest near the inner corner where circles are darkest. */
    private static void paintUnderEye(Canvas c, int[] lid) {
        int n = lid.length;
        float eyeW = (float) Math.hypot(x(lid[0]) - x(lid[n - 1]), y(lid[0]) - y(lid[n - 1]));
        Path path = new Path();
        float gap = eyeW * 0.06f;
        path.moveTo(x(lid[0]), y(lid[0]) + gap);
        for (int i = 1; i < n; i++) path.lineTo(x(lid[i]), y(lid[i]) + gap);
        for (int i = n - 1; i >= 0; i--) {
            float s = i / (n - 1f);
            float depth = eyeW * (0.22f + 0.18f * (float) Math.sin(Math.PI * s));
            path.lineTo(x(lid[i]), y(lid[i]) + gap + depth);
        }
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(eyeW * 0.12f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(path, p);
    }

    private static void paintEyeOpening(Canvas c, int[] contour) {
        Path path = new Path();
        path.moveTo(x(contour[0]), y(contour[0]));
        for (int i = 1; i < contour.length; i++) path.lineTo(x(contour[i]), y(contour[i]));
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        c.drawPath(path, p);
    }

    private static void paintHighlights(Canvas c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(SIZE * 0.02f, BlurMaskFilter.Blur.NORMAL));

        // Nose bridge.
        p.setStrokeWidth(SIZE * 0.025f);
        stroke(c, p, 168, 6, 197, 195, 5);
        // Cheekbones, sweeping toward the temples.
        p.setStrokeWidth(SIZE * 0.035f);
        p.setAlpha(170);
        stroke(c, p, 118, 117, 111);
        stroke(c, p, 347, 346, 340);
        // Centre of forehead and chin.
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(150);
        c.drawCircle(x(151), y(151) + SIZE * 0.02f, SIZE * 0.04f, p);
        p.setAlpha(140);
        c.drawCircle(x(175), y(175), SIZE * 0.025f, p);
    }

    /** Line from beside the nose wing, curving out, to just outside the mouth corner. */
    private static void paintFold(Canvas c, int noseWing, int mouthCorner, float side) {
        float sx = x(noseWing) + side * SIZE * 0.012f, sy = y(noseWing) + SIZE * 0.01f;
        float ex = x(mouthCorner) + side * SIZE * 0.02f, ey = y(mouthCorner) - SIZE * 0.005f;
        float mx = (sx + ex) * 0.5f + side * SIZE * 0.03f, my = (sy + ey) * 0.5f;
        Path path = new Path();
        path.moveTo(sx, sy);
        path.quadTo(mx, my, ex, ey);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(SIZE * 0.035f);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(SIZE * 0.015f, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(path, p);
    }

    private static void stroke(Canvas c, Paint p, int... idx) {
        Path path = new Path();
        path.moveTo(x(idx[0]), y(idx[0]));
        for (int i = 1; i < idx.length; i++) path.lineTo(x(idx[i]), y(idx[i]));
        c.drawPath(path, p);
    }

    private static float x(int landmark) {
        return FaceMesh.UV[2 * landmark] * SIZE;
    }

    private static float y(int landmark) {
        return FaceMesh.UV[2 * landmark + 1] * SIZE;
    }
}
