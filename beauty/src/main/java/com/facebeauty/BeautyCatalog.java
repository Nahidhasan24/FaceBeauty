package com.facebeauty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every effect the library offers, described for selector UIs: category, label, icon, slider
 * type and colour palette. {@link BeautyPanelView} is built entirely from this; apps with their
 * own UI can use it the same way:
 * <pre>
 * for (BeautyCatalog.Item item : BeautyCatalog.items(Category.RESHAPE)) {
 *     slider.setValue(item.get(params));            // -1..1 if item.bipolar, else 0..1
 *     onChange(v -> { item.set(params, v); view.setParams(params); });
 * }
 * </pre>
 */
public final class BeautyCatalog {

    public enum Category {
        LOOKS("Filters"), SKIN("Skin"), RESHAPE("Reshape"), EYES("Eyes"), MAKEUP("Makeup");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    interface Getter {
        float get(BeautyParams p);
    }

    interface Setter {
        void set(BeautyParams p, float v);
    }

    interface ColorAccess {
        int get(BeautyParams p);

        void set(BeautyParams p, int color);
    }

    /** One selectable effect. */
    public static final class Item {
        public final String id;
        public final String label;
        /** Short glyph (emoji) for the button. */
        public final String icon;
        public final Category category;
        /** Two-way slider: -1..1 with 0 = neutral. Otherwise 0..1. */
        public final boolean bipolar;
        /** Colour choices (ARGB) for colour effects, else empty. */
        public final int[] colors;
        /** For filter looks: the colour grade (null for "None"). */
        public final ColorGrade look;
        /** Suggested value when the user first taps the item. */
        public final float defaultValue;

        private final Getter getter;
        private final Setter setter;
        private final ColorAccess color;
        private final int lookTint;
        private final float lookTintAmount;

        private Item(String id, String label, String icon, Category category, boolean bipolar, float defaultValue,
                     int[] colors, Getter getter, Setter setter, ColorAccess color,
                     ColorGrade look, int lookTint, float lookTintAmount) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.category = category;
            this.bipolar = bipolar;
            this.defaultValue = defaultValue;
            this.colors = colors != null ? colors : new int[0];
            this.getter = getter;
            this.setter = setter;
            this.color = color;
            this.look = look;
            this.lookTint = lookTint;
            this.lookTintAmount = lookTintAmount;
        }

        public boolean isLook() {
            return category == Category.LOOKS;
        }

        public boolean hasColors() {
            return colors.length > 0;
        }

        /** Current strength (filters: strength if this filter is the active one, else 0). */
        public float get(BeautyParams p) {
            if (isLook()) return isSelectedLook(p) ? (look == null ? 1f : p.gradeStrength) : 0f;
            return getter.get(p);
        }

        public void set(BeautyParams p, float v) {
            if (!isLook()) {
                setter.set(p, bipolar ? clamp(v, -1f, 1f) : clamp(v, 0f, 1f));
                return;
            }
            v = clamp(v, 0f, 1f);
            if (look == null) {
                p.grade = null;
                p.lut = null;
                p.skinTintAmount = 0f;
                return;
            }
            p.grade = look.copy();
            p.lut = null;
            p.gradeStrength = v;
            p.skinTint = lookTint;
            p.skinTintAmount = lookTintAmount * v;
        }

        /** True when this filter is the one currently applied. */
        public boolean isSelectedLook(BeautyParams p) {
            if (!isLook()) return false;
            if (look == null) return p.grade == null && p.lut == null;
            return p.grade != null && p.lut == null && p.grade.key().equals(look.key());
        }

        public boolean isActive(BeautyParams p) {
            return isLook() ? isSelectedLook(p) && look != null : Math.abs(get(p)) > 0.005f;
        }

        public int getColor(BeautyParams p) {
            return color != null ? color.get(p) : 0;
        }

        public void setColor(BeautyParams p, int c) {
            if (color != null) color.set(p, c);
        }

        /** Preview swatch for filter buttons: the look applied to a typical skin tone. */
        public int previewColor() {
            int skin = 0xFFD8A589;
            if (look == null) return skin;
            int c = look.applyTo(skin);
            if (lookTintAmount > 0f) c = blend(c, lookTint, lookTintAmount * 0.5f);
            return c;
        }
    }

    // ------------------------------------------------------------ palettes

    public static final int[] LIP_COLORS = {
            0xFFC62828, 0xFFD81B60, 0xFFE05A7A, 0xFFFF6F61, 0xFFC48A7A, 0xFF8E244D, 0xFF6A1B4D, 0xFFE65100};
    public static final int[] BLUSH_COLORS = {0xFFF06C8C, 0xFFFF8A80, 0xFFFFA07A, 0xFFE57373, 0xFFD4846A};
    public static final int[] SHADOW_COLORS = {
            0xFF9C6B5A, 0xFFB08D57, 0xFF8E5A7A, 0xFF6D4C41, 0xFFC28E6E, 0xFF5D6B8A};
    public static final int[] LINER_COLORS = {0xFF1A1414, 0xFF4E342E, 0xFF263B5C};
    public static final int[] LENS_COLORS = {
            0xFF6E4B2A, 0xFF9C7A3C, 0xFF8A9AA8, 0xFF4F86C6, 0xFF5E9E6E, 0xFF8E6BBF};
    public static final int[] LASH_COLORS = {0xFF100C0C, 0xFF3E2723};
    public static final int[] SKIN_TONES = {0xFFE8B060, 0xFFFFC4A8, 0xFFFFE4E1, 0xFFC68A55, 0xFFF4A7B9};

