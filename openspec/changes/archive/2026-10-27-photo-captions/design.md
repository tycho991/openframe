# Design

## Context

What already exists:

- **Metadata.** `MediaEngine.probe` reads format, size and capture date. The Android build uses the framework `ExifInterface`, the JVM fake in tests uses metadata-extractor. `PhotoEntry` keeps the result in the index (`JsonStore`, `ignoreUnknownKeys = true`, `explicitNulls = false`).
- **When files are read.** `LocalPhotoSource` reads a file only when its mtime or size changes. `RemoteSource.fetch` probes every download, but on an unchanged ETag it discards the result and returns the previous entry.
- **Originals of remote sources.** They are a cache bounded by the free storage minus a reserve (`OriginalsStore`), so they are usually all on the frame.
- **Slide state.** The display gets `SlideState.Showing(photo, file, step, seq)` from `SlideshowController`. It shows the photo through `SlideRenderer`, which fades the back view in over the front view (`fade`), slides both (`slide`), or swaps them at once (`none`, duration 0, first photo, or the instant fallback of `TransitionGovernor`).
- **Clock.** `ClockOverlay` places the clock in one of four corners and is hidden while the hint is up (`DisplayActivity.setHintVisible`).
- **Frame language.** `FrameLanguage.AUTO` is resolved only in `DisplayActivity.locale`, from the system configuration, so `:core` cannot name anything in the frame's language today.
- **Changes this one builds on.** `photo-crop` makes the fields of `PhotoPatch` optional (`minProperties: 1`) and adds `crop` to `PhotoEntry`. `nested-albums` makes `PhotoEntry.album` the full folder path.

Requirements are in the specs of this change; motivation in proposal.md.

## Goals / Non-Goals

**Goals:**
- The place lookup works without a network, and its memory is affordable on the Frameo (1 GB RAM, API 23).
- One function in `:core` builds the caption's text, so the display only lays it out and the rules are testable on the JVM.
- The metadata of existing photos is filled in once, without blocking the slideshow or rendering anything again.
- The format of the place data takes a new language by adding a file, without touching the base.

**Non-Goals:**
- A general metadata framework for further EXIF fields. `metadataVersion` leaves room for one, nothing more is built.
- Sharing the place lookup with the web UI: the web UI shows what the API sends.

## Decisions

### 1. Place data: a base and one name pack per language, generated and committed

A script, `scripts/build-places.py`, turns the GeoNames dumps `cities500.zip` and `alternateNamesV2.zip` into three files under `core/src/main/resources/places/`:

- **`base.bin`: every place.**
  - The places are sorted by 1°×1° cell.
  - A cell table (`IntArray` of 64,801 start indices) points into them.
  - Per place: latitude and longitude as `Short` offsets inside the cell in units of 1/10,000° (about 11 m), an index into a table of country codes, and the offset of its name in a UTF-8 name pool.
  - The names are GeoNames' own `name` field.
- **`names-de.bin`: the German names.** Pairs of place index and name, sorted by index, only where the German name differs from the base name.
  - The script picks the name with `isPreferredName` first, then one that is not a short name.
  - It skips historic and colloquial names.
  - Measured on the dump of 2026-10-24: 5,628 names, 53 KB compressed.
- **`SOURCE.txt`:** the dump date, the attribution and the licence (CC BY 4.0).

Base and pack carry the same dataset ID in their header. A pack with another ID is ignored and logged, so a pack from a different build cannot attach wrong names.

The generated files are committed; together they are about 2.8 MB.

*Alternative:* let Gradle download and build the data. Rejected:
- The build would need a network.
- GeoNames changes daily, so builds would not be reproducible.

The data is regenerated on purpose, not on every build.

*Alternative:* `cities1000`, `cities5000`, or polygons of municipalities. The user chose `cities500`. Polygons are several MB to GB, partly under licences that forbid commercial use (GADM) or require share-alike (OSM), and need point-in-polygon tests on the device.

*Alternative:* an English pack. Rejected by measurement: GeoNames' own names already are the English exonyms ("Munich", "Milan"). An en pack would mostly strip diacritics ("Poznań" to "Poznan") or add suffixes ("Dubai City").

### 2. Lookup in memory, loaded on first use

`PlaceIndex` (in `core/.../places/`) loads `base.bin` from the class path on its first lookup into flat arrays:
- coordinates as two `ShortArray`
- country indices as a `ByteArray`
- name offsets as an `IntArray`
- the name pool as a `ByteArray`

That is about 5 MB of heap, without one object per place. Names are decoded only for a hit.

A lookup searches the cells within 50 km: ±0.45° of latitude, and the matching width in longitude, capped at the whole circle near the poles. It measures by the equirectangular approximation, which is exact enough for 50 km. It returns the nearest place, or none.

The index loads only when a place is needed, either for the API's photo list or for a caption with the place on. A frame that never shows places never pays for it.

*Alternative:* a k-d tree as in Nextcloud. Rejected: a grid over sorted arrays needs no objects and no build step on the device, and is fast enough with a few hundred distance checks per lookup.

