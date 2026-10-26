package com.facebeauty;

import android.graphics.Bitmap;

import java.util.Locale;

/**
 * A colour "look" (white balance, tone, saturation, split toning). It is baked into a 32x32x32
 * LUT once and applied on the GPU with one texture lookup per pixel, so any grade costs the same.
 * All values are roughly -1..1 (0 = neutral) unless noted.
 */
public final class ColorGrade {

    static final int LUT_N = 32;
    static final int LUT_COLS = 8;

    /** Brightness in stops. */
    public float exposure;
    public float contrast;
    public float saturation;
    /** Saturation boost that favours muted colours (keeps skin from going orange). */
    public float vibrance;
    /** Cool (-) to warm (+). */
    public float temperature;
    /** Green (-) to magenta (+). */
    public float tint;
    /** 0..1, soft lift of highlights for a luminous look. */
    public float glow;
    /** 0..1, raised blacks for a soft matte look. */
    public float fade;
    /** 0..1, strength of the shadow/highlight colours below. */
    public float splitTone;
    public int shadowColor = 0xFF808080;
    public int highlightColor = 0xFF808080;

    public ColorGrade copy() {
        ColorGrade g = new ColorGrade();
        g.exposure = exposure;
        g.contrast = contrast;
        g.saturation = saturation;
        g.vibrance = vibrance;
        g.temperature = temperature;
        g.tint = tint;
        g.glow = glow;
        g.fade = fade;
        g.splitTone = splitTone;
        g.shadowColor = shadowColor;
        g.highlightColor = highlightColor;
        return g;
    }

    // ------------------------------------------------------------ looks

    public static ColorGrade natural() {
        ColorGrade g = new ColorGrade();
        g.temperature = 0.05f;
        g.vibrance = 0.10f;
        g.contrast = 0.03f;
        return g;
    }

    /** Soft, low-contrast. */
    public static ColorGrade smooth() {
        ColorGrade g = new ColorGrade();
        g.contrast = -0.08f;
        g.saturation = -0.05f;
        g.fade = 0.25f;
        g.glow = 0.15f;
        return g;
    }

    /** Warm and luminous. */
    public static ColorGrade glow() {
        ColorGrade g = new ColorGrade();
        g.exposure = 0.10f;
        g.glow = 0.55f;
        g.temperature = 0.10f;
        g.vibrance = 0.15f;
        g.fade = 0.08f;
        g.splitTone = 0.25f;
        g.highlightColor = 0xFFFFC8A0;
        return g;
    }

    /** Cool, clean and bright. */
    public static ColorGrade fresh() {
        ColorGrade g = new ColorGrade();
        g.exposure = 0.06f;
        g.temperature = -0.14f;
        g.vibrance = 0.25f;
        g.saturation = 0.05f;
        g.contrast = 0.06f;
        g.splitTone = 0.2f;
        g.shadowColor = 0xFF6A8CA8;
        return g;
    }

    /** Pink, romantic. */
    public static ColorGrade rosy() {
        ColorGrade g = new ColorGrade();
        g.tint = 0.18f;
        g.temperature = 0.04f;
        g.glow = 0.2f;
        g.splitTone = 0.3f;
        g.highlightColor = 0xFFFFB6C8;
        return g;
    }

    /** Bright, low-saturation porcelain skin. */
    public static ColorGrade fair() {
        ColorGrade g = new ColorGrade();
        g.exposure = 0.14f;
        g.saturation = -0.14f;
        g.temperature = -0.05f;
        g.glow = 0.25f;
        g.contrast = -0.03f;
        return g;
    }

    /** Warm honey-gold, lifted highlights. */
    public static ColorGrade golden() {
        ColorGrade g = new ColorGrade();
        g.temperature = 0.22f;
        g.tint = 0.03f;
        g.glow = 0.35f;
        g.vibrance = 0.12f;
        g.contrast = 0.04f;
        g.splitTone = 0.35f;
        g.highlightColor = 0xFFFFD27A;
        g.shadowColor = 0xFF8A6A50;
        return g;
    }

    /** Soft peach-pink, airy. */
    public static ColorGrade peach() {
        ColorGrade g = new ColorGrade();
        g.exposure = 0.08f;
        g.temperature = 0.08f;
        g.tint = 0.10f;
        g.fade = 0.18f;
        g.glow = 0.25f;
        g.splitTone = 0.3f;
        g.highlightColor = 0xFFFFC4A8;
        return g;
    }

    /** Milky soft white, low contrast. */
    public static ColorGrade milk() {
        ColorGrade g = new ColorGrade();
        g.exposure = 0.16f;
        g.contrast = -0.10f;
        g.saturation = -0.18f;
        g.fade = 0.30f;
        g.glow = 0.30f;
        return g;
    }

    /** Deep warm bronze, richer contrast. */
    public static ColorGrade bronze() {
        ColorGrade g = new ColorGrade();
        g.exposure = -0.04f;
        g.temperature = 0.28f;
        g.contrast = 0.12f;
        g.vibrance = 0.10f;
        g.splitTone = 0.4f;
        g.shadowColor = 0xFF6A4A3A;
        g.highlightColor = 0xFFFFC890;
        return g;
    }

