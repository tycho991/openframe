# Tasks

## 1. Project skeleton and tooling

- [x] 1.1 Pin JDK 17 (Temurin) in `.mise.toml`, create the Gradle wrapper, `settings.gradle.kts` with `:core` and `:android`, and `gradle/libs.versions.toml`; verify: `mise install && ./gradlew help` runs through
- [x] 1.2 Create module `:core` as a Kotlin/JVM library (Kotlin 2.x, toolchain 17, `kotlinx-coroutines`, `kotlinx-serialization`, JUnit 5) with a placeholder test; verify: `./gradlew :core:test` green
- [x] 1.3 Hook a Gradle check against `android.*` imports in `:core` into `check`; verify: a deliberately inserted import makes `./gradlew :core:check` fail with an understandable message
- [x] 1.4 Create module `:android` with package ID `io.github.tycho991.openframe`, `minSdk 21`, newest stable `targetSdk`, `largeHeap` and an empty activity; verify: `./gradlew :android:assembleDebug` produces an APK, `aapt dump badging` shows package ID and `minSdk 21`
- [x] 1.5 Set up Spotless/ktlint and Android Lint, including the check that no API above `minSdk` is used; verify: `./gradlew check` green, a style violation makes it fail
- [x] 1.6 Create `webadmin/` with Vite, Svelte 5, TypeScript, ESLint, Prettier and Vitest, check in `package-lock.json`; verify: `npm ci && npm run lint && npm test && npm run build` in `webadmin/` green
- [x] 1.7 Add the GitHub Actions workflow `ci.yml` (Gradle `check` + `assembleDebug`, web UI lint/test/build) and Renovate rules for Gradle and npm; verify: workflow runs green on a push branch

## 2. API contract and HTTP layer

- [x] 2.1 Write `api/openapi.yaml` for `/api/v1` with all endpoints from design.md (decision 6), error format and auth schema; verify: schema lint (`redocly lint` or similar) without errors
- [x] 2.2 Build the routing layer in `:core` on NanoHTTPD: typed `suspend` handlers, JSON through `kotlinx.serialization`, uniform error responses (400/401/403/404/413/415/429/500/507), bounded dispatcher; verify: tests against a real `ServerSocket` for routing, error mapping and unknown paths
- [x] 2.3 Implement security headers and origin/`Sec-Fetch-Site` check for mutating methods (spec `admin-access`: Security headers); verify: test "Foreign page" yields 403, all responses carry the headers
- [x] 2.4 Contract test harness: validate every response of a test against `api/openapi.yaml` (JSON schema validator on the test classpath); verify: a deliberately wrong field makes the test fail
- [x] 2.5 Static serving through `AssetSource` with SPA fallback to `index.html` and CSP for the web UI; verify: test serves `index.html` for `/` and `/photos`, assets with the correct MIME type

## 3. Settings and identity

- [x] 3.1 Atomic JSON storage (`tmp` + `fsync` + `rename`, single writer, batching) as `JsonStore`; verify: tests for round trip, interrupted write (temp file stays behind → old state applies) and parallel writers
- [x] 3.2 Settings model with defaults, merge over defaults and per-field validation (spec `frame-settings`), including orientation (`landscape`/`portrait`), language on the frame and cache budget (100–10,000 MB, default 1,000 MB); "local only" is stored along but is not a field of the API; verify: tests for "First start", "Update with a new setting", "Invalid interval", "Unknown field"
- [x] 3.3 `GET`/`PATCH /api/v1/settings` with partial changes, settings version and a `StateFlow` for the display; verify: contract tests and scenario "Change only the interval"
- [x] 3.4 Identity (random ID on first start), name as a setting with default from `PlatformInfo` and `GET /api/v1/info` without auth; verify: tests "Fetch info" and "Change name" (through `PATCH /api/v1/settings`), ID stays the same across restart

## 4. Pairing and access