*Alternative:* reading names from the resource on demand. Rejected: resources inside the APK have no random access without extracting them first, and 2.4 MB of name pool is affordable.

### 3. Language and country names in `:core`

`PlatformInfo` gains `systemLocale: Locale`. On Android it is the system configuration's locale, which `DisplayActivity.locale` reads today; in the JVM fake it is a fixed locale.

`FrameLanguage.locale(platform)` moves to `:core`, and the display uses it too, so clock date, texts and place names cannot disagree.

A place name is:
- the pack's name for the language if there is one, otherwise the base name,
- followed by `Locale("", code).getDisplayCountry(locale)`.

If the platform has no name for the code, `getDisplayCountry` returns the code itself (as for `XK`); the place then stands alone.

The place name is never stored, only computed when asked for. Changing the language or adding a pack therefore needs no rescan (spec "Language of the place name").

### 4. New fields of `PhotoEntry` and `metadataVersion`

`PhotoEntry` gains three fields:
- `comment: String? = null`
- `gps: GeoPoint? = null`, with `lat` and `lon` as `Double`
- `metadataVersion: Int = 0`

Version 1 means "GPS has been read". An index from an older build loads with 0 everywhere, which is exactly "not read yet". `gps == null` together with version 1 means "no position" (spec "Metadata of photos already in the index").

A future field read from EXIF raises the constant and reuses the same backfill.

`ImageInfo` gains `gps`, which `probe` fills. The validity rule (±90°/±180°, not 0/0) is applied in `:core`, not in each engine. Every entry created or re-read by this build gets the current version.

`LocalPhotoSource.read` and `RemoteSource.fetch` carry `comment` over to a new version as they carry `hidden`.

`RemoteSource.fetch` keeps returning the previous entry on an unchanged ETag. If that entry's `metadataVersion` is below the current one, it now first takes `gps` over from the probe it just did and sets the version. That is the "with its next download" of the spec.

### 5. Metadata-only read for the backfill

`MediaEngine` gains `readMetadata(file): ImageMetadata` (today only `gps`). It reads the EXIF data without a probe decode:
- On Android: `ExifInterface(path).getLatLong(FloatArray(2))`.
- In the JVM fake: metadata-extractor's `GpsDirectory.getGeoLocation()`.

`probe` stays as it is for new files, because there the decode check is the point.

*Alternative:* re-run `probe` for the backfill. Rejected: a probe decode of 10,000 photos on the Frameo takes minutes of CPU, while EXIF alone takes milliseconds per file.

### 6. The backfill job

`MetadataBackfill` (in `core/.../library/`) runs on a thread of its own with minimum priority. It starts:
- once the library is ready,
- after every scan,
- after every sync.

Each run takes the entries whose `metadataVersion` is below the current one:
- a local photo: its file in the photo folder,
- a remote photo with its original on the frame (`OriginalsStore.fileOf` exists): that file,
- a remote photo without its original: skipped and left for its next download.

It reads in batches of 50 and writes each batch with one `index.edit`, so the library version changes once per batch and not once per photo.

The write applies to an entry only if its `version` is still the one that was read. A file that changed in between keeps the result of its own new read.

A file that cannot be read (gone, damaged) still gets the current version with no position, so it is never retried.

### 7. The caption is built in `:core`

`CaptionText.of(entry, captionSettings, placeName)` is a pure function. It returns the two lines, or `null` if both are empty:
- **First line:** the album title, i.e. the last segment of `entry.album`, used only when it is switched on and the photo has an album. If there is a comment, the title is followed by `" · "` and the comment; otherwise the title stands alone.
- **Second line:** the place name, if switched on and present.

`FrameCore` exposes `caption: StateFlow<ShownCaption?>`, where `ShownCaption` holds `seq` and the text. It combines four inputs:
- the slideshow state (which photo, which `seq`),
- the current entry of that photo from the library (so an edited comment shows without a photo change),
- the settings,
- the resolved language.

The display therefore gets its data as a `StateFlow`, as the module rules require. The 5 seconds of the spec are met because every input is a `StateFlow` that emits at once.

### 8. Caption on the display: switched at the swap, faded with the photo

`CaptionOverlay` (in `:android`, next to `ClockOverlay`) holds two caption views that cross-fade, each with a text line (`maxLines = 2`, ellipsis at the end) and a place line (`maxLines = 1`). The maximum width is 60 % of the screen's width.

**Swap.** `SlideRenderer` gains a callback when it actually starts the swap to a photo, with its `seq`, whether the swap is animated, and the duration. The caption switches only then:
- **Animated:** the incoming caption view fades from 0 to 1 and the outgoing one from 1 to 0, over the photo's duration. This holds for `fade` and for `slide`; text sliding along with the photo would be harder to read.
- **Instant:** the text is set immediately, as are the cases the renderer handles at once.

The callback is needed because the state can change before the next photo is decoded. Switching on the state alone could put a caption over the photo it does not belong to (spec "Caption changes with the photo").

**Same `seq`, new text.** A caption update with the same `seq` replaces the text of the visible view in place.

**Legibility.** The caption uses the clock's text shadow, and with `design-system` its type and colours.

**Hint.** `setHintVisible` hides the caption next to the clock.

