# Design

## Context

Today the core renders an uncropped display size that covers the screen (`RenderSizes.display`, up to 2.5 times the screen's pixels), and the display crops it: `BitmapFit.forView` trims the bitmap to the centered window that `CENTER_CROP` shows, or scales it down for `FIT_CENTER`. `fill`/`fit` is a global setting (`FillMode`). A photo's state in `PhotoEntry` is `hidden`, carried over by `LocalPhotoSource` and `RemoteSource` when a file gets a new version. `AndroidMediaEngine.render` decodes the whole original with `inSampleSize` and draws it scaled and EXIF-rotated onto a target bitmap. The bootstrap design already named the place where a crop would attach. See proposal.md for why.

## Goals / Non-Goals

**Goals:**
- One pure function in `:core` decides what part of a photo the frame shows, for the display, the cache key and the API alike.
- Sharp details up to the largest zoom without decoding a 48 MP original at full size on a frame.
- The threshold of `auto` can be changed by editing one constant.

**Non-Goals:**
- Any change to thumbnails: the grid keeps showing whole photos.
- Moving the section while a photo is on screen: a new section takes effect at the next change, as other photo changes do.

## Decisions

### 1. Zoom relative to `fill`, section as `{x, y, zoom}`

For a photo of W×H pixels (after EXIF rotation) and a screen S_w×S_h, the cover scale is `c = max(S_w/W, S_h/H)` and the fit scale `f = min(S_w/W, S_h/H)`. A zoom `z` means the scale `s = c·z`. Its range on a screen is `z_min = f/c` (fully visible) to `z_max = max(1, min(4, 1/c))` (one original pixel per screen pixel, at most 4; a photo smaller than the screen allows 1, upscaled as today). The stored zoom is clamped into this range when shown, never rewritten (spec "Section across screen formats"). The visible window has the fractions `w = min(1, S_w/(s·W))` and `h = min(1, S_h/(s·H))`. Its center is the focus point clamped to `[w/2, 1−w/2]` and `[h/2, 1−h/2]`, which yields both "moved inward at the edge" and "centered between borders" where `w = 1` or `h = 1`.

*Alternative:* a rectangle locked to the screen's aspect ratio, as `pictureframe` had. Rejected: it is invalid after switching between landscape and portrait, and the user chose a focus point with a zoom. *Alternative:* zoom relative to `fit`. Rejected: zoom 1 = "fills the screen" is the natural middle of the range, and `auto` and `fill` become simple values on it.

### 2. Fill modes as presets of the same function

Without a section, the fill mode chooses the zoom with the focus point at the center: `fill` is `z = 1`, `fit` is `z = z_min`, and `auto` is `z = min(1, z_min / (1 − T))` with `T = 0.25` as `Presentation.AUTO_MAX_CROP` in `:core`. Because `z_min` is exactly the share of the long direction that `fill` keeps, `auto` keeps at least `1 − T` of it. `T` stays a constant, not a setting: it can be adjusted once if it does not fit, not offered as an option. A device test of it with the user's judgement is out of scope.

### 3. `Presentation` as the single source

`Presentation.of(width, height, screen, crop, fillMode)` in `core/.../library/` returns the window (normalized `x, y, width, height`), the effective zoom and the output size in pixels (`w·W·s` × `h·H·s`, at most the screen, never more than the window's original pixels). `RenderSizes.display` is replaced by it, the 2.5 factor goes away. The API's `Photo.presentation` is this result. Since the editor in the web UI must react to every finger movement, the web UI has its own small copy of the formula. Both are tested against one shared table of cases (`api/fixtures/presentation-cases.json`: photo size, screen, section, fill mode → window and zoom), read by a Kotlin test and a Vitest test, so that they cannot drift apart.

*Alternative:* the web UI asks the server for every preview. Rejected: too slow for dragging on a frame on Wi-Fi.

### 4. The core renders the window, the display only centers it

`MediaEngine.render` gains a `region` (normalized rectangle in rotated coordinates, default the whole image). `RenderCache` renders the display size as `Presentation`'s window at its output size, so the display file is at most the screen's size. `SlideRenderer` shows every photo with `FIT_CENTER` on a black background: the rendered image already has the aspect ratio of the visible part, so `FIT_CENTER` produces exactly the borders of the presentation. `BitmapFit.forView` scales down a bitmap larger than the view and pads it with black to the view's aspect ratio, so the borders are opaque pixels of the bitmap: a crossfade blends them like the rest of the photo, while transparent borders would leave the outgoing photo in them until the transition ends (`FINDINGS.md` 57). The views stay without a background, so `alpha` still needs no layer. Setting `fillMode` no longer changes the views, it changes the rendered files. This also removes the oversized portrait bitmaps that `BitmapFit` had to trim for the Lenovo's GPU; a padded bitmap is at most the screen's size.

*Alternative:* keep the uncropped display size and crop in the display with the `ImageView`'s matrix. Rejected: a zoom above 1 would be upscaled from a size that only covers zoom 1, so it would be blurred.

### 5. Decoding only the needed region on Android

`AndroidMediaEngine.render` maps the region back to the stored, unrotated image (inverse of the EXIF orientation) and decodes it with `BitmapRegionDecoder` (framework, API 10) and an `inSampleSize` chosen from the region's size and the output size. If the format or the device's decoder does not support region decoding (GIF, a failing `newInstance` or `decodeRegion`), it falls back to today's sampled full decode followed by cropping. For a 48 MP photo at zoom 4 on the Frameo the region is about 2000×1170 pixels instead of 8000×6000. The JVM fake in `core/src/test/.../testing/` crops with `BufferedImage.getSubimage`.

### 6. Cache key and URL carry the window

The display file is named `id-v{version}-{w}x{h}-{key}.jpg`, where `key` is the window rounded to 1/10,000 per coordinate. A new section, fill mode or orientation thus gives a new file and, through a parameter `p` with this key in the signed display URL and in the ETag, a new URL (spec "Section in the photo list"). Display files of other windows fall out by LRU, as those of the other orientation do today, so `peekAnyDisplay` can still show an older one while the new one renders. `peekAnyDisplay` must skip the full size as it skips the thumbnail. The full size (`size=full`) is named like the thumbnail with its own size and is uncropped.

### 7. Stored section and its fate on a new version

`PhotoEntry` gets `crop: Crop? = null` (`x`, `y`, `zoom`), serialized only when set (`explicitNulls = false`). No migration: an index without `crop` loads with no sections. An older build ignores the field (`ignoreUnknownKeys`) and shows the photos in its fill mode, which is enough as a rollback. `LocalPhotoSource` and `RemoteSource` carry `crop` over to a new version as they carry `hidden`, but only if `|W₁·H₂ − W₂·H₁| ≤ 0.01·W₁·H₂` (spec "Section when the file changes"). Setting a section is an index change, so the library version changes and the slideshow and the web UI see it as they see hiding.

### 8. API

Written in `api/openapi.yaml` first.
- `PhotoPatch`: `hidden` and `crop` both optional, `minProperties: 1`. `crop` is a `Crop` (`x`, `y` 0 to 1, `zoom` exclusive minimum 0, maximum 4) or `null` to remove it. Errors name `crop.x`, `crop.y`, `crop.zoom`, as `SettingsPatch` names its fields. Allowed for every source, like hiding.
- `Photo`: `crop` (`Crop` or `null`), `presentation` (`x`, `y`, `width`, `height`, `zoom`), `urls.full`.
- `GET /photos/{photoId}/image`: `size` gains `full`; `p` is part of the signed display URL.
- `FillMode` gains `auto`.

`presentation` depends on the settings and the screen as well as the photo. A client that caches photos has to reload them when the settings version changes, not only the library version. The web UI does this for the open detail view.

### 9. Editor in the web UI

`SectionEditor` (Svelte) opens from `PhotoDetail` with `urls.full`. Its viewport has the frame's aspect ratio (`GET /info` → `screen`, swapped for `portrait`) and draws the image with CSS transforms from the client copy of `Presentation`. Pointer Events: one pointer drags the focus point (by the movement divided by the current scale), two pointers pinch, the wheel and the −/+ buttons zoom, all clamped to `[z_min, z_max]`. "Automatic" sets `crop: null`, "Save" sends `PATCH /photos/{id}` and shows the returned `presentation`. It is the editor of `pictureframe` turned around: the viewport is fixed and the photo moves. With a focus point that is the more direct gesture, and zoom below 1 shows the borders exactly as the frame will.

### 10. Default and order of changes

`Settings.fillMode` defaults to `AUTO`. Stored settings contain every value (`encodeDefaults = true`), so existing frames keep `fill` (spec "Default fill mode"). The change is implemented after `nested-albums` and `multi-source-share-links`, which change `PhotoEntry`, `StoredIndex`, `LocalPhotoSource`, `RemoteSource` and the photo routes; this change adds a field to the same places. Neither modifies the requirements this change modifies; the ADDED requirement "Section in the photo list" is separate from "Photo list through the API", which `nested-albums` modifies, so the deltas do not collide at archive time.

## Risks / Trade-offs

- [Switching the fill mode or the orientation renders every display size again] → as with the orientation today: `peekAnyDisplay` shows the older size until the new one is ready, and prefetch renders ahead by priority.
- [`BitmapRegionDecoder` misbehaves on old Android versions with some JPEG files, for example progressive ones] → any exception or `null` falls back to the sampled full decode; a device test zooms into a progressive JPEG on the Lenovo.
- [25 % does not fit what the user likes] → one constant (`AUTO_MAX_CROP`), with the shared cases and the spec scenarios that name it; not tested on the devices in this change.
- [The editor's copy of the formula drifts from the core's] → both run the shared table of cases.
- [A section chosen for landscape looks different in portrait] → accepted: the focus point stays, the zoom is clamped; a section per format is out of scope.
- [Changing a section re-renders while the frame may be showing that photo] → it takes effect at the next change, the current photo is not replaced.

## Migration Plan

No data migration. Updating keeps the stored fill mode and loads the index without sections. Rolling back to an older build ignores the sections (`ignoreUnknownKeys`). Its `SettingsRepository.load` applies the stored values through `SettingsPatch` field by field, so an unknown `auto` costs only that field: the older build logs it and uses its default `fill`, and every other setting is kept.

## Open Questions

- The exact longest edge of the full size (2048 px) may be lowered if the editor on the Frameo's Wi-Fi feels slow; it changes neither specs nor tasks.
