package com.facebeauty;

/**
 * Strength of every effect. Floats are 0..1 unless documented as -1..1 (two-way sliders where 0
 * is neutral); values outside are clamped at render time. Mutable on purpose so a UI can bind
 * sliders straight to fields; renderers copy it on {@code setParams}, so it is safe to keep
 * editing the same instance. {@link BeautyCatalog} describes every field for building selectors.
 */
public final class BeautyParams {

    /** Edge-preserving skin smoothing (fast guided filter, keeps eyes/brows/lips sharp). */
    public float smoothing;
    /** Skin brightening, applied only to skin (face + matching neck skin). */
    public float whitening;
    /** Healthy pink tint on skin. */
    public float rosy;
    /** Detail/clarity on non-skin areas (eyes, brows, hair, lips) so the result stays crisp. */
    public float sharpen;

    /** Eye enlargement. */
    public float eyeEnlarge;
    /** Jaw/cheek slimming (V-line). */
    public float faceSlim;
    /** Shorter, narrower chin. */
    public float chinSlim;
    /** Narrower nose wings. */
    public float noseSlim;

    /** Lipstick opacity. */
    public float lipstick;
    /** Lipstick colour as ARGB (alpha ignored). */
    public int lipColor = 0xFFC2185B;
    /** Specular gloss on lips; only visible when lipstick &gt; 0. */
    public float lipGloss;

    /** Whitens teeth while leaving tongue/gums alone. */
    public float teethWhiten;
    /** Brightens sclera and adds iris contrast. */
    public float eyeBrighten;
    /**
     * How strongly visible skin around the face (neck, ears) follows the face's whitening and
     * smoothing, so the face never looks like a mask pasted on a darker neck.
     */
    public float bodySkin = 0.7f;

    /** Blush on the cheek apples. */
    public float blush;
    public int blushColor = 0xFFF06C8C;
    /** Eyeshadow on the lids, fading toward the brow. */
    public float eyeshadow;
    public int eyeshadowColor = 0xFF9C6B5A;
    /** Winged liner along the upper lash line. */
    public float eyeliner;
    public int eyelinerColor = 0xFF1A1414;

    /** Colour look applied to the whole frame (null = none). */
    public ColorGrade grade;
    /**
     * Custom LUT image instead of {@link #grade}: a standard 512x512 (64^3) or N^2 x N strip,
     * e.g. exported from Photoshop/Resolve. Treated as immutable once set.
     */
    public android.graphics.Bitmap lut;
    /** Strength of the colour look, 0..1. */
    public float gradeStrength = 1f;

    // ---- skin tone and retouch
    /** Skin-only tint (soft light), e.g. golden, peach or porcelain. Background is untouched. */
    public int skinTint = 0xFFE8B060;
    public float skinTintAmount;
    /** Lightens and neutralises the dark/purple area under the eyes. */
    public float darkCircles;
    /** Softens the nose-to-mouth (nasolabial) folds. */
    public float smileLines;
    /** Soft highlight on nose bridge, cheekbones and chin for a 3D, dewy face. */
    public float highlight;

    // ---- reshape (two-way fields are -1..1)
    /** Whole face slightly smaller. */
    public float faceSmall;
    /** Narrower cheekbones. */
    public float cheekbone;
    /** -1 shorter .. +1 longer chin. */
    public float chinLength;
    /** -1 lower .. +1 higher forehead. */
    public float forehead;
    /** -1 shorter .. +1 longer nose. */
    public float noseLength;
    /** -1 smaller .. +1 fuller mouth. */
    public float mouthSize;
    /** -1 closer .. +1 wider-set eyes. */
    public float eyeDistance;

    // ---- eyes
    /** Coloured contact lenses (iris recolour, pupil and eye whites untouched). */
    public float lens;
    public int lensColor = 0xFF6E4B2A;
    /** Fuller upper lashes. */
    public float eyelashes;
    public int eyelashColor = 0xFF100C0C;

    public BeautyParams() {
    }

    public BeautyParams(BeautyParams other) {
        set(other);
    }

    public BeautyParams set(BeautyParams o) {
        smoothing = o.smoothing;
        whitening = o.whitening;
        rosy = o.rosy;
        sharpen = o.sharpen;
        eyeEnlarge = o.eyeEnlarge;
        faceSlim = o.faceSlim;
        chinSlim = o.chinSlim;
        noseSlim = o.noseSlim;
        lipstick = o.lipstick;
        lipColor = o.lipColor;
        lipGloss = o.lipGloss;
        teethWhiten = o.teethWhiten;
        eyeBrighten = o.eyeBrighten;
        bodySkin = o.bodySkin;
        blush = o.blush;
        blushColor = o.blushColor;
        eyeshadow = o.eyeshadow;
        eyeshadowColor = o.eyeshadowColor;
        eyeliner = o.eyeliner;
        eyelinerColor = o.eyelinerColor;
        grade = o.grade != null ? o.grade.copy() : null;
        lut = o.lut;
        gradeStrength = o.gradeStrength;
        skinTint = o.skinTint;
        skinTintAmount = o.skinTintAmount;
        darkCircles = o.darkCircles;
        smileLines = o.smileLines;
        highlight = o.highlight;
        faceSmall = o.faceSmall;
        cheekbone = o.cheekbone;
        chinLength = o.chinLength;
        forehead = o.forehead;
        noseLength = o.noseLength;
        mouthSize = o.mouthSize;
        eyeDistance = o.eyeDistance;
        lens = o.lens;
        lensColor = o.lensColor;
        eyelashes = o.eyelashes;
        eyelashColor = o.eyelashColor;
        return this;
    }

