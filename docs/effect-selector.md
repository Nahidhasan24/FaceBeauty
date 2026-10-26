# Effect selector

## `BeautyPanelView`: ready-made

A TikTok/Snapchat-style panel:
* tabs: **Filters, Skin, Reshape, Eyes, Makeup**
* round effect buttons, with a ring on active effects
* one strength slider, which is two-way (−/+) for items like chin length
* colour swatches for colour effects
* **↺** reset and **◐** hold-to-compare

It needs no layouts, images or themes; everything is created in code.

```xml
<com.facebeauty.BeautyPanelView
    android:id="@+id/beautyPanel"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
panel.setParams(BeautyParams.natural())          // initial state (copied)
panel.setDefaults(BeautyParams.natural())        // what ↺ restores
panel.setListener { params ->                    // every change
    beautyView.setParams(params)                 // or videoProcessor.setParams(params)
}
panel.setCompareListener { showOriginal ->       // while ◐ is held
    videoProcessor.isEnabled = !showOriginal
}
panel.selectCategory(BeautyCatalog.Category.MAKEUP)
```

`getParams()` returns the panel's live object. Copy it (`params.copy()`) if you store it, for example to save the user's look:

```kotlin
// Save: the fields are plain values, so persist the ones you need (or all of them).
prefs.edit().putFloat("smoothing", panel.params.smoothing).apply()
```

Behaviour:
* Tapping an effect that is off turns it on at a pleasant default (`Item.defaultValue`); the slider fine-tunes it.
* Tapping a filter applies its colour grade and matching skin tone; the slider sets its strength.
* Picking a colour swatch also turns that effect on if it was off.

## `BeautyCatalog`: build your own UI

The panel is built only from `BeautyCatalog`, which describes every effect. Use the same data to build a selector in Compose or your own design:

```kotlin
@Composable
fun BeautyTab(category: BeautyCatalog.Category, params: BeautyParams, onChange: (BeautyParams) -> Unit) {
    var selected by remember { mutableStateOf<BeautyCatalog.Item?>(null) }
    LazyRow {
        items(BeautyCatalog.items(category)) { item ->
            EffectButton(
                icon = item.icon, label = item.label,
                active = item.isActive(params),
                swatch = if (item.isLook) Color(item.previewColor()) else null,
                onClick = {
                    selected = item
                    if (item.isLook || item.get(params) == 0f) item.set(params, item.defaultValue)
                    onChange(params)
                },
            )
        }
    }
    selected?.let { item ->
        Slider(
            value = item.get(params),
            valueRange = if (item.bipolar) -1f..1f else 0f..1f,
            onValueChange = { item.set(params, it); onChange(params) },
        )
        if (item.hasColors()) ColorRow(item.colors, item.getColor(params)) { c ->
            item.setColor(params, c); onChange(params)
        }
    }
}
```

`BeautyCatalog.Item`:

| Member | |
|---|---|
| `id`, `label`, `icon`, `category` | Identity and display. `icon` is a short emoji you can replace with your own artwork by `id` |
| `bipolar` | Slider is −1..1 with 0 neutral |
| `colors` | Palette for colour effects (empty otherwise) |
| `defaultValue` | Suggested strength when first switched on |
| `get(params)` / `set(params, v)` | Read/write the effect strength |
| `getColor` / `setColor` | Read/write the effect colour |
| `isActive(params)` | Effect currently on |
| `isLook`, `look`, `isSelectedLook(params)`, `previewColor()` | Filters: the grade, whether it is applied, and a preview swatch colour |

Other helpers: `BeautyCatalog.items(category)`, `BeautyCatalog.all()`, `BeautyCatalog.find(id)`, and the palettes `LIP_COLORS`, `BLUSH_COLORS`, `SHADOW_COLORS`, `LINER_COLORS`, `LENS_COLORS`, `LASH_COLORS`, `SKIN_TONES`.

Some effects appear in two tabs (for example Big eyes under Reshape and Eyes). Both items edit the same field.