    private static final List<Item> ALL = build();

    private BeautyCatalog() {
    }

    public static List<Item> all() {
        return ALL;
    }

    public static List<Item> items(Category category) {
        List<Item> out = new ArrayList<>();
        for (Item i : ALL) if (i.category == category) out.add(i);
        return Collections.unmodifiableList(out);
    }

    public static Item find(String id) {
        for (Item i : ALL) if (i.id.equals(id)) return i;
        return null;
    }

    private static List<Item> build() {
        List<Item> l = new ArrayList<>();

        // Filters: colour grade + matching skin tone, one slider for strength.
        look(l, "look_none", "None", null, 0, 0f);
        look(l, "look_natural", "Natural", ColorGrade.natural(), 0, 0f);
        look(l, "look_white", "White", ColorGrade.fair(), 0xFFFFE4E1, 0.30f);
        look(l, "look_golden", "Golden", ColorGrade.golden(), 0xFFE8B060, 0.45f);
        look(l, "look_smooth", "Silky", ColorGrade.smooth(), 0xFFFFE0D0, 0.15f);
        look(l, "look_glow", "Glow", ColorGrade.glow(), 0xFFFFC8A0, 0.20f);
        look(l, "look_fresh", "Fresh", ColorGrade.fresh(), 0, 0f);
        look(l, "look_rosy", "Rosy", ColorGrade.rosy(), 0xFFF4A7B9, 0.30f);
        look(l, "look_peach", "Peach", ColorGrade.peach(), 0xFFFFC4A8, 0.30f);
        look(l, "look_milk", "Milk", ColorGrade.milk(), 0xFFFFF0EA, 0.25f);
        look(l, "look_bronze", "Bronze", ColorGrade.bronze(), 0xFFC68A55, 0.40f);
        look(l, "look_mono", "Mono", ColorGrade.mono(), 0, 0f);

        Category s = Category.SKIN;
        slider(l, "smooth", "Smooth", "✨", s, 0.5f, p -> p.smoothing, (p, v) -> p.smoothing = v);
        slider(l, "whiten", "Whiten", "🤍", s, 0.35f, p -> p.whitening, (p, v) -> p.whitening = v);
        color(l, "skin_tone", "Skin tone", "🎨", s, 0.35f, SKIN_TONES,
                p -> p.skinTintAmount, (p, v) -> p.skinTintAmount = v,
                p -> p.skinTint, (p, c) -> p.skinTint = c);
        slider(l, "rosy", "Rosy", "🌸", s, 0.3f, p -> p.rosy, (p, v) -> p.rosy = v);
        slider(l, "clarity", "Clarity", "🔍", s, 0.3f, p -> p.sharpen, (p, v) -> p.sharpen = v);
        slider(l, "dark_circles", "Dark circles", "🌙", s, 0.5f, p -> p.darkCircles, (p, v) -> p.darkCircles = v);
        slider(l, "smile_lines", "Smile lines", "〰", s, 0.4f, p -> p.smileLines, (p, v) -> p.smileLines = v);
        slider(l, "highlight", "Highlight", "💡", s, 0.35f, p -> p.highlight, (p, v) -> p.highlight = v);
        slider(l, "neck", "Neck match", "👤", s, 0.7f, p -> p.bodySkin, (p, v) -> p.bodySkin = v);

        Category r = Category.RESHAPE;
        slider(l, "big_eyes", "Big eyes", "👁", r, 0.3f, p -> p.eyeEnlarge, (p, v) -> p.eyeEnlarge = v);
        slider(l, "slim_face", "Slim face", "💎", r, 0.3f, p -> p.faceSlim, (p, v) -> p.faceSlim = v);
        slider(l, "small_face", "Small face", "🙂", r, 0.3f, p -> p.faceSmall, (p, v) -> p.faceSmall = v);
        slider(l, "small_chin", "V chin", "🔻", r, 0.3f, p -> p.chinSlim, (p, v) -> p.chinSlim = v);
        bipolar(l, "chin_length", "Chin length", "↕", r, p -> p.chinLength, (p, v) -> p.chinLength = v);
        slider(l, "cheekbones", "Cheekbones", "◇", r, 0.3f, p -> p.cheekbone, (p, v) -> p.cheekbone = v);
        bipolar(l, "forehead", "Forehead", "⬆", r, p -> p.forehead, (p, v) -> p.forehead = v);
        slider(l, "slim_nose", "Slim nose", "👃", r, 0.3f, p -> p.noseSlim, (p, v) -> p.noseSlim = v);
        bipolar(l, "nose_length", "Nose length", "↧", r, p -> p.noseLength, (p, v) -> p.noseLength = v);
        bipolar(l, "mouth", "Mouth", "👄", r, p -> p.mouthSize, (p, v) -> p.mouthSize = v);
        bipolar(l, "eye_distance", "Eye distance", "↔", r, p -> p.eyeDistance, (p, v) -> p.eyeDistance = v);

        Category e = Category.EYES;
        slider(l, "eyes_big", "Big eyes", "👁", e, 0.3f, p -> p.eyeEnlarge, (p, v) -> p.eyeEnlarge = v);
        slider(l, "bright_eyes", "Bright eyes", "✦", e, 0.5f, p -> p.eyeBrighten, (p, v) -> p.eyeBrighten = v);
        slider(l, "eyes_dark_circles", "Dark circles", "🌙", e, 0.5f, p -> p.darkCircles, (p, v) -> p.darkCircles = v);
        color(l, "lenses", "Lenses", "🔵", e, 0.6f, LENS_COLORS,
                p -> p.lens, (p, v) -> p.lens = v, p -> p.lensColor, (p, c) -> p.lensColor = c);
        color(l, "lashes", "Lashes", "🪶", e, 0.6f, LASH_COLORS,
                p -> p.eyelashes, (p, v) -> p.eyelashes = v, p -> p.eyelashColor, (p, c) -> p.eyelashColor = c);
        color(l, "eye_liner", "Eyeliner", "✒", e, 0.7f, LINER_COLORS,
                p -> p.eyeliner, (p, v) -> p.eyeliner = v, p -> p.eyelinerColor, (p, c) -> p.eyelinerColor = c);
        color(l, "eye_shadow", "Eyeshadow", "🎨", e, 0.5f, SHADOW_COLORS,
                p -> p.eyeshadow, (p, v) -> p.eyeshadow = v, p -> p.eyeshadowColor, (p, c) -> p.eyeshadowColor = c);

        Category m = Category.MAKEUP;
        color(l, "lipstick", "Lipstick", "💄", m, 0.5f, LIP_COLORS,
                p -> p.lipstick, (p, v) -> p.lipstick = v, p -> p.lipColor, (p, c) -> p.lipColor = c);
        slider(l, "gloss", "Gloss", "💧", m, 0.4f, p -> p.lipGloss, (p, v) -> p.lipGloss = v);
        color(l, "blush", "Blush", "🍑", m, 0.4f, BLUSH_COLORS,
                p -> p.blush, (p, v) -> p.blush = v, p -> p.blushColor, (p, c) -> p.blushColor = c);
        color(l, "shadow", "Eyeshadow", "🎨", m, 0.5f, SHADOW_COLORS,
                p -> p.eyeshadow, (p, v) -> p.eyeshadow = v, p -> p.eyeshadowColor, (p, c) -> p.eyeshadowColor = c);
        color(l, "liner", "Eyeliner", "✒", m, 0.7f, LINER_COLORS,
                p -> p.eyeliner, (p, v) -> p.eyeliner = v, p -> p.eyelinerColor, (p, c) -> p.eyelinerColor = c);
        color(l, "makeup_lashes", "Lashes", "🪶", m, 0.6f, LASH_COLORS,
                p -> p.eyelashes, (p, v) -> p.eyelashes = v, p -> p.eyelashColor, (p, c) -> p.eyelashColor = c);
        slider(l, "teeth", "Teeth", "😁", m, 0.5f, p -> p.teethWhiten, (p, v) -> p.teethWhiten = v);
        return Collections.unmodifiableList(l);
    }

