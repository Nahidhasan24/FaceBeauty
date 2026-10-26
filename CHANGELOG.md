# Changelog

## 1.0.1

* Fixed: with lashes enabled, or with smile lines plus any makeup, the whole face rendered dark. The mesh pass forced alpha to 1, and alpha is the lashes / smile-line layer.
* Fixed: coloured lenses left a thin colour line on closed eyelids. Lenses now fade out as the eye closes.
* New: `BeautyImageProcessor.processVideoFrame(bitmap, params, timestampMs)` beautifies recorded video frame by frame, with face tracking and jitter filtering for steady results.
* New: `BeautyLiveKit` (beauty-livekit). Java-friendly `createCameraTrack`, `connect` and `publish` for LiveKit's Kotlin-first API, plus Java examples in every guide and a Java sample screen (`LiveKitJavaActivity`).
* README: demo animation, gallery and selector screenshot.

## 1.0.0

First release.

* Skin: smoothing (fast guided filter), whitening, skin tone, rosy, clarity, dark circles, smile lines, highlight, neck matching.
* Reshape: big eyes, slim face, small face, V chin, chin length, cheekbones, forehead, slim nose, nose length, mouth size, eye distance.
* Eyes: bright eyes, coloured lenses, lashes, eyeliner, eyeshadow.
* Makeup: lipstick with gloss, blush, teeth whitening. Makeup is drawn on the tracked face mesh.
* Filters: 11 colour looks with matching skin tones, custom `ColorGrade`, and LUT images.
* Frame-accurate rendering (`SyncedBeautyRenderer`) for preview and video.
* `BeautyGLView`, `BeautyImageProcessor`, `BeautyPanelView`, `BeautyCatalog`.
* `beauty-livekit`: `BeautyVideoProcessor` and `BeautyCameraCapturer`.
