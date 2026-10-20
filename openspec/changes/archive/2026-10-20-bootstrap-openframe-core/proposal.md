# Proposal

## Why

`pictureframe` (v1.2.0) works, but it carries its history with it. The code exists twice: a Node server with about 1,900 lines and a Kotlin core with about 5,000 lines in the APK. The slideshow is a web app with about 8,000 lines of vanilla JS that has to run in the old WebViews of the target devices. Among other things that requires a `fetch()` polyfill, and it can neither animate smoothly nor offer a modern interface.

At the same time the most important step is already proven: the server runs on the device itself. openframe therefore rebuilds on this architecture, with only one codebase. The slideshow is rendered natively and hardware-accelerated, so it runs smoothly even on weak hardware. The admin interface becomes contemporary and runs in the browser of a laptop or phone instead of on the frame. Specs and tests form the contract from the start.

This first change lays the foundation and the core. It is meant to be cut so that Nextcloud, video, cropping, screen schedule, Home Assistant and multiple frames can build on it as separate, smaller changes.

## What Changes

- New project in Kotlin with coroutines and `kotlinx.serialization`. There is a platform-neutral module `core` without `android.*` imports and a module `android` for the kiosk APK. There is no Node server any more.
- **Native slideshow on the device** instead of a WebView: the images are shown with hardware-accelerated Android views, with a crossfade and a clock as overlay. Decoding is throttled and works with pre-scaled images so that memory is enough on devices with 1 GB RAM and API 21.
- **Local photo library**: photos arrive through a USB folder or upload. An index with stable IDs and album folders forms the basis on which Nextcloud and other sources can build later.
- **Embedded HTTP server** on the device. It serves a versioned JSON API under `/api/v1` and the admin interface.
- **Modern admin interface** with TypeScript, components and a build step, built for current browsers on laptop and phone. It offers a photo overview, upload, hiding and showing albums, the slideshow settings and management of the paired devices.
- **Access protection**: new is pairing by a code that is shown on the frame. After that a token manages access, instead of a completely open API on the LAN. In addition there is a switch on the frame that makes the server reachable locally only.
- **Kiosk operation**: the app is the HOME launcher, starts by itself after boot, runs in fullscreen, keeps the display awake and runs the server in a foreground service.
- **Identifier per frame**: every frame gets a unique ID and a name. This lets a later change for multiple frames build on it without rework.
- Tooling for the Gradle build, CI with tests for `core`, the web UI and the APK, and a check that `core` contains no Android imports.

Not part of this change and planned as separate follow-up changes:

- Nextcloud sync
- Video
- Crop editor
- Screen schedule and auto brightness
- Home Assistant
- Management of multiple frames
- Self-update
- Reverse geocoding
- Taking over the data from `pictureframe`

## Capabilities

### New Capabilities

- `slideshow-display`: Native display of the photos on the frame: order, interval, transitions, fill mode, orientation, clock overlay, operation on the frame with hint and device menu, empty state and memory behavior on weak hardware.
- `photo-library`: Local photos and albums. This includes import by folder and upload, index with stable IDs, detection of changes, list of skipped files, hiding, deleting and rendered image sizes with a storage budget.
- `frame-settings`: The frame's settings model with defaults, validation and persistence, including orientation, language on the frame and cache budget, plus the frame's identity (ID, name).
- `admin-access`: Reachability of the API on the LAN, pairing by code, tokens and the "local only" mode.
- `web-admin`: Admin interface in the browser for photos, albums, settings and paired devices.
- `kiosk-host`: Running the app as a kiosk on Android: autostart, HOME launcher, fullscreen, keeping the display awake and the server's lifecycle.

### Modified Capabilities

None. The project has no specs yet.

## Impact

- New repository `openframe` with `settings.gradle.kts` and the modules `:core` and `:android`, the API contract `api/openapi.yaml` and `webadmin/` for the Node build of the admin interface, whose result ends up in the APK assets.
- Dependencies: Kotlin, `kotlinx-coroutines`, `kotlinx-serialization`, an embedded HTTP server (see design.md) and a frontend toolchain via Node, pinned through `.mise.toml`.
- Target devices: Frameo `PFF_1024` (Android 6.0.1, armeabi-v7a) and Lenovo Yoga Tablet 2 1050F (Android 5.0.1, x86). This means `minSdk 21`.
- New package ID. This allows openframe to be installed and tested in parallel to `com.pictureframe.kiosk` before it replaces the old frame.
- The API is not compatible with the old `pictureframe` API. **BREAKING** applies only to tools built against the old API, for example the Home Assistant integration. It will be reconnected in a follow-up change.
