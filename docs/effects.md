# Effects reference

All effects are fields of `BeautyParams`. Values are `0..1` (off at 0) unless marked **±**, which means `-1..1` with 0 neutral. Values outside the range are clamped. `BeautyParams` is a plain mutable object; renderers copy it on `setParams`, so you can keep editing the same instance.

## Presets

| Preset | Look |
|---|---|
| `BeautyParams.none()` | Everything off (camera image unchanged) |
| `natural()` | Subtle everyday retouch, light reshape, natural colour |
| `smooth()` | Strongest skin smoothing, soft matte tone |
| `glow()` | Warm, luminous skin, hint of blush |
| `fresh()` | Cool, clean and bright |
| `rosyLook()` | Pink cheeks and lips |
| `fair()` | Bright porcelain skin |
| `golden()` | Warm golden skin with a sun-kissed glow |
| `glam()` | Full makeup: liner, lashes, shadow, blush, lipstick, plus reshape |

Start from a preset and change single fields: `BeautyParams.natural().apply { lipstick = 0.5f }`.

## Skin

| Field | Effect |
|---|---|
| `smoothing` | Edge-preserving smoothing (fast guided filter). Pores and blemishes go; eyes, brows, lips and hair stay sharp |
| `whitening` | Brightens skin only, with a curve that never clips highlights |
| `skinTint`, `skinTintAmount` | Skin-only tint (soft light): golden `0xFFE8B060`, peach `0xFFFFC4A8`, porcelain `0xFFFFE4E1`, bronze `0xFFC68A55`, rosy `0xFFF4A7B9` |
| `rosy` | Healthy pink tone on skin |
| `sharpen` | Clarity on everything that is not skin, so the result stays crisp |
| `darkCircles` | Lifts and neutralises the under-eye area |
| `smileLines` | Softens the nose-to-mouth folds |
| `highlight` | Soft highlight on nose bridge, cheekbones and chin |
| `bodySkin` | How much neck/ear skin follows the face's whitening and smoothing (default 0.7), so the face never looks like a mask |

## Reshape

| Field | Effect |
|---|---|
| `eyeEnlarge` | Bigger eyes |
| `faceSlim` | Slimmer jaw (V-line) |
| `faceSmall` | Whole face slightly smaller |
| `chinSlim` | Shorter, narrower chin |
| `chinLength` ± | Shorter (−) / longer (+) chin |
| `cheekbone` | Narrower cheekbones |
| `forehead` ± | Lower (−) / higher (+) forehead |
| `noseSlim` | Narrower nose wings |
| `noseLength` ± | Shorter (−) / longer (+) nose |
| `mouthSize` ± | Smaller (−) / fuller (+) mouth |
| `eyeDistance` ± | Closer (−) / wider-set (+) eyes |

Reshaping is a smooth mesh warp computed on the GPU, with displacement capped so the image never folds. Background close to the face bends slightly, as in every beauty app.

## Eyes

| Field | Effect |
|---|---|
| `eyeBrighten` | Whitens the eye whites (clipped to the real eye opening) and adds iris contrast |
| `lens`, `lensColor` | Coloured contact lenses: recolours the iris and keeps the pupil, with a natural dark limbal ring, clipped by the eyelids |
| `eyelashes`, `eyelashColor` | Fuller upper lashes |
| `eyeliner`, `eyelinerColor` | Winged liner on the upper lash line |
| `eyeshadow`, `eyeshadowColor` | Lid shadow fading toward the brow |

## Makeup

| Field | Effect |
|---|---|
| `lipstick`, `lipColor` | Lipstick hue and depth; real lip texture kept; open mouth excluded (teeth never painted) |
| `lipGloss` | Specular gloss (with lipstick) |
| `blush`, `blushColor` | Cheek-apple blush swept toward the temples |
| `teethWhiten` | Whitens teeth; tongue and gums untouched |

Makeup is painted once onto MediaPipe's canonical face layout and drawn on the tracked 478-point face mesh every frame. It follows expressions and head turns, and the far side of the face is culled when you turn.

## Filters (colour looks)

```kotlin
p.grade = ColorGrade.golden()     // natural, smooth, glow, fresh, rosy, fair, golden, peach, milk, bronze, mono
p.gradeStrength = 0.8f
```

A `ColorGrade` is a set of parameters (exposure, contrast, saturation, vibrance, temperature, tint, glow, fade, split toning) baked once into a 32³ lookup table. Applying it costs one texture lookup per pixel, whatever the look. Make your own:

```kotlin
val myLook = ColorGrade().apply {
    temperature = 0.15f; vibrance = 0.2f; glow = 0.3f
    splitTone = 0.3f; highlightColor = 0xFFFFD27A.toInt()
}
```

### Custom LUT images

```kotlin
p.lut = BitmapFactory.decodeStream(assets.open("film.png"))   // treat as immutable once set
p.gradeStrength = 1f
```

Supported layouts: the common 512×512 (64³, 8×8 tiles) and N²×N strips, e.g. 1024×32. These are the formats exported from Photoshop, Resolve and most LUT packs. `lut` overrides `grade`.

The selector's filters also set a matching skin tint (`skinTint` / `skinTintAmount`), so White, Golden and similar looks change the *skin* tone naturally instead of only tinting the whole frame. See `BeautyCatalog`.

## Cost

Everything above runs in one full-resolution GPU pass plus a few tiny passes. Effects you leave at 0 cost nothing. Makeup and retouch regions add one cheap mesh pass each, only when used.