    private static void look(List<Item> l, String id, String label, ColorGrade grade, int tint, float tintAmount) {
        l.add(new Item(id, label, "", Category.LOOKS, false, grade == null ? 0f : 0.8f, null,
                null, null, null, grade, tint, tintAmount));
    }

    private static void slider(List<Item> l, String id, String label, String icon, Category c, float def,
                               Getter g, Setter s) {
        l.add(new Item(id, label, icon, c, false, def, null, g, s, null, null, 0, 0f));
    }

    private static void bipolar(List<Item> l, String id, String label, String icon, Category c,
                                Getter g, Setter s) {
        l.add(new Item(id, label, icon, c, true, 0f, null, g, s, null, null, 0, 0f));
    }

    private static void color(List<Item> l, String id, String label, String icon, Category c, float def,
                              int[] colors, Getter g, Setter s, ColorGetter colorGet, ColorSetter colorSet) {
        ColorAccess access = new ColorAccess() {
            @Override
            public int get(BeautyParams p) {
                return colorGet.get(p);
            }

            @Override
            public void set(BeautyParams p, int color) {
                colorSet.set(p, color);
            }
        };
        l.add(new Item(id, label, icon, c, false, def, Arrays.copyOf(colors, colors.length), g, s, access,
                null, 0, 0f));
    }

    interface ColorGetter {
        int get(BeautyParams p);
    }

    interface ColorSetter {
        void set(BeautyParams p, int color);
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static int blend(int a, int b, float t) {
        int r = Math.round((a >> 16 & 0xFF) * (1 - t) + (b >> 16 & 0xFF) * t);
        int g = Math.round((a >> 8 & 0xFF) * (1 - t) + (b >> 8 & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