    /** Classic black and white. */
    public static ColorGrade mono() {
        ColorGrade g = new ColorGrade();
        g.saturation = -1f;
        g.contrast = 0.12f;
        return g;
    }

    /** Applies the grade to one colour (for UI previews). */
    public int applyTo(int argb) {
        float[] c = {(argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f};
        float sr = ((shadowColor >> 16 & 0xFF) / 255f - 0.5f) * splitTone;
        float sg = ((shadowColor >> 8 & 0xFF) / 255f - 0.5f) * splitTone;
        float sb = ((shadowColor & 0xFF) / 255f - 0.5f) * splitTone;
        float hr = ((highlightColor >> 16 & 0xFF) / 255f - 0.5f) * splitTone;
        float hg = ((highlightColor >> 8 & 0xFF) / 255f - 0.5f) * splitTone;
        float hb = ((highlightColor & 0xFF) / 255f - 0.5f) * splitTone;
        apply(c, (float) Math.pow(2.0, exposure), sr, sg, sb, hr, hg, hb);
        return 0xFF000000 | to8(c[0]) << 16 | to8(c[1]) << 8 | to8(c[2]);
    }

    // ------------------------------------------------------------ baking

    /** Identity of the bake; equal keys produce the same LUT. */
    String key() {
        return String.format(Locale.US, "%.3f|%.3f|%.3f|%.3f|%.3f|%.3f|%.3f|%.3f|%.3f|%x|%x",
                exposure, contrast, saturation, vibrance, temperature, tint, glow, fade, splitTone,
                shadowColor, highlightColor);
    }

    /** LUT in the "N slices side by side" layout ({@value LUT_COLS} columns, {@value LUT_N}^3). */
    Bitmap bake() {
        int n = LUT_N;
        int w = n * LUT_COLS;
        int h = n * (n / LUT_COLS);
        int[] px = new int[w * h];
        float[] c = new float[3];
        float exp = (float) Math.pow(2.0, exposure);
        float sr = ((shadowColor >> 16 & 0xFF) / 255f - 0.5f) * splitTone;
        float sg = ((shadowColor >> 8 & 0xFF) / 255f - 0.5f) * splitTone;
        float sb = ((shadowColor & 0xFF) / 255f - 0.5f) * splitTone;
        float hr = ((highlightColor >> 16 & 0xFF) / 255f - 0.5f) * splitTone;
        float hg = ((highlightColor >> 8 & 0xFF) / 255f - 0.5f) * splitTone;
        float hb = ((highlightColor & 0xFF) / 255f - 0.5f) * splitTone;
        for (int b = 0; b < n; b++) {
            for (int g = 0; g < n; g++) {
                for (int r = 0; r < n; r++) {
                    c[0] = r / (n - 1f);
                    c[1] = g / (n - 1f);
                    c[2] = b / (n - 1f);
                    apply(c, exp, sr, sg, sb, hr, hg, hb);
                    int x = (b % LUT_COLS) * n + r;
                    int y = (b / LUT_COLS) * n + g;
                    px[y * w + x] = 0xFF000000 | to8(c[0]) << 16 | to8(c[1]) << 8 | to8(c[2]);
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private void apply(float[] c, float exp, float sr, float sg, float sb, float hr, float hg, float hb) {
        float r = c[0] * exp, g = c[1] * exp, b = c[2] * exp;

        // White balance.
        r *= 1f + 0.10f * temperature + 0.04f * tint;
        g *= 1f - 0.06f * tint;
        b *= 1f - 0.10f * temperature + 0.04f * tint;

        // Glow: lift the upper tones toward white, smoothly.
        float l = luma(r, g, b);
        if (glow > 0f) {
            float k = glow * smoothstep(0.3f, 1f, l) * 0.35f;
            r += (1f - r) * k;
            g += (1f - g) * k;
            b += (1f - b) * k;
        }

        // Contrast around mid grey.
        float ct = 1f + contrast;
        r = 0.5f + (r - 0.5f) * ct;
        g = 0.5f + (g - 0.5f) * ct;
        b = 0.5f + (b - 0.5f) * ct;

        // Saturation + vibrance.
        l = luma(r, g, b);
        float sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        float f = 1f + saturation + vibrance * (1f - Math.min(1f, sat * 2f));
        r = l + (r - l) * f;
        g = l + (g - l) * f;
        b = l + (b - l) * f;

        // Split toning: colour the shadows and highlights separately.
        if (splitTone > 0f) {
            l = clamp01(luma(r, g, b));
            float ws = (1f - l) * (1f - l) * 0.5f;
            float wh = l * l * 0.5f;
            r += sr * ws + hr * wh;
            g += sg * ws + hg * wh;
            b += sb * ws + hb * wh;
        }

        // Fade: raise the black point.
        float fd = fade * 0.12f;
        r = fd + r * (1f - fd);
        g = fd + g * (1f - fd);
        b = fd + b * (1f - fd);

        c[0] = clamp01(r);
        c[1] = clamp01(g);
        c[2] = clamp01(b);
    }

    private static float luma(float r, float g, float b) {
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int to8(float v) {
        return Math.round(v * 255f);
    }
}