    public BeautyParams copy() {
        return new BeautyParams(this);
    }

    /** Everything off: renders the camera image unchanged. */
    public static BeautyParams none() {
        BeautyParams p = new BeautyParams();
        p.bodySkin = 0f;
        return p;
    }

    /** Subtle everyday look. */
    public static BeautyParams natural() {
        BeautyParams p = new BeautyParams();
        p.smoothing = 0.45f;
        p.whitening = 0.25f;
        p.rosy = 0.10f;
        p.sharpen = 0.30f;
        p.eyeEnlarge = 0.15f;
        p.faceSlim = 0.15f;
        p.chinSlim = 0.10f;
        p.noseSlim = 0.10f;
        p.eyeBrighten = 0.30f;
        p.teethWhiten = 0.30f;
        p.darkCircles = 0.35f;
        p.smileLines = 0.25f;
        p.grade = ColorGrade.natural();
        p.gradeStrength = 0.8f;
        return p;
    }

    /** Warm golden skin with a sun-kissed glow. */
    public static BeautyParams golden() {
        BeautyParams p = natural();
        p.smoothing = 0.55f;
        p.whitening = 0.20f;
        p.skinTint = 0xFFE8B060;
        p.skinTintAmount = 0.45f;
        p.highlight = 0.35f;
        p.grade = ColorGrade.golden();
        p.gradeStrength = 1f;
        return p;
    }

    /** Strongest skin smoothing, soft matte tone. */
    public static BeautyParams smooth() {
        BeautyParams p = natural();
        p.smoothing = 0.80f;
        p.whitening = 0.30f;
        p.sharpen = 0.20f;
        p.grade = ColorGrade.smooth();
        p.gradeStrength = 1f;
        return p;
    }

    /** Warm, luminous skin with a hint of blush. */
    public static BeautyParams glow() {
        BeautyParams p = natural();
        p.smoothing = 0.55f;
        p.whitening = 0.35f;
        p.rosy = 0.20f;
        p.blush = 0.25f;
        p.blushColor = 0xFFFF8A80;
        p.grade = ColorGrade.glow();
        p.gradeStrength = 1f;
        return p;
    }

    /** Cool, clean and bright. */
    public static BeautyParams fresh() {
        BeautyParams p = natural();
        p.smoothing = 0.45f;
        p.whitening = 0.30f;
        p.sharpen = 0.40f;
        p.eyeBrighten = 0.45f;
        p.grade = ColorGrade.fresh();
        p.gradeStrength = 1f;
        return p;
    }

    /** Pink cheeks and lips. */
    public static BeautyParams rosyLook() {
        BeautyParams p = natural();
        p.smoothing = 0.50f;
        p.whitening = 0.25f;
        p.rosy = 0.50f;
        p.blush = 0.45f;
        p.blushColor = 0xFFF06C8C;
        p.lipstick = 0.30f;
        p.lipColor = 0xFFE05A7A;
        p.lipGloss = 0.3f;
        p.grade = ColorGrade.rosy();
        p.gradeStrength = 1f;
        return p;
    }

    /** Bright porcelain skin. */
    public static BeautyParams fair() {
        BeautyParams p = natural();
        p.smoothing = 0.55f;
        p.whitening = 0.60f;
        p.rosy = 0.05f;
        p.skinTint = 0xFFFFE4E1;
        p.skinTintAmount = 0.25f;
        p.darkCircles = 0.5f;
        p.grade = ColorGrade.fair();
        p.gradeStrength = 1f;
        return p;
    }

    /** Strong social-media look: full makeup (liner, shadow, blush, lipstick). */
    public static BeautyParams glam() {
        BeautyParams p = natural();
        p.smoothing = 0.75f;
        p.whitening = 0.50f;
        p.rosy = 0.20f;
        p.sharpen = 0.40f;
        p.eyeEnlarge = 0.40f;
        p.faceSlim = 0.40f;
        p.chinSlim = 0.35f;
        p.noseSlim = 0.25f;
        p.lipstick = 0.55f;
        p.lipGloss = 0.40f;
        p.eyeBrighten = 0.50f;
        p.teethWhiten = 0.50f;
        p.blush = 0.40f;
        p.eyeshadow = 0.50f;
        p.eyeliner = 0.85f;
        p.eyelashes = 0.6f;
        p.highlight = 0.3f;
        p.darkCircles = 0.5f;
        p.grade = ColorGrade.glow();
        p.gradeStrength = 0.7f;
        return p;
    }

    boolean hasMakeup() {
        return blush > 0f || eyeshadow > 0f || eyeliner > 0f || eyelashes > 0f;
    }

    /** Effects that need the UV region map (eye openings, under-eye, folds, highlight). */
    boolean hasRegions() {
        return darkCircles > 0f || smileLines > 0f || highlight > 0f || lens > 0f || eyeBrighten > 0f;
    }
}
