# openframe

Digital picture frame for old Android tablets and Frameo frames. The slideshow
runs natively on the device and is managed in the browser of a laptop or phone.
There is no cloud: photos get onto the frame by USB or upload, and the frame
itself is the server.

Target devices run Android 5.0 (API 21) or newer, tested on the Frameo `PFF_1024`
(Android 6.0.1) and the Lenovo Yoga Tablet 2 1050F (Android 5.0.1).

## Architecture

One codebase in Kotlin. `:core` contains all the business logic and runs on the
plain JVM: settings, photo library with index, image size cache, slideshow
control, pairing and the HTTP API under `/api/v1` (NanoHTTPD as transport).
`:android` is the kiosk APK: a foreground service holding the core, a native
display with two `ImageView`s and the binding to `BitmapFactory`. The display
receives its data directly as `StateFlow`s from the core, not over HTTP. The
admin interface in `webadmin/` (Svelte 5, TypeScript, Vite) is placed into the
APK at build time and served by the frame. The API contract is in
`api/openapi.yaml`; the web UI's types are generated from it, and contract tests
check every server response against it.

Background and decisions: `openspec/changes/bootstrap-openframe-core/design.md`.

## Building

Prerequisites: [mise](https://mise.jdx.dev) for JDK 17 and Node, plus an
Android SDK with platform 36.

```bash
mise trust && mise install
# Path to the Android SDK, if ANDROID_HOME is not set:
echo "sdk.dir=$HOME/android-sdk" > local.properties
./gradlew :android:assembleDebug
```

The APK ends up at `android/build/outputs/apk/debug/android-debug.apk`.
Without Node the build aborts with a message instead of building an APK without
the web UI. Without an Android SDK `:android` is skipped; `:core` and the web UI
can still be built and tested.

## Installing and first pairing

```bash
adb install -r android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n io.github.tycho991.openframe/.android.DisplayActivity
```

openframe runs in parallel to `pictureframe` (own package ID). To have it appear
by itself after a restart, choose it as the home screen (HOME) in the Android
settings.

1. Without photos the frame shows the address of the admin interface, for
   example `http://192.168.100.215:8080/`, and a six-digit pairing code.
2. Open the address in a browser and enter the code. The browser is paired
   afterwards; further devices pair the same way ("Show code on frame").
3. Upload photos or copy them over USB into the folder
   `Android/data/io.github.tycho991.openframe/files/photos`.
   Subfolders become albums.

On the frame itself: a tap shows name, address, code and pause. Swiping pages
through the photos. Pressing a corner for three seconds opens the device menu
with "local only", "Revoke all pairings" and info.

Uninstalling deletes the photo folder as well. Back it up first:
`adb pull /sdcard/Android/data/io.github.tycho991.openframe/files/photos`.

## Developing

```bash
./gradlew check                 # formatting, :core tests, Android imports, API 21, Android Lint
./gradlew :android:testDebugUnitTest
cd webadmin
npm ci
npm run lint && npm run typecheck && npm test
npm run e2e                     # builds the web UI and tests it with Playwright against a :core test server
OPENFRAME_URL=http://192.168.100.215:8080 npm run dev   # Vite with hot reload, API proxied to the frame
```

The `:core` test server with fake images can also be started with
`./gradlew :core:runTestServer --args="8090 8091 webadmin/dist"`; the web UI is
then at `http://127.0.0.1:8090/`.

What can be verified only on real hardware is listed in
[`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md).

## Workflow with OpenSpec

Changes are planned as an OpenSpec change (proposal, specs, design, tasks under
`openspec/changes/`) and then implemented. In Claude Code:

1. `/opsx:propose` for a new change
2. `openspec validate <name>` and `openspec show <name>`
3. `/opsx:apply` to implement
4. `/opsx:archive` or `openspec archive <name> --yes`

Node and OpenSpec are pinned in `.mise.toml`. Renovate raises the version by PR;
in the PR's branch then:

```bash
mise install
mise run openspec-sync
git add -A && git commit -m "Update OpenSpec skills"
```

The drift check in `.github/workflows/openspec-drift.yml` makes sure that the
committed skills and commands match the pinned version exactly.
