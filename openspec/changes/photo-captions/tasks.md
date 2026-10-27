# Tasks

## 1. Contract first

- [x] 1.1 `api/openapi.yaml`, per design decision 11:
  - `ClockPosition` renamed to `Corner` (same enum values) and referenced from `Clock.position`.
  - A `Caption` object (`albumTitle`, `place`, `position: Corner`), in `Settings` and, with optional fields, in `SettingsPatch`.
  - `PhotoPatch.comment` (string or null; the description names 500 code points after trimming).
  - `Photo.comment` (string or null, required) and `Photo.location` (null or `{lat, lon, place}` with `place` string or null, required).

  Verify: `npx @redocly/cli lint api/openapi.yaml` passes.
- [x] 1.2 Run `npm run gen:api` in `webadmin/`. Verify: `webadmin/src/lib/api/schema.d.ts` has `Corner`, `Caption`, `Photo.comment` and `Photo.location`, and `git diff --stat` shows only generated changes there.

## 2. Place data

- [x] 2.1 `scripts/build-places.py`, per design decision 1.
  - Input: the paths of `cities500.zip` and `alternateNamesV2.zip`.
  - Output in a target folder:
    - `base.bin`: places sorted by 1° cell, cell table, `Short` offsets in 1/10,000°, country table, name pool.
    - `names-de.bin`: only names that differ from the base name; preferred names first; historic and colloquial names skipped.
    - `SOURCE.txt`: dump date, attribution, CC BY 4.0.
  - The same dataset ID in both headers.
  - The format documented at the top of the script.

  Verify: on the dumps of 2026-10-24 the script reports about 236,000 places and about 5,600 German names, and running it twice gives byte-identical files.
- [x] 2.2 Download the GeoNames dumps into a scratch folder outside the repo and run the script into `core/src/main/resources/places/`. Commit only the three generated files. Verify: the folder holds `base.bin`, `names-de.bin` and `SOURCE.txt`, about 2.8 MB together, and no dump file is in the repo.

## 3. Place lookup in `:core`

- [ ] 3.1 `PlaceIndex` in `core/.../places/`, per design decision 2:
  - Lazy load from the class path into flat arrays.
  - Search of the cells within 50 km with the equirectangular distance.
  - Name packs by language with the dataset ID check (a mismatched pack is ignored and logged).
  - No `java.nio.file`, only APIs available on API 21.

  Verify:
  - Tests for "Place name from the position" › "Photo from Siena" and "Photo on the open sea", for "Language of the place name" › "Milan in German" and "Milan in English".
  - A test that a pack with a wrong dataset ID gives base names.
  - A test that 10,000 lookups take under one second on the JVM.
  - `./gradlew :core:check` passes, including Animal Sniffer.
- [ ] 3.2 `PlatformInfo.systemLocale`, and `FrameLanguage.locale(platform)` moved to `:core` (design decision 3).
  - A place name is the pack name or the base name, then `getDisplayCountry`; if the platform returns the code itself, the place stands alone.
  - Implemented in the JVM fake, and in `:android` from `Resources.getSystem().configuration`.
  - `DisplayActivity.locale` uses the shared function.

  Verify: tests for "auto" with a German and an English fake locale, for a country code without a name, and that the clock's date on the frame follows the language as before (existing `ClockOverlayTest` passes).

## 4. GPS position and the backfill

- [ ] 4.1 Extend `scripts/generate-test-fixtures.py` with JPEGs that carry a GPS position (Siena), the position 0/0, and no GPS. Regenerate `core/src/test/resources/fixtures`. Verify: metadata-extractor reads the expected positions from the new fixtures in a test.
- [ ] 4.2 `ImageInfo.gps` filled by `probe`, plus a new `MediaEngine.readMetadata` without a probe decode (design decision 5). Implemented in the JVM fake (`GpsDirectory`) and in `AndroidMediaEngine` (`ExifInterface.getLatLong`). The validity rule lives in `:core`. Verify:
  - JVM fake tests on the fixtures of 4.1, including "Valid GPS position" › "Position zero".
  - Robolectric tests in `AndroidMediaEngineTest` for a photo with and without GPS.
  - The fake's open counter shows that `readMetadata` does no decode.
- [ ] 4.3 `PhotoEntry` with `gps` and `metadataVersion`. Entries created by this build get the current version. Implemented in `LocalPhotoSource` (scan and upload) and in `RemoteSource.fetch`, including the unchanged-ETag path that takes the position over when the version is old (design decision 4). Verify:
  - Tests for "Metadata" › "Photo with GPS position" and "Photo without GPS position".
  - A test that an index without the new fields loads with version 0.
  - A `RemoteSourceTest` for "Remote photo without its original".
  - "Rescan without changes" still opens no file.
- [ ] 4.4 `MetadataBackfill` per design decision 6: its own thread at minimum priority, started when the library is ready and after each scan and sync, batches of 50 with one `index.edit` each, a write only if `version` is unchanged, and an unreadable file marked as read. Verify:
  - Tests for "Metadata of photos already in the index" › "Update with local photos" (no render call), "Remote photo without its original" and "Photo without GPS data after the update" (no second read after a restart).
  - A test that a file replaced during a batch keeps its own new result.
  - A test that the library version changes once per batch.

## 5. Comment in the library and the API