- [x] 4.1 Pairing code (6 digits, 5 min, single use, 5 failed attempts → 60 s lockout), `POST /api/v1/pairing` with device name (fallback from user agent) and `POST /api/v1/pairing/code` (without auth, 204, shows a valid code on the frame through a `StateFlow` for 5 min or until pairing); a displayed code is replaced when it expires; verify: tests with a fake `Clock` for success, expiry, single use, lockout (429), "Request code on the frame" (response contains no code) and replacing a displayed code after 5 min
- [x] 4.2 Token storage as SHA-256, auth filter for all protected routes, `GET/DELETE /api/v1/clients` with marking of the requesting device; verify: tests "Without token" (401), "With token" (200), "Revoke pairing" (401 immediately); a test lists all routes and ensures that only the approved ones are reachable without a token
- [x] 4.3 Signed, short-lived image URLs (`?t=`, HMAC over photo ID and expiry, server secret, expiry rounded to the full hour after next) as an alternative to the token for image requests only; verify: tests for valid, expired and transferred-to-another-photo signatures (scenarios "Signed image URL", "Signature reused for another photo") and the same URL within an hour
- [x] 4.4 "Revoke all pairings" (including a new signature secret) and binding to `127.0.0.1` in "local only" mode as core functions, not reachable through the API; verify: test that after the reset all tokens and old image URLs yield 401 and a new code is created; test that the server in "local only" mode does not accept on a LAN address; scenario "Via the API" (400)

## 5. Photo library