### 9. Corner and stacking

`ClockPosition` becomes `Corner`, in `:core` and as the `Corner` schema in the contract. The serial names stay (`top-left` and so on), so stored settings and clients are unchanged. `CaptionSettings(albumTitle = false, place = false, position = Corner.BOTTOM_LEFT)` joins `Settings` as `caption`, with patch fields `caption.albumTitle`, `caption.place` and `caption.position`.

Both overlays sit in the root `FrameLayout` with the gravity of their corner. A pure function `cornerOffsets(clockCorner, clockOn, clockHeight, captionCorner)` gives the caption its extra margin towards the middle when it shares the corner with a visible clock:
- in the bottom corners, above the clock,
- in the top corners, below it.

The clock's height comes from a layout listener, so a size change or a new format moves the caption too. The function is unit-tested on the JVM. That there is no overlap at 300 % in portrait is checked on the devices.

*Alternative:* reparent both into one vertical `LinearLayout` per corner. Rejected: `ClockOverlay` manages its own container's gravity, and moving views between parents on every settings change is more fragile than one margin.

### 10. Comment normalization and validation

The comment is normalized in one place, in `:core`, before it is validated:
1. Remove surrounding whitespace.
2. Replace each run of control characters (`Character.isISOControl`, which includes line breaks) with one space.
3. Count Unicode code points (`codePointCount`).

The order matters: trimming first means "comment too long" is measured on the trimmed text, as the spec says.

The rules:
- An empty result is `null`.
- More than 500 code points is 400 `validation_failed` with the field `comment`.
- The whole patch is rejected, so `hidden` and `crop` stay unchanged too.

The web UI counts the same way (`[...text.trim()].length`) for its counter, but the server decides.

### 11. API

Written in `api/openapi.yaml` first:
- **`PhotoPatch.comment`:** a string or `null`, optional like the other fields after `photo-crop`. The description names the 500 code points after trimming.
- **`Photo.comment`:** a string or `null`, always present.
- **`Photo.location`:** `null` or `{lat, lon, place}`, where `place` is a string or `null` and is given in the frame's language.
- **`Settings.caption` and `SettingsPatch.caption`.**
- **`Corner`** in place of `ClockPosition`.

There are no new endpoints, so `RouteAccessTest` is unchanged. Contract tests run with `checkContract = true` as usual.

### 12. Web UI

`PhotoDetail` gets:
- a comment field (a `textarea`, because comments are sentences) with a counter and a save action; errors go to the field through `fields.comment`,
- the place and coordinates, formatted with four decimals in the interface's locale.

`SettingsView` gets the caption group:
- two switches and a corner choice, reusing the clock's corner control,
- next to the place switch, the attribution "Place names: GeoNames, CC BY 4.0", from `en.json`/`de.json`. It is shown as text with a plain link to geonames.org; nothing is loaded from there.

## Risks / Trade-offs

- [5 MB heap for the place index on the Frameo] → It loads only when a place is needed, and is measured with `dumpsys meminfo` before and after on the Frameo (DEVICE_TEST.md). If it is too much, the name pool can be read per hit from an extracted file instead (decision 2).
- [The APK and the repository grow by about 2.8 MB, and again with each regeneration] → The data is regenerated only on purpose, and old blobs are the price of an offline build.
- [Nearest place is not the municipality: a photo near a border can name the neighbouring village] → `cities500` keeps distances small. Changing the place by hand is out of scope, and the comment is the way out.
- [The backfill competes with rendering on weak devices] → It runs at minimum priority, in batches, without decoding. Its duration for 1,000 photos is measured on the Frameo.
- [Two more views fading during a transition could tip the GPU measurement of `TransitionGovernor` on the Frameo] → The caption views are small, and the measurement on the devices is repeated with a caption shown (DEVICE_TEST.md).
- [A rollback to an older build loses comments and positions once that build writes the index] → The older build ignores the fields when reading, but its `JsonStore` writes only the fields it knows. This is acceptable for a rollback and is said in the release notes. Positions come back through the backfill; comments do not.
- [`getDisplayCountry` differs between Android versions (ICU data)] → The country name may vary slightly between devices, which is accepted. Tests compare against the JVM's names, not fixed strings, except for a few stable ones ("Italien", "Italy").

## Migration Plan

**Update.** There is no data migration step.
- The index loads with `metadataVersion = 0`, and the backfill fills in positions in the background.
- Settings without `caption` get the defaults (spec "Caption settings").

**Rollback.** The older build applies stored settings field by field through `SettingsPatch` and drops `caption` with a log line. It ignores `comment`, `gps` and `metadataVersion` in the index; for the loss of comments once it rewrites the index, see Risks.

**Ordering.** This change is implemented after `nested-albums`, `multi-source-share-links` and `photo-crop`, which change `PhotoEntry`, `PhotoPatch`, `RemoteSource.fetch` and `PhotoDetail`. Before `nested-albums` the album has only one level, so "last level" would be the same; the ordering is about avoiding merge work, not about behaviour.

## Open Questions

- The backfill's batch size and the pause between batches may be tuned after the device tests; they change neither specs nor tasks.
