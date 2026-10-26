package com.facebeauty;

/**
 * GLSL ES 1.00 sources (run on every GLES2/GLES3 GPU).
 *
 * Coordinate convention everywhere: "upright uv" = the image as displayed, u right, v up.
 * {@code uTexMatrix} maps upright uv to the input texture's own coordinates (camera rotation,
 * crop, or a bitmap flip), so the input is never copied just to straighten it.
 */
final class Shaders {

    /** Warp operations the composite vertex shader accepts (2 faces x 20). */
    static final int MAX_OPS = 40;
    /** Faces whose neck/skin region the composite shader evaluates. */
    static final int MAX_BODY = 2;

    static final String FRAG_PRECISION =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                    + "precision highp float;\n"
                    + "#else\n"
                    + "precision mediump float;\n"
                    + "#endif\n";

    /** Header for fragment shaders that read the input frame. */
    static String inputHeader(boolean oes) {
        return oes
                ? "#extension GL_OES_EGL_image_external : require\n" + FRAG_PRECISION
                + "#define INPUT_SAMPLER samplerExternalOES\n"
                : FRAG_PRECISION + "#define INPUT_SAMPLER sampler2D\n";
    }

    /** Full-screen pass; vIn is the upright uv already mapped into the input texture. */
    static final String QUAD_VS = ""
            + "attribute vec2 aPos;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "uniform float uFlipY;\n"
            + "varying vec2 vUv;\n"
            + "varying vec2 vIn;\n"
            + "void main() {\n"
            + "  vUv = aPos;\n"
            + "  vIn = (uTexMatrix * vec4(aPos, 0.0, 1.0)).xy;\n"
            + "  gl_Position = vec4(aPos.x * 2.0 - 1.0, (aPos.y * 2.0 - 1.0) * uFlipY, 0.0, 1.0);\n"
            + "}\n";

    /** Draws a finished frame onto the screen with crop/letterbox, mirror and flip. */
    static final String PRESENT_VS = ""
            + "attribute vec2 aPos;\n"
            + "uniform vec4 uPosXform;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vUv = aPos;\n"
            + "  gl_Position = vec4((aPos * 2.0 - 1.0) * uPosXform.xy + uPosXform.zw, 0.0, 1.0);\n"
            + "}\n";

    static final String PRESENT_FS = FRAG_PRECISION
            + "uniform sampler2D uTex;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);\n"
            + "}\n";

    /** Straight copy of the input (used for the small frame handed to the landmark model). */
    static final String COPY_FS = ""
            + "uniform INPUT_SAMPLER uInput;\n"
            + "varying vec2 vIn;\n"
            + "void main() {\n"
            + "  gl_FragColor = vec4(texture2D(uInput, vIn).rgb, 1.0);\n"
            + "}\n";

    // 9-tap Gaussian done with 5 bilinear fetches (offsets/weights of the linear-sampling trick).
    private static final String GAUSS = ""
            + "const float O1 = 1.3846154;\n"
            + "const float O2 = 3.2307692;\n"
            + "const float W0 = 0.2270270;\n"
            + "const float W1 = 0.3162162;\n"
            + "const float W2 = 0.0702703;\n";

    /** Horizontal blur that also downsamples the input into the low-res buffer. */
    static final String BLUR_INPUT_FS = GAUSS
            + "uniform INPUT_SAMPLER uInput;\n"
            + "uniform vec2 uStepIn;\n"
            + "varying vec2 vIn;\n"
            + "void main() {\n"
            + "  vec2 o1 = uStepIn * O1;\n"
            + "  vec2 o2 = uStepIn * O2;\n"
            + "  vec3 c = texture2D(uInput, vIn).rgb * W0\n"
            + "      + (texture2D(uInput, vIn + o1).rgb + texture2D(uInput, vIn - o1).rgb) * W1\n"
            + "      + (texture2D(uInput, vIn + o2).rgb + texture2D(uInput, vIn - o2).rgb) * W2;\n"
            + "  gl_FragColor = vec4(c, 1.0);\n"
            + "}\n";

    /** Generic separable blur pass on a low-res texture. */
    static final String BLUR_FS = GAUSS
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uStep;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vec2 o1 = uStep * O1;\n"
            + "  vec2 o2 = uStep * O2;\n"
            + "  gl_FragColor = texture2D(uTex, vUv) * W0\n"
            + "      + (texture2D(uTex, vUv + o1) + texture2D(uTex, vUv - o1)) * W1\n"
            + "      + (texture2D(uTex, vUv + o2) + texture2D(uTex, vUv - o2)) * W2;\n"
            + "}\n";

