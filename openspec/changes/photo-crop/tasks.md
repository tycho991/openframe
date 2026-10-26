# Tasks

## 1. Contract first

- [x] 1.1 `api/openapi.yaml`: schema `Crop` (`x`, `y` 0 to 1, `zoom` exclusive minimum 0, maximum 4) and `Presentation` (`x`, `y`, `width`, `height`, `zoom`); `PhotoPatch` with optional `hidden` and `crop` (`Crop` or null), `minProperties: 1`; `Photo.crop`, `Photo.presentation`, `ImageUrls.full`; `size` enum `thumb|display|full` and the parameter `p` on `GET /photos/{photoId}/image`; `FillMode` with `auto`; the `patchPhoto` summary no longer says only "Hide or unhide"; verify: `npx @redocly/cli lint api/openapi.yaml` passes
- [x] 1.2 Run `npm run gen:api` in `webadmin/`; verify: `webadmin/src/lib/api/schema.d.ts` has `Crop`, `Presentation` and `auto`, and `git diff --stat` shows only generated changes there
- [x] 1.3 Shared table `api/fixtures/presentation-cases.json` (photo size, screen, section or null, fill mode → window `x, y, width, height` and zoom) with every number of the spec scenarios in `slideshow-display` and `photo-library` (3000×4000, 4000×3000, 6000×2000, 4800×3000, 8000×6000 on 1920×1200 and 1200×1920, a photo smaller than the screen, a focus point in a corner); verify: the file is valid JSON and every case cites its spec scenario in a `scenario` field

## 2. Presentation and fill mode in `:core`

- [x] 2.1 `Presentation.of` in `core/.../library/` per design decisions 1 and 2, with `AUTO_MAX_CROP = 0.25`, the zoom range and the output size; verify: a test reads `api/fixtures/presentation-cases.json` and every case matches within 1/10,000, plus tests for clamping at all four edges and for a stored zoom above the largest one
- [x] 2.2 `FillMode.AUTO` with `@SerialName("auto")`, default of `Settings.fillMode` set to `AUTO`; verify: tests for "Default fill mode" › "New frame" and "Update of a frame in fill" (a stored `settings.json` with `fill` loads as `fill`), and that `SettingsPatch` accepts `auto` and rejects `cover` with the field `fillMode`
- [x] 2.3 Inverse EXIF mapping of a normalized region from rotated to stored coordinates as a pure function in `core/.../platform/`, for the Android engine; verify: unit tests for all eight EXIF orientations with an asymmetric region, and `./gradlew :core:checkNoAndroidImports` passes

## 3. Rendering

- [x] 3.1 `MediaEngine.render` with a `region` (default the whole image); the JVM fake in `core/src/test/.../testing/` crops with `getSubimage` and records the region; verify: existing render tests pass unchanged and a fake test renders the top left quarter of a test image with distinct quadrant colours
- [x] 3.2 `RenderCache`: display size from `Presentation` with the window key in the file name (design decision 6), screen and fill mode as suppliers, size `full` (longest edge 2048 px, uncropped), `peekAnyDisplay` skipping thumbnail and full size; `RenderSizes.display` and `MAX_PIXEL_FACTOR` removed; verify: tests for "Rendered image sizes" › "Portrait photo on a landscape frame" and "Portrait photo with borders", for a new file after a section or fill mode change, and that `peekAnyDisplay` returns the older display size until the new one exists
- [x] 3.3 `AndroidMediaEngine.render` with `BitmapRegionDecoder` on the region mapped by 2.3 and an `inSampleSize` from region and output size, falling back to a sampled full decode and a crop for GIF and on any failure; verify: Robolectric tests in `AndroidMediaEngineTest` for a region of a JPEG with EXIF orientation 6, for the fallback with a GIF, and that the decoded bitmap of a 8000×6000 JPEG at zoom 4 is no larger than twice the output size in each direction
- [x] 3.4 `SlideRenderer` shows every photo with `FIT_CENTER` on black, `fillMode` no longer touches the views, `BitmapFit.forView` only scales down; verify: `BitmapFitTest` adapted and passing, and `./gradlew :android:lintDebug` without new warnings

