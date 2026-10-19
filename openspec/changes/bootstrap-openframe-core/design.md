# Design

## Context

Motivation and scope are in proposal.md, the requirements in the six specs of this change. These constraints matter for the architecture:

- **Target hardware**: Frameo `PFF_1024` (Android 6.0.1, armeabi-v7a, 1024×600) and Lenovo Yoga Tablet 2 1050F (Android 5.0.1, x86, 1920×1200, CA store from 2014). Both have little heap (48 to 96 MB per app) and a system WebView at the Chromium level of delivery.
- **Experience from `pictureframe`**: NanoHTTPD runs stably on both devices. Decoding with `inSampleSize` in two passes, rendering on a single worker thread and a foreground service plus wake/Wi-Fi lock have proven themselves there. It has also proven itself that the display loads only once the frame is ready. In openframe that means: once the core in the service is ready (decision 4). These findings are taken over, the code is not.
- **AndroidX** raised `minSdk` to 23 in 2025 for most libraries, including Jetpack Compose. The Lenovo (API 21) would drop out because of that.
- **Tooling**: Node and OpenSpec are pinned through `.mise.toml`, Renovate is set up. JDK and Android SDK currently live under `~/android-toolchain` and `~/android-sdk`.

## Goals / Non-Goals

**Goals:**

- One codebase in Kotlin. The business logic lives in `:core` and runs on the plain JVM so that it is testable without an emulator.
- The slideshow renders natively and is decoupled from the HTTP layer. It receives changes as a data stream and does not poll over HTTP.
- An explicit API contract from which the web UI's types are generated and against which the server responses are tested.
- The extension points for the follow-up changes are laid out now so that they can attach without rework: further photo sources (Nextcloud), media types (video), control (Home Assistant, schedule) and identity (multiple frames).

**Non-Goals:**

- Desktop or server build of `:core`. The JVM serves only for tests. A desktop module can be added later without changes to `:core`.
- Network discovery through mDNS/NSD, TLS and root features. These include real display-off, self-update and a status LED.
- Screen schedule, brightness, screen pinning and device owner.
- Reuse of code from `pictureframe`. Only findings and test cases are taken over.

## Decisions

### 1. Modules: `:core` (JVM) and `:android`, plus `webadmin/`

```
openframe/
  api/openapi.yaml     API contract (source of truth)
  core/                Kotlin/JVM: domain, storage, library, HTTP routes, auth
  android/             APK: activity (display), service (server), AndroidMediaEngine
  webadmin/            Svelte + TypeScript + Vite, build output into the APK assets
```

`:core` must not contain `android.*` imports. A Gradle check hooked into `check` verifies this. Platform-dependent things sit behind narrow interfaces in `:core`, implemented for real in `:android` and as JVM fakes in the tests:

- `MediaEngine`: dimensions, EXIF, rendering of image sizes
- `Clock`
- `PlatformInfo`: model, Android version, resolution, IP addresses
- `AssetSource`: the web UI's files
- `MediaStoreNotifier`: reports photo files the app created or deleted itself to the system so that they become visible over USB (see decision 7)

*Alternative:* Kotlin Multiplatform. Rejected because there is no second target frame without a JVM and KMP complicates build and tooling without a payoff.

### 2. Native slideshow with Android views instead of WebView or Compose

The display is its own `Activity` with two stacked `ImageView`s. The transitions run through `ViewPropertyAnimator` (`alpha`, `translationX`), deliberately without `withLayer()`: an `ImageView` without a background does not overlap itself and needs no layer for either. On the Frameo an explicit layer cost about every fifth vsync (missed vsyncs per crossfade: 20 to 22 % with, 2 to 7 % without). Clock and hint are ordinary views with a shadow for legibility. A shared theme with typography and colors gives the on-device interface a uniform, contemporary look. All texts on the device are available as string resources in German and English. Which language applies is determined by the setting "Language on the frame", `auto` follows the system language.

*Alternatives:*