    /**
     * Local mean absolute deviation of luminance around the blurred mean (horizontal half; a
     * vertical BLUR_FS pass finishes it). Stored x4 to use the 8-bit range well. This is the
     * "how much detail is here" signal the guided filter uses to keep edges.
     */
    static final String DEV_FS = GAUSS
            + "uniform INPUT_SAMPLER uInput;\n"
            + "uniform sampler2D uMean;\n"
            + "uniform vec2 uStepIn;\n"
            + "uniform vec2 uStep;\n"
            + "varying vec2 vUv;\n"
            + "varying vec2 vIn;\n"
            + "const vec3 LUMA = vec3(0.299, 0.587, 0.114);\n"
            + "float dev(float k) {\n"
            + "  return abs(dot(texture2D(uInput, vIn + uStepIn * k).rgb\n"
            + "      - texture2D(uMean, vUv + uStep * k).rgb, LUMA));\n"
            + "}\n"
            + "void main() {\n"
            + "  float d = dev(0.0) * W0 + (dev(O1) + dev(-O1)) * W1 + (dev(O2) + dev(-O2)) * W2;\n"
            + "  gl_FragColor = vec4(min(d * 4.0, 1.0), 0.0, 0.0, 1.0);\n"
            + "}\n";

    /** Feathered region polygons: R skin, G lips, B eyes, A inner mouth (teeth). */
    static final String MASK_VS = ""
            + "attribute vec2 aPos;\n"
            + "attribute float aVal;\n"
            + "varying float vVal;\n"
            + "void main() {\n"
            + "  vVal = aVal;\n"
            + "  gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";

    static final String MASK_FS = ""
            + "precision mediump float;\n"
            + "uniform vec4 uColor;\n"
            + "varying float vVal;\n"
            + "void main() {\n"
            + "  gl_FragColor = uColor * vVal;\n"
            + "}\n";

    /**
     * Face mesh with canonical UVs: rasterizes the UV-space makeup texture onto the tracked face
     * (R blush, G eyeshadow, B eyeliner), in input space like the region masks.
     */
    static final String MAKEUP_VS = ""
            + "attribute vec2 aPos;\n"
            + "attribute vec2 aUv;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  vTex = aUv;\n"
            + "  gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";

    static final String MAKEUP_FS = ""
            + "precision mediump float;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform float uFade;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  gl_FragColor = vec4(texture2D(uTex, vTex).rgb * uFade, 1.0);\n"
            + "}\n";

    /**
     * Mesh warp. Each op is a smooth bump-weighted displacement in pixel space:
     * q -= w * (scale * (q - center) + move), w = (1 - |q - center|^2 / r^2)^2.
     * scale > 0 magnifies (eyes), move translates content (jaw, chin, nose). Computed per vertex
     * of a fine grid so the fragment shader pays nothing for warping.
     */
    static final String COMPOSITE_VS = ""
            + "#define MAX_OPS " + MAX_OPS + "\n"
            + "attribute vec2 aPos;\n"
            + "uniform vec4 uPosXform;\n"
            + "uniform vec2 uWarpSize;\n"
            + "uniform int uOpCount;\n"
            + "uniform vec4 uOpA[MAX_OPS];\n" // center.xy, 1/r^2, scale
            + "uniform vec4 uOpB[MAX_OPS];\n" // move.xy
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vec2 q = aPos * uWarpSize;\n"
            + "  for (int i = 0; i < MAX_OPS; i++) {\n"
            + "    if (i >= uOpCount) break;\n"
            + "    vec4 a = uOpA[i];\n"
            + "    vec2 d = q - a.xy;\n"
            + "    float t = max(0.0, 1.0 - dot(d, d) * a.z);\n"
            + "    q -= (t * t) * (a.w * d + uOpB[i].xy);\n"
            + "  }\n"
            + "  vUv = q / uWarpSize;\n"
            + "  gl_Position = vec4((aPos * 2.0 - 1.0) * uPosXform.xy + uPosXform.zw, 0.0, 1.0);\n"
            + "}\n";