## 4. Section in the library and the API

- [x] 4.1 `PhotoEntry.crop: Crop?` stored in the index only when set; set and removed through `PhotoLibrary.update`; `LocalPhotoSource` and `RemoteSource` carry `crop` over to a new version if the aspect ratio is equal within 1 %; verify: tests for "Section when the file changes" › "Edited photo of the same size" and "Photo cropped elsewhere" for a local photo and a photo of a remote source, and that an index without `crop` loads with no sections
- [x] 4.2 `PATCH /photos/{photoId}` with optional `hidden` and `crop`, errors at `crop.x`, `crop.y`, `crop.zoom`, an empty body 400; `Photo` with `crop`, `presentation` and `urls.full`; the signed display URL with `p`, the ETag with the window key; `size=full`; verify: `TestHttp(..., checkContract = true)` tests for every scenario of "Section of a photo" and "Section in the photo list" in `photo-library`, a patch of only `hidden` as before, and `RouteAccessTest` passes
- [x] 4.3 The slideshow takes up a new section and a new fill mode at the next change; verify: a `TestFrame` test sets a section and sees the next display file rendered with its region, and one switches `fillMode` to `fit` and sees the next file with the full photo
- [x] 4.4 Run `./gradlew check`; verify: green, including `checkNoAndroidImports`, the API 21 check and Android Lint

## 5. Web UI

- [x] 5.1 API client: `patchPhoto` with `crop`, the types of `Crop` and `Presentation`; `webadmin/src/lib/presentation.ts` as the client copy of `Presentation.of`; verify: a Vitest test reads `api/fixtures/presentation-cases.json` and every case matches within 1/10,000, and `npm run typecheck` passes
- [x] 5.2 `PhotoDetail` shows `urls.display` in a box of the frame's aspect ratio (`GET /info` → `screen`, swapped for `portrait`) on black at the place of `presentation`, and reloads the open photo when the settings version changes; verify: e2e test "Detail view of a portrait photo" of `web-admin` passes, and one that switches the fill mode with the detail view open sees the new part
- [x] 5.3 `SectionEditor` per design decision 9 (viewport, drag, pinch, wheel, −/+ buttons, zoom range from `presentation.ts`, "Automatic", "Save", "Cancel"); texts in `en.json` and `de.json`; `auto` in the fill mode choice of `SettingsView` with its texts; verify: e2e tests for every scenario of "Choose a section" and "Section editor on the phone" (viewport 390 px, buttons at least 44 px, no horizontal scrolling), and the i18n test that both languages have the same keys passes
- [x] 5.4 Run `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e` in `webadmin/`; verify: all green

## 6. Devices

- [x] 6.1 Rows in `docs/DEVICE_TEST.md`, each with its spec scenario: a zoomed detail sharp on the Lenovo (slideshow-display › Section of a photo › Zoom on a detail), a 48 MP photo at the largest zoom on the Frameo (photo-library › Rendering a section within memory), a progressive JPEG zoomed on the Lenovo (design, Risks), the section editor on a phone (web-admin › Section editor on the phone); verify: the rows exist with their references
- [x] 6.2 Run the rows of 6.1 on the devices; ask before installing and before any change on a device; verify: results entered, a finding in `docs/FINDINGS.md` for anything that differs

## 7. Integration

- [x] 7.1 `./gradlew check`; verify: it ends green (formatting, tests, API 21 check, Android Lint)
- [x] 7.2 `openspec validate photo-crop`; verify: exit 0 without warnings

## Workflow follow-up

- Start the implementation only after `nested-albums` and `multi-source-share-links` are merged (design.md, decision 10).
- Archive the change after the device rows of 6.1 are done.