- [x] 5.1 `MediaEngine` interface and JVM fake (ImageIO + `metadata-extractor`) for tests; fixture photos with EXIF orientation 1/6/8, PNG, WebP, GIF and a broken file under `core/src/test/resources`; verify: fake reads dimensions correctly after rotation
- [x] 5.2 `PhotoIndexStore` (index in memory, persisted in batches, library version, `StateFlow` of the visible photos); verify: tests for round trip, version change on modification and restart
- [x] 5.3 `PhotoSource` `local`: scan every 15 s, mtime/size shortcut, ID from path, image version, album from first folder, format filter, HEIC only after a successful probe decode, list of skipped files with reason and `GET /api/v1/skipped-files` (spec `photo-library`); verify: tests for "Mixed folder", "HEIC on an old device" (fake MediaEngine without HEIC), "Rescan without changes" (no opening, not even of the skipped files), "File replaced", "File removed", "Assign album"
- [x] 5.4 Visibility of photo and album, including hidden albums for new photos; `PATCH /api/v1/photos/{id}`, `GET`/`PATCH /api/v1/albums` (with count and cover image), `POST /api/v1/photos:batch`; verify: contract tests and scenario "Hidden album receives a new photo"
- [x] 5.5 `GET /api/v1/photos` with filter, sorting, cursor pagination and signed image URLs per entry (from 4.3); verify: contract test "Fetch page" and a test with 10,000 synthetic entries under 50 ms per page on the JVM
- [x] 5.6 Upload (multipart, streaming to disk, sanitizing of file and album names, unique names, format check, check for at least 100 MB remaining storage, immediate index entry, notification to `MediaStoreNotifier`); verify: tests "Upload into album", "Path traversal" (file and album name), "Same name", "Unsupported format" (415), "Storage full" (507, no partial file) and an upload of 300 MB with a limited test heap
- [x] 5.7 Delete including the derived sizes (`DELETE /api/v1/photos/{id}`, batch) and notification to `MediaStoreNotifier`; verify: test "Delete photo", fake notifier has received the file
- [x] 5.8 Image size cache: thumbnail 400 px and screen-covering display size for the configured orientation (at most 2.5 times the screen's pixel count), cache key from ID, image version and target dimensions, one render worker, rendering the next photos ahead, on first start the first photo of the order first, ETag/304, `Cache-Control: immutable`, LRU budget from the settings; verify: tests "Fetch thumbnail", "Portrait photo on a landscape frame" (1920×2560), panorama at the upper limit, "Repeated request" (304 without rendering), "Budget exceeded" (originals untouched)

## 6. Android host

- [x] 6.1 `AndroidMediaEngine`: `BitmapFactory` with `inSampleSize` in two passes, rotation from `ExifInterface` (framework class, not AndroidX), JPEG with quality 85, GIF as first frame, HEIC probe; verify: Robolectric tests with the fixture photos from 5.1
- [ ] 6.2 Foreground service with `:core`, HTTP server, `PARTIAL_WAKE_LOCK`, `WifiLock`, notification channel from API 26 and `START_STICKY`; local binder with the `StateFlow`s; verify: on the emulator (API 21 and current) `curl http://<ip>:8080/api/v1/info` is reachable, even when the activity is in the background
- [ ] 6.3 `BootReceiver`, HOME intent filter, immersive fullscreen with restoration, `FLAG_KEEP_SCREEN_ON`; verify: emulator restart shows the app without intervention; `adb shell cmd package resolve-activity -c android.intent.category.HOME` offers it
- [ ] 6.4 Self-healing (`UncaughtExceptionHandler` → log + `AlarmManager` restart) and rotating file log with `GET /api/v1/logs`; verify: a debug command that triggers a crash leads to a restart within 30 s, and the log shows the cause
- [ ] 6.5 `PlatformInfo` (model, Android version, resolution, IP addresses) and rebinding of the server after a Wi-Fi change; verify: on the emulator Wi-Fi off and on, afterwards `/api/v1/info` is reachable again and the displayed address is correct
- [ ] 6.6 `MediaStoreNotifier` for Android with `MediaScannerConnection.scanFile` for created and deleted files; verify: on the emulator after an upload `adb shell content query --uri content://media/external/file` shows the file, after deletion not any more (scenario "Uploaded photo over USB"; real MTP test in `docs/DEVICE_TEST.md`)

## 7. Native slideshow

- [x] 7.1 Order logic in `:core` (`shuffle` without repeats within a pass, `chronological`, wrap-around, forward/back, reaction to library changes, deleted current photo); verify: JVM tests for all scenarios on order, wrap-around and changes from spec `slideshow-display`
- [ ] 7.2 Display activity with two `ImageView`s, decode thread, at most two bitmaps, preloading the next photo, skipping broken or not yet rendered photos (waiting only as long as none is rendered yet), start only once the core is ready (not the HTTP server); orientation through `setRequestedOrientation` (`sensorLandscape`/`sensorPortrait`) without restart, with the old display size until re-rendered; verify: emulator shows fixture photos at the configured interval, the broken file is skipped; scenario "Cold start" with Wi-Fi off and empty cache; scenario "Switch to portrait"
- [ ] 7.3 Transitions `fade`/`slide`/`none` with `ViewPropertyAnimator` (without `withLayer()`, see design.md, decision 2), duration from the settings; fill modes `fill`/`fit` through the matrix; verify: scenario "Transition changed" without restart; scenarios "Crossfade", "Portrait with fill/fit" visually on the emulator (screenshots in `docs/`)
- [ ] 7.4 Clock overlay (position, size, 12/24 h, date language, shadow), minute change through `ACTION_TIME_TICK`; verify: scenarios "Minute change" and "Clock off" without restart
- [ ] 7.5 Gestures and hint overlay (swipe forward/back, tap → pause/resume, name, address, valid pairing code), empty state and display of the code requested through the API; verify: scenarios "Swipe back", "Pause", "New frame" on the emulator
- [ ] 7.6 Device menu (3 s long press in the corner): "local only" on/off, "Revoke all pairings" with confirmation, info; verify: scenarios "Turn off on the frame" and "Access lost" on the emulator
- [ ] 7.7 Shared theme (typography, colors, spacing) for overlay, hint, empty state and device menu; all texts on the frame as string resources in German and English, chosen through the setting "Language on the frame" without restart; verify: screenshots of all four states at 1024×600 and 1920×1200 in `docs/`, once each with `de` and `en`

## 8. Admin interface

- [x] 8.1 Generate TypeScript types from `api/openapi.yaml` (`openapi-typescript`), API client with token, 401 handling and signed image URLs (reload loaded pages after 50 min at the latest, reload the page once on an image error); verify: `npm run typecheck`, Vitest for 401 → pairing dialog and scenario "Tab open for hours" with fake timers
- [x] 8.2 Gradle task builds `webadmin/` before `mergeAssets` into the APK assets and fails clearly without Node; verify: `./gradlew :android:assembleDebug` contains `assets/webadmin/index.html`; without Node the error message appears
- [x] 8.3 App shell: layout for phone and desktop, navigation (photos, albums, settings, devices), CSS variables, light and dark, i18n German/English with detection through the browser and choice per browser in `localStorage`; Playwright projects for Chromium, Firefox and WebKit; verify: Playwright at 360 px, 390 px and 1280 px without horizontal scrolling; scenario "German browser"; scenario "Offline on the home network" (network capture shows no requests to foreign hosts)
- [x] 8.4 Pairing dialog with device name, "Show code on frame" (`POST /api/v1/pairing/code`) and errors (wrong, expired, lockout); verify: Playwright against a `:core` test server for "First visit" and "Returning visit"
- [x] 8.5 Photo grid with virtual list, cursor pagination, filter by album and visibility, marking of hidden photos, detail view; hint and list of skipped files; verify: Playwright with 10,000 fake photos, only thumbnails in the visible area are requested (network capture); scenario "HEIC over USB on an old device"
- [x] 8.6 Multi-selection with hide, unhide and delete (with confirmation); verify: Playwright "Hide several" and "Confirm delete"
- [x] 8.7 Upload by file selection and drag and drop with progress and errors per file and album choice (existing or new album); verify: Playwright "Partial failure"
- [x] 8.8 Album view with count, cover image and switch; verify: Playwright "Hide album"
- [x] 8.9 Settings form for all API settings (including name, orientation, language on the frame and cache budget, without "local only") with errors at the field and confirmation without reloading; verify: Playwright "Error at the field"
- [x] 8.10 Device list with marking of the current device and revoking a pairing; verify: Playwright "Remove old device"
- [x] 8.11 Refresh through `GET /api/v1/state` (polling every 10 s, only when the tab is visible); verify: Playwright "Photo over USB" (create a file in the test server's folder → appears within 30 s)

## 9. Documentation

- [x] 9.1 Write the README: short description, architecture in one paragraph, build (`mise install`, SDK path, `./gradlew :android:assembleDebug`), installation by `adb install`, first pairing; verify: the commands work in a fresh clone as described
- [x] 9.2 Create `docs/DEVICE_TEST.md` as a checklist for real hardware (frame drops via `dumpsys gfxinfo`, endurance run 24 h at 5 s, 48 MP photo, autostart, Wi-Fi outage, upload visible on the computer over USB, side-by-side operation with `pictureframe`, way back with photo backup); verify: every row refers to its spec scenario
- [x] 9.3 Create `CLAUDE.md` with the project rules (module boundaries, contract tests, device access with a reference to `../pictureframe/devices/`, confirmation before persistent changes on the device); verify: file exists and refers to existing paths

## 10. Acceptance on the devices

- [ ] 10.1 Lenovo Yoga Tablet 2: install the APK in parallel to `pictureframe` and work through `docs/DEVICE_TEST.md` completely (after consultation, since it installs a package on the device); verify: all items ticked, measurements entered
- [ ] 10.2 Frameo: install the APK in parallel, check slideshow, pairing, upload and endurance run, `pictureframe` remains the HOME launcher (after consultation); verify: checklist ticked without the HOME switch

## Workflow follow-up

- Propose follow-up changes: Nextcloud sync, video, crop editor, screen schedule/brightness, Home Assistant, multiple frames (mDNS), self-update.
- Archive the change with `/opsx:archive` after review and green acceptance.