- A modern web interface in the WebView, as in `pictureframe`. Rejected because the target devices' WebViews are too old, animations stutter and polyfills become necessary. That is exactly what is supposed to get better.
- Jetpack Compose. Rejected because it requires `minSdk 23` and measurably costs more than views on weak hardware.
- OpenGL. Rejected because two bitmaps with an alpha animation through the normal, hardware-accelerated view system reach the goal without a render pipeline of our own.

### 3. Image pipeline: pre-rendered, one image ahead

- `:core` renders a display size (JPEG, quality 85, EXIF-rotated) and a thumbnail of 400 px for every photo. Both end up in the cache. Rendering runs on a single low-priority worker thread. Rendering happens on demand and ahead of time for the next photos in the order.
- The display size is the smallest uncropped size that fully covers the screen in the configured orientation. This keeps `fill` sharp, and `fit` only scales down. A 3:4 portrait photo thus becomes 1920×2560 on 1920×1200. For extreme aspect ratios such as panoramas, an upper limit caps the size at 2.5 times the screen's pixel count. In that case `fill` is scaled up slightly. The cache key contains photo ID, image version and target dimensions. After a change of orientation the old entries drop out through LRU.
- The display decodes only the display size, without further scaling, on a thread of its own. It holds at most two bitmaps, the current and the next. A bitmap in ARGB_8888 takes about 23 MB in the worst case at 1920×1200 and about 6 MB at 1024×600, so two together take about 46 MB or 12 MB. `largeHeap` is set.
- The display handles `fill` and `fit` through the `ImageView`'s matrix. The rendered sizes are therefore uncropped. The crop editor will attach at exactly this point later.
- Orientation: the activity applies `sensorLandscape` or `sensorPortrait` from the setting through `setRequestedOrientation`, without a restart. After a change the render worker prioritizes the next photos in the new size. Until a new display size is ready, the display shows the old one scaled instead of skipping the photo.
- First start: if no display size is ready for any visible photo yet, the worker first renders the first photo of the order, and the display shows it as soon as it is ready. The empty state exists only when no photo is visible.
- `HEIC` is accepted only if `BitmapFactory` can decode it. That is the case from API 28 and is checked by a probe decode on import.

*Alternative:* Decode originals directly in the display. Rejected because at 12 to 48 MP that is too slow on these devices and blows the heap, as `pictureframe` showed.

### 4. In-process connection between display and core

The display binds to the foreground service and receives `StateFlow`s for settings and the visible library from `:core`. HTTP does not come into play. Changes from the web UI therefore take effect immediately. The display waits only for the core to have loaded settings and library, not for the HTTP server or the network. The library version and settings version in the API serve external clients only.

*Alternative:* The display polls its own HTTP API as in `pictureframe`. Rejected because that means latency, unnecessary work and a source of errors at cold start.

### 5. HTTP: NanoHTTPD behind a routing layer of our own, kept narrow

`:core` contains a small routing layer with typed handlers, JSON through `kotlinx.serialization`, error mapping, auth filter and security headers. NanoHTTPD serves only as transport, with range support and streaming upload to disk. Before an upload the handler checks the free storage based on `Content-Length` and once more while writing. If less than 100 MB remain free afterwards, it answers with 507 and removes the partial file. The handlers are `suspend` and run on a bounded dispatcher.

*Alternative:* Ktor Server (CIO). Rejected because it is not officially supported on Android, brings additional dependencies and the risk on API 21 on x86 is uncertain. NanoHTTPD, in contrast, is proven on exactly these devices. Because the transport sits behind our own layer, it can be replaced later.

### 6. API contract as an OpenAPI file

`api/openapi.yaml` describes `/api/v1`. The web UI's TypeScript types are generated from it (`openapi-typescript`). Contract tests in `:core` check real responses against the schema. The Kotlin DTOs are maintained by hand, the contract tests catch their deviation from the schema.

The endpoints at a glance (details are in the file):