    /** Everything else, in one full-resolution pass. */
    static final String COMPOSITE_FS = ""
            + "#define MAX_BODY " + MAX_BODY + "\n"
            + "uniform INPUT_SAMPLER uInput;\n"
            + "uniform sampler2D uMean;\n"
            + "uniform sampler2D uDev;\n"
            + "uniform sampler2D uMask;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "uniform vec2 uInStepX;\n"
            + "uniform vec2 uInStepY;\n"
            + "uniform vec2 uSize;\n"
            + "uniform float uSmoothMix;\n"
            + "uniform float uEps;\n"
            + "uniform float uSharpen;\n"
            + "uniform float uWhiteMix;\n"
            + "uniform float uWhiteBm1;\n"
            + "uniform float uWhiteInvLog;\n"
            + "uniform float uRosy;\n"
            + "uniform vec4 uLip;\n"
            + "uniform float uGloss;\n"
            + "uniform float uTeeth;\n"
            + "uniform float uEyeBright;\n"
            + "uniform float uBodySkin;\n"
            + "uniform int uBodyCount;\n"
            + "uniform vec4 uBodyA[MAX_BODY];\n" // center.xy, axis.xy
            + "uniform vec4 uBodyB[MAX_BODY];\n" // 1/rx, 1/ry, presence
            + "uniform sampler2D uMakeup;\n"
            + "uniform vec4 uBlush;\n"  // rgb, amount
            + "uniform vec4 uShadow;\n"
            + "uniform vec4 uLiner;\n"
            + "uniform sampler2D uLut;\n"
            + "uniform vec4 uLutP;\n"   // N, columns, 1/width, 1/height
            + "uniform float uGrade;\n"
            + "#define MAX_IRIS " + (MAX_BODY * 2) + "\n"
            + "uniform sampler2D uRegions;\n" // r under-eye, g eye opening, b highlight, a folds
            + "uniform vec4 uRetouch;\n"      // dark circles, smile lines, highlight, eye whites
            + "uniform vec4 uSkinTint;\n"     // rgb, amount
            + "uniform vec4 uLens;\n"         // rgb, amount
            + "uniform vec4 uLash;\n"         // rgb, amount
            + "uniform int uIrisCount;\n"
            + "uniform vec4 uIris[MAX_IRIS];\n" // centre.xy, radius (px), presence
            + "varying vec2 vUv;\n"
            + "const vec3 LUMA = vec3(0.299, 0.587, 0.114);\n"
            + "float luma(vec3 c) { return dot(c, LUMA); }\n"
            // Pegtop soft light: tints while keeping the skin's own shading.
            + "vec3 softLight(vec3 b, vec3 s) { return (1.0 - 2.0 * s) * b * b + 2.0 * s * b; }\n"
            // N^3 LUT stored as N slices of NxN laid out in a grid; trilinear via two lookups.
            + "vec3 applyLut(vec3 c) {\n"
            + "  float n1 = uLutP.x - 1.0;\n"
            + "  float b = c.b * n1;\n"
            + "  float s0 = floor(b);\n"
            + "  float s1 = min(s0 + 1.0, n1);\n"
            + "  vec2 rg = c.rg * n1 + 0.5;\n"
            + "  vec2 t0 = (vec2(mod(s0, uLutP.y), floor(s0 / uLutP.y)) * uLutP.x + rg) * uLutP.zw;\n"
            + "  vec2 t1 = (vec2(mod(s1, uLutP.y), floor(s1 / uLutP.y)) * uLutP.x + rg) * uLutP.zw;\n"
            + "  return mix(texture2D(uLut, t0).rgb, texture2D(uLut, t1).rgb, b - s0);\n"
            + "}\n"
            // W3C "color" blend: hue/saturation of c, given luminance, clipped back into gamut.
            + "vec3 setLum(vec3 c, float l) {\n"
            + "  c += l - luma(c);\n"
            + "  float y = luma(c);\n"
            + "  float n = min(min(c.r, c.g), c.b);\n"
            + "  float x = max(max(c.r, c.g), c.b);\n"
            + "  if (n < 0.0) c = y + (c - y) * y / max(y - n, 1e-4);\n"
            + "  if (x > 1.0) c = y + (c - y) * (1.0 - y) / max(x - y, 1e-4);\n"
            + "  return c;\n"
            + "}\n"
            // Soft YCbCr skin classifier; covers light to dark skin tones.
            + "float skinProb(vec3 c) {\n"
            + "  float cb = dot(c, vec3(-0.168736, -0.331264, 0.5)) + 0.5;\n"
            + "  float cr = dot(c, vec3(0.5, -0.418688, -0.081312)) + 0.5;\n"
            + "  return smoothstep(0.26, 0.31, cb) * (1.0 - smoothstep(0.50, 0.55, cb))\n"
            + "       * smoothstep(0.50, 0.535, cr) * (1.0 - smoothstep(0.68, 0.72, cr))\n"
            + "       * smoothstep(0.06, 0.18, luma(c));\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 inUv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n"
            + "  vec3 src = texture2D(uInput, inUv).rgb;\n"
            + "  vec3 mean = texture2D(uMean, vUv).rgb;\n"
            + "  vec4 m = texture2D(uMask, vUv);\n"
            + "  float sp = skinProb(src);\n"
            // Neck/ears: skin-coloured pixels inside an ellipse hanging below each face.
            + "  float body = 0.0;\n"
            + "  vec2 p = vUv * uSize;\n"
            + "  for (int i = 0; i < MAX_BODY; i++) {\n"
            + "    if (i >= uBodyCount) break;\n"
            + "    vec2 d = p - uBodyA[i].xy;\n"
            + "    vec2 ax = uBodyA[i].zw;\n"
            + "    vec2 l = vec2(dot(d, ax), dot(d, vec2(-ax.y, ax.x))) * uBodyB[i].xy;\n"
            + "    body = max(body, (1.0 - smoothstep(0.7, 1.0, length(l))) * uBodyB[i].z);\n"
            + "  }\n"
            + "  body *= sp * uBodySkin;\n"
            + "  float faceSkin = m.r * mix(0.4, 1.0, sp);\n"
            // Tone (whitening/rosy) also covers the eye area at reduced strength so the whole
            // face shifts evenly; smoothing still skips the eyes.
            + "  float skin = max(faceSkin, body);\n"
            + "  float tone = max(skin, m.b * 0.7);\n"
            + "  vec3 c = src;\n"
            // 1. Fast guided filter: flat skin -> local mean, edges/features (high deviation) kept.
            + "  if (uSmoothMix > 0.0) {\n"
            + "    float dv = texture2D(uDev, vUv).r * 0.25;\n"
            + "    dv *= dv;\n"
            + "    vec3 sm = mean + (dv / (dv + uEps)) * (src - mean);\n"
            + "    c = mix(src, sm, max(faceSkin, body * 0.5) * uSmoothMix);\n"
            + "  }\n"
            // Retouch regions drawn on the face mesh.
            + "  vec4 rg = vec4(0.0);\n"
            + "  if (uRetouch.x + uRetouch.y + uRetouch.z + uRetouch.w + uLens.a > 0.0) {\n"
            + "    rg = texture2D(uRegions, vUv);\n"
            + "  }\n"
            // Dark circles: lift and neutralise the purple/brown under the eyes.
            + "  float dk = rg.r * uRetouch.x;\n"
            + "  if (dk > 0.002) {\n"
            + "    vec3 lifted = log(max(c, 0.0) * 3.0 + 1.0) * 0.7213475;\n"
            + "    c = mix(c, mix(lifted, vec3(luma(lifted)), 0.3), dk * 0.75);\n"
            + "  }\n"
            // Smile lines: fill the fold's shadow from the local mean, keep a little texture.
            + "  float fold = rg.a * uRetouch.y;\n"
            + "  if (fold > 0.002) {\n"
            + "    vec3 filled = mean + (c - mean) * 0.3 + max(0.0, luma(mean) - luma(c)) * 0.6;\n"
            + "    c = mix(c, filled, fold);\n"
            + "  }\n"
            // 2. Clarity: unsharp mask + local contrast on everything that is not skin.
            + "  float eye = m.b * uEyeBright;\n"
            + "  float w = uSharpen * (1.0 - 0.85 * skin) + eye * 0.6;\n"
            + "  if (w > 0.001) {\n"
            + "    vec3 nb = texture2D(uInput, inUv + uInStepX).rgb + texture2D(uInput, inUv - uInStepX).rgb\n"
            + "            + texture2D(uInput, inUv + uInStepY).rgb + texture2D(uInput, inUv - uInStepY).rgb;\n"
            + "    c += (src - nb * 0.25) * (w * 2.0) + (src - mean) * (w * 0.25 * (1.0 - skin));\n"
            + "  }\n"
            + "  c = mix(c, c * 1.07 + 0.015, eye);\n"
            // 3. Whitening: log curve lifts shadows/mids more than highlights (no clipping).
            + "  if (uWhiteMix > 0.0) {\n"
            + "    vec3 wc = log(max(c, 0.0) * uWhiteBm1 + 1.0) * uWhiteInvLog;\n"
            + "    wc = mix(wc, vec3(luma(wc)), 0.1);\n"
            + "    c = mix(c, wc, tone * uWhiteMix);\n"
            + "  }\n"
            + "  c = mix(c, c * vec3(1.03, 0.95, 0.97) + vec3(0.025, 0.0, 0.01), tone * uRosy);\n"
            // Skin tone (golden, peach, porcelain...) on skin only.
            + "  if (uSkinTint.a > 0.0) {\n"
            + "    c = mix(c, softLight(clamp(c, 0.0, 1.0), uSkinTint.rgb), tone * uSkinTint.a);\n"
            + "  }\n"
            // Highlight: soft screen-like lift on nose bridge, cheekbones, chin.
            + "  c += (1.0 - c) * (rg.b * uRetouch.z * 0.22);\n"
            // Eye whites: brighten and neutralise bright pixels inside the real eye opening.
            + "  float eo = rg.g;\n"
            + "  if (eo * uRetouch.w > 0.002) {\n"
            + "    float l = luma(c);\n"
            + "    c = mix(c, vec3(l) * 1.1 + 0.05, eo * uRetouch.w * smoothstep(0.28, 0.55, l) * 0.6);\n"
            + "  }\n"
            // Coloured lenses: recolour the iris ring (pupil kept), clipped by the eyelids.
            + "  if (uLens.a > 0.0 && eo > 0.01) {\n"
            + "    for (int i = 0; i < MAX_IRIS; i++) {\n"
            + "      if (i >= uIrisCount) break;\n"
            + "      vec4 ir = uIris[i];\n"
            + "      float d = distance(p, ir.xy);\n"
            + "      float wi = (1.0 - smoothstep(ir.z * 0.92, ir.z * 1.05, d)) * smoothstep(ir.z * 0.3, ir.z * 0.42, d);\n"
            + "      if (wi > 0.0) {\n"
            + "        vec3 lc = setLum(uLens.rgb, clamp(luma(c) * 0.9 + luma(uLens.rgb) * 0.55, 0.0, 1.0));\n"
            + "        lc *= 1.0 - 0.45 * smoothstep(ir.z * 0.72, ir.z * 0.98, d);\n" // limbal ring
            + "        c = mix(c, lc, wi * eo * uLens.a * ir.w);\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            // Makeup: blush/eyeshadow tint the skin (soft light, shading kept), liner/lashes paint.
            + "  if (uBlush.a + uShadow.a + uLiner.a + uLash.a > 0.0) {\n"
            + "    vec4 mk = texture2D(uMakeup, vUv);\n"
            + "    c = mix(c, softLight(clamp(c, 0.0, 1.0), uBlush.rgb), mk.r * uBlush.a);\n"
            + "    vec3 cs = clamp(c, 0.0, 1.0);\n"
            + "    c = mix(c, mix(softLight(cs, uShadow.rgb), cs * uShadow.rgb * 1.15, 0.5), mk.g * uShadow.a);\n"
            + "    c = mix(c, uLiner.rgb * (0.7 + 0.3 * luma(c)), mk.b * uLiner.a);\n"
            + "    c = mix(c, uLash.rgb, mk.a * uLash.a);\n"
            + "  }\n"
            // 4. Lipstick: lipstick hue + lipstick luminance, real lip texture (l - local mean) kept.
            + "  float lipA = m.g * uLip.a;\n"
            + "  if (lipA > 0.002) {\n"
            + "    float l = luma(c);\n"
            + "    float ml = luma(mean);\n"
            + "    vec3 lc = setLum(uLip.rgb, clamp(luma(uLip.rgb) + (l - ml) * 1.1, 0.0, 1.0));\n"
            + "    lc += smoothstep(0.04, 0.2, l - ml) * uGloss * 0.4;\n"
            + "    c = mix(c, lc, lipA);\n"
            + "  }\n"
            // 5. Teeth: bright, low-red pixels inside the mouth only (tongue/gums untouched).
            + "  float tw = m.a * uTeeth;\n"
            + "  if (tw > 0.002) {\n"
            + "    float l = luma(c);\n"
            + "    float red = c.r - 0.5 * (c.g + c.b);\n"
            + "    tw *= smoothstep(0.3, 0.5, l) * (1.0 - smoothstep(0.06, 0.16, red));\n"
            + "    c = mix(c, vec3(l) * 1.1 + 0.03, tw * 0.85);\n"
            + "  }\n"
            + "  c = clamp(c, 0.0, 1.0);\n"
            // 6. Colour look over the whole frame.
            + "  if (uGrade > 0.0) c = mix(c, applyLut(c), uGrade);\n"
            + "  gl_FragColor = vec4(c, 1.0);\n"
            + "}\n";

    private Shaders() {
    }
}