- [ ] 5.1 `PhotoEntry.comment`, normalized and validated in one function (trim, control-character runs to one space, at most 500 code points; design decision 10). Carried over to a new version in `LocalPhotoSource` and `RemoteSource` like `hidden`. Verify:
  - Tests for every scenario of "Comment of a photo".
  - Tests for "Comment when the file changes" › "File replaced" (local and remote) and "File moved".
  - A test with a 500-character text of emoji (code points, not UTF-16 units).
- [ ] 5.2 `PATCH /photos/{photoId}` accepts `comment`. `Photo` has `comment` and `location`, with `place` in the frame's language from `PlaceIndex`. A too long comment rejects the whole patch. Verify:
  - `TestHttp(..., checkContract = true)` tests for "Comment too long" › "501 characters".
  - The same for "Comment and location in the photo list" › "Photo with comment and position" and "Set only the comment".
  - The same for "Comment on a Nextcloud photo" against `FakeNextcloud` (no write request reaches it).
  - `RouteAccessTest` passes.

## 6. Caption and settings in `:core`

- [ ] 6.1 `Corner` in place of `ClockPosition`, and `CaptionSettings` in `Settings` with the defaults and the patch fields `caption.albumTitle`, `caption.place`, `caption.position` (design decision 9). Verify:
  - Tests for every scenario of "Caption settings", including a stored `settings.json` from before the change and the 400 naming `caption.position`.
  - A test that a stored clock position loads unchanged.
- [ ] 6.2 `CaptionText.of` and `FrameCore.caption` as a `StateFlow` combining slide state, the current entry, settings and language (design decision 7). Verify:
  - Unit tests for every scenario of "Caption over the photo" and "Album title in the caption", with album paths from `nested-albums`.
  - A `TestFrame` test that an edited comment of the photo on screen, a switched-on place and a changed language reach `caption` without a new `seq` ("Caption changes without a photo change").

## 7. Caption on the frame

- [ ] 7.1 `SlideRenderer` reports the start of each swap with `seq`, whether it animates, and the duration. `CaptionOverlay` with two cross-fading views (text line with at most 2 lines, place line with 1 line, ellipsis, at most 60 % of the width, the clock's shadow) switches only on that report; with the same `seq`, it replaces the text in place (design decision 8). Verify:
  - Robolectric tests that a caption switches at the swap and not at the state change.
  - Robolectric tests that `none` and the instant fallback switch at once.
  - A Robolectric test for "Length of the caption" › "Long comment" (the place line stays visible).
- [ ] 7.2 Corner, alignment and stacking: the caption in its corner, aligned to its side, and a pure function `cornerOffsets` for the shared corner, fed by a layout listener on the clock. The caption is hidden together with the clock in `setHintVisible`. Verify:
  - JVM tests of `cornerOffsets` for all corners, with the clock on and off, and at 50 % and 300 %.
  - Robolectric tests for "Corner of the caption" › "Caption top right" and "Caption and hint" › "Tap on the frame".
  - `./gradlew :android:lintDebug` without new warnings.
- [ ] 7.3 New rows in `docs/DEVICE_TEST.md`, each with its spec scenario:
  - Legibility on a bright and a dark photo (slideshow-display › Caption over the photo).
  - Clock at 300 % in portrait sharing the corner (› Caption and clock in the same corner › Large clock in portrait).
  - Crossfade without a caption over the wrong photo (› Caption changes with the photo › Crossfade).
  - The `TransitionGovernor` measurement repeated with a caption shown (design, Risks).
  - `dumpsys meminfo` before and after the first place lookup on the Frameo (design, Risks).
  - Duration of the backfill for 1,000 photos on the Frameo (photo-library › Metadata of photos already in the index).

  Verify: the rows exist with their references.

## 8. Web UI

- [ ] 8.1 API client with `comment`, `location` and `caption`. In `PhotoDetail`: a comment `textarea` with a counter of code points, a save action, the error at the field from `fields.comment` (text kept), and the place with coordinates at four decimals, with no map. Texts in `en.json` and `de.json`. Verify:
  - e2e tests for every scenario of "Edit the comment" and "Location in the detail view", including a Nextcloud photo.
  - A check that no request goes to another host.
  - The i18n test that both languages have the same keys passes.
- [ ] 8.2 `SettingsView`: the caption group (two switches, the corner control shared with the clock) and the GeoNames attribution with CC BY 4.0 as text next to the place switch. Verify: e2e tests for "Attribution of the place data" › "Settings open" (offline) and for saving the caption settings, and an error at `caption.position` shown at the field.
- [ ] 8.3 Run `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e` in `webadmin/`. Verify: all green.

## 9. Devices

- [ ] 9.1 Run the rows of 7.3 on the Lenovo, the Frameo and the Galaxy Tab S7. Ask before installing the build, before uploading test photos with GPS data, and before deleting them afterwards. Read-only `adb` commands need no question. Verify: results entered with the date, and a finding in `docs/FINDINGS.md` for anything that differs.

## 10. Integration

- [ ] 10.1 Run `./gradlew check`. Verify: green (formatting, tests, API 21 check, Android Lint).
- [ ] 10.2 Update `README.md` with the caption, the offline place names and the GeoNames attribution. Verify: the README names GeoNames and CC BY 4.0, and says that no place lookup leaves the frame.
- [ ] 10.3 Run `openspec validate photo-captions`. Verify: exit 0 without warnings.

## Workflow follow-up

- Start the implementation only after `nested-albums`, `multi-source-share-links` and `photo-crop` are merged (design.md, Migration Plan).
- Archive the change after the device rows of 7.3 are done.