| Area | Endpoints |
|---|---|
| Info, without auth | `GET /api/v1/info` |
| Pairing | `POST /api/v1/pairing`, `POST /api/v1/pairing/code` (show code on the frame, without auth), `GET/DELETE /api/v1/clients[/{id}]` |
| Settings | `GET /api/v1/settings`, `PATCH /api/v1/settings` (including the frame's name, without "local only", see decision 9) |
| Photos | `GET /api/v1/photos?album&visible&sort&cursor&limit` (entries with signed image URLs), `GET /api/v1/skipped-files`, `PATCH /api/v1/photos/{id}`, `DELETE /api/v1/photos/{id}`, `POST /api/v1/photos` (multipart), `GET /api/v1/photos/{id}/image?size=thumb\|display&v=&t=` |
| Albums | `GET /api/v1/albums`, `PATCH /api/v1/albums/{name}` |
| Batch | `POST /api/v1/photos:batch` (hide/show/delete for several IDs) |
| State | `GET /api/v1/state` (library and settings version) |
| Diagnostics | `GET /api/v1/logs` |

*Alternative:* Kotlin as the source and generate the schema from it. Rejected because there are no mature tools for that with `kotlinx.serialization` and a file as the contract is easier to review.

### 7. Storage: JSON files, atomic, index in memory

Settings, index, clients and identity live as JSON files. The identity contains only the immutable ID. The frame's name is an ordinary setting that `GET /api/v1/info` serves as well. Writing is atomic: temp file, `fsync`, then `rename`. The index is loaded completely at start and kept in memory. Write accesses are batched and flushed by a single writer after one second at most. With 10,000 photos that is about 3 MB of JSON and therefore uncritical. List, filter and sort work on pre-sorted lists in memory, paging uses a cursor.

The photos themselves live in `getExternalFilesDir("photos")`, which is reachable over USB without root. What the app creates or deletes there itself (upload, delete) is seen by a USB computer only after it has been reported to the MediaStore. For this `:core` calls `MediaStoreNotifier` after every such change, `:android` implements it with `MediaScannerConnection.scanFile`. Because the folder belongs to the app, Android deletes it on uninstall, see Migration Plan. Cache and state live in `filesDir` and `cacheDir` respectively. Tokens are stored only as a SHA-256 hash.

*Alternative:* SQLite (SQLDelight with Android and JVM drivers). Rejected for now because it brings a dependency, schema migrations and a driver choice, for data volumes that fit easily into memory. The index sits behind a `PhotoIndexStore` interface. If a follow-up change switches to SQLite, for example for multiple sources and large Nextcloud libraries, the rest remains untouched.

### 8. Scanning: periodic, with a shortcut through mtime and size

The photo folder is scanned every 15 seconds, immediately after an upload. The scan recognizes unchanged files by `mtime` and size and does not open them. The ID is `SHA-1(relative path)` with the source's prefix (`local-…`). Image version and content hang on `mtime` and size. `FileObserver` is unreliable for MTP copies on old devices and therefore serves at most as an accelerator.

Files with an image extension that cannot be accepted (HEIC without a decoder, damaged) are remembered by the scan with path, reason, `mtime` and size in a list of skipped files. It tries them again only when `mtime` or size change. `GET /api/v1/skipped-files` provides the list.

The source (`PhotoSource`) is an interface: `local` now, `nextcloud` later. Every source manages only its own entries in the index.

### 9. Access: pairing code, bearer token, origin check

- The code consists of six digits from `SecureRandom`, is valid for 5 minutes and only once. After 5 failed attempts it is discarded and a lockout of 60 seconds applies. The frame shows the code in the empty state, in the hint after a tap and on request from the web UI ("Show code on frame", `POST /api/v1/pairing/code`). The request answers with 204 and never returns the code. If no valid code exists, a new one is created when showing. After a request from the web UI the hint with the code stays for 5 minutes, or until a pairing succeeds. If the code expires while it is displayed, also in the empty state, the frame replaces it with a new one. During the lockout after failed attempts no code is created.
- When pairing, the client sends a device name along. If it is missing, it is derived from the user agent (e.g. "Firefox on Android"). `GET /api/v1/clients` marks the requesting device.
- The token consists of 32 random bytes in Base64url and is sent as `Authorization: Bearer`. The web UI stores it in `localStorage`. Because `<img>` cannot send headers, image URLs carry a signature `?t=` instead. Only the server issues it: the photo list delivers ready-made URLs for `thumb` and `display` with `v=` and `t=` per entry. Signing is done by HMAC over photo ID and expiry time. The key is a random server secret that "Revoke all pairings" regenerates. The expiry time is rounded to the full hour after next. That keeps a URL the same within an hour so that the browser cache works, and it is valid for one to two hours.
- For mutating methods `Origin` or `Sec-Fetch-Site` is checked. Foreign origins receive 403. In addition there are `X-Content-Type-Options`, `X-Frame-Options: DENY` and a strict CSP for the web UI.
- In "local only" mode the server binds to `127.0.0.1`. Because the display works in-process, it loses nothing as a result. The mode is stored together with the settings, but can be switched only through the menu on the device (decision 10) and is not part of `PATCH /api/v1/settings`. This way nobody can lock themselves out from the browser.

*Alternative:* PIN or password. Rejected because it gets forgotten, can be sniffed on the LAN and needs administration on the device. The code on the display, by contrast, proves physical proximity. However, neither solution offers protection against a sniffed token on the LAN without TLS, see Risks.

### 10. Operation on the device: hint overlay and device menu

A tap shows the hint: pause/resume, name, address and pairing code. A long press of 3 seconds in a corner opens a small native device menu with "local only" on/off, "Revoke all pairings" (with confirmation) and info. All other settings exist only in the web UI so that the device itself has nothing complicated to operate.

### 11. Kiosk lifecycle

- A `BootReceiver` starts service and activity. A HOME intent filter offers the app as a launcher.
- The service runs as a foreground service with a notification channel from API 26 and `START_STICKY`. It holds a `PARTIAL_WAKE_LOCK` and a `WifiLock`.
- The activity sets `FLAG_KEEP_SCREEN_ON` and immersive flags and brings fullscreen back on `onSystemUiVisibilityChange`.
- An `UncaughtExceptionHandler` logs the crash and schedules a restart in 5 seconds through `AlarmManager`.
- The package ID is `io.github.tycho991.openframe`, `minSdk 21`, `targetSdk` the newest stable version. The APK contains no native libraries and therefore runs on all ABIs.

### 12. Web UI: Svelte 5, TypeScript, Vite

Svelte produces small bundles without a runtime framework. That matters because the weak frame serves the files, and the load time over Wi-Fi depends on the size. The grid uses a virtual list of its own with cursor pagination and `loading="lazy"`. The signed image URLs expire after one to two hours. Therefore the web UI reloads loaded pages after 50 minutes at the latest, even if the library version has not changed. If an image fails anyway, it reloads its page once. i18n consists of two JSON catalogs without a library. The language follows the browser, a deviating choice is stored by the web UI per browser in `localStorage`. Styling uses CSS variables and own components, without a UI kit. Testing uses Vitest for the logic and Playwright (Chromium, Firefox, WebKit) against a `:core` test server with a fake MediaEngine.

Gradle calls the Vite build as a task before `mergeAssets`. Node comes from `.mise.toml`. The build output is not checked in.

*Alternatives:* React or Vue. Rejected because they produce larger bundles and bring advantages that do not count here. Preact would be equivalent, Svelte has less boilerplate when writing forms and animations.

### 13. Tooling and quality

- Gradle Kotlin DSL with version catalog (`gradle/libs.versions.toml`), Kotlin 2.x, JDK 17 as toolchain. The JDK comes through `.mise.toml`, the Android SDK path is documented.
- Formatting and lint: ktlint through Spotless for Kotlin, ESLint and Prettier for the web UI, Android Lint with `minSdk` checks.
- Tests: JUnit 5 and `kotlinx-coroutines-test` in `:core`, including contract tests and tests with a real `ServerSocket`. In `:android` there is Robolectric only for the image pipeline. A checklist for the devices (`docs/DEVICE_TEST.md`) covers what is measurable only on real hardware: frame drops, endurance run, autostart.
- CI on GitHub Actions runs `./gradlew check :android:assembleDebug`, plus web UI lint, tests and build. Renovate maintains the versions, OpenSpec's drift check remains.

## Risks / Trade-offs

- **[Token in cleartext on the LAN, no TLS]** → Pairing requires physical proximity. Tokens can be revoked and are stored only as a hash. The "local only" mode is the emergency switch. TLS with a self-signed certificate and pinning at pairing is conceivable as a follow-up change. Until then the same model as with many home-network devices is documented.
- **[HEIC missing on API < 28]** → Such files are shown in the web UI as "not supported" instead of silently missing. A conversion on upload in the browser through WebAssembly is a candidate for a follow-up change.
- **[Heap on old devices]** → The display decodes only pre-rendered, screen-covering sizes with an upper limit and holds at most two bitmaps, about 46 MB in the worst case on the Lenovo. If the heap is not enough there, RGB_565 for the waiting bitmap is the way out. Rendering runs on a single thread with `inSampleSize` in two passes. An endurance test on both devices is part of acceptance.
- **[First rendering of a large library takes time]** → Rendering happens on demand and ahead of time for the next photos, not everything at once. Until a display size is ready, the slideshow skips the photo instead of waiting. Exception: if none at all is ready yet, it waits for the first one (decision 3).
- **[OpenAPI file and Kotlin DTOs drift apart]** → Contract tests in CI check every route against the schema.
- **[NanoHTTPD is hardly maintained any more]** → The transport sits behind our own routing layer, a replacement affects one file. The used surface is small, and range and multipart are tested ourselves.
- **[Node in the APK build]** → Node is pinned exactly through mise, the lockfile is checked in and `npm ci` runs in CI. Without Node the build fails with a clear message instead of building an APK without the web UI.
- **[Uninstalling deletes the photos]** → The photo folder lives in `getExternalFilesDir` and thus belongs to the app. The way back in the Migration Plan therefore backs up the photos before uninstalling. A folder outside the app would need scoped-storage workarounds from API 29 and is left to a follow-up change.
- **[Whole index in memory]** → With 10,000 photos that is a few MB. For significantly more, the switch to SQLite is ready behind the interface.

## Migration Plan

1. The APK is installed on the Lenovo first, in parallel to `pictureframe`, which remains the HOME launcher. Photos arrive over USB or upload. After that the checklist in `docs/DEVICE_TEST.md` is worked through.
2. If it is green, openframe is chosen as the HOME launcher on the Lenovo. A 24-hour endurance run and a restart test follow.
3. After that the same follows on the Frameo. There `pictureframe` was removed during acceptance, ahead of the Nextcloud sync (follow-up change), so that openframe is the only HOME launcher. Removing it required its device admin to be deactivated first, which only the user can do in the Android settings. It was uninstalled with its data kept (`pm uninstall -k`), and the APK of version 1.2.0 was backed up. Until the Nextcloud sync exists, the Frameo therefore shows only photos added locally.
4. Way back: on the Lenovo, set the HOME launcher back to `com.pictureframe.kiosk` in the Android settings, or through `cmd package set-home-activity`, or through the chooser dialog. On the Frameo, `pictureframe` first has to be reinstalled from its backed-up APK (`adb install`) and its device admin enabled again by the user in the Android settings; only then it can be chosen as HOME launcher. Then back up the photos (`adb pull /sdcard/Android/data/io.github.tycho991.openframe/files/photos`), because uninstalling deletes them as well, and only after that uninstall openframe. openframe does not touch system files or root changes.

## Open Questions

- Should the release APK be signed with a key of its own instead of the debug keystore, as with `pictureframe`? That becomes relevant only with the self-update change and changes nothing here.
- License: do we take over MIT from `pictureframe`? That has no influence on specs or tasks.
