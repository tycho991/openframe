# openframe

Digital picture frame for old Android tablets and Frameo frames. The slideshow
runs natively on the device and is managed in the browser of a laptop or phone.
There is no cloud service of its own: photos get onto the frame by USB, by upload
or from a Nextcloud folder, and the frame itself is the server.

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

Background and decisions: `openspec/changes/archive/2026-10-20-bootstrap-openframe-core/design.md`.

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
   example `http://192.168.100.215:8080/`, a QR code and a six-digit pairing
   code.
2. Scan the QR code with the phone's camera app and open the link. The admin
   interface opens and pairs by itself, without typing anything. The device
   gets a name from its browser ("Safari on iPhone"), which "Rename" changes.
3. Upload photos or copy them over USB into the folder
   `Android/data/io.github.tycho991.openframe/files/photos`.
   Subfolders become albums.

The code is the fallback for devices without a camera: open the address in a
browser and enter the code below the QR code. QR code and code are valid for
5 minutes and only once; "Show code on frame" in the pairing dialog brings up
a new one. After five wrong codes the code is locked for a minute, the QR code
keeps working.

Further devices do not need the frame: on a paired device, "Paired devices" ›
"Invite device" shows a QR code and a link that pair one more device once,
within 5 minutes.

The token lives in the browser per address. If the frame gets a new IP address,
every browser has to pair again, so give the frame a fixed address (DHCP
reservation) in the router. On an iPhone, a web app added to the home screen
has storage of its own and is not paired, even if Safari is. It has no address
bar, so links do not reach it: pair it with the code ("Show code on frame").

On the frame itself: a tap shows name, address, code, QR code and pause. Swiping pages
through the photos. Pressing a corner for three seconds opens the device menu
with "local only", "Revoke all pairings" and info.

## Photos from Nextcloud

Next to the local photo folder the frame can show a folder of a Nextcloud server. The frame only reads it; nothing
is changed on the server.

1. Open "Nextcloud" in the admin interface, enter the address of the server and choose "Connect". A Nextcloud page
   opens in a new tab; approve the access there. The frame receives an app password, so no password is typed into
   the frame or the interface.
2. Choose the folder, whether subfolders are included (the first one is the album) and how often the frame syncs
   (every 20 minutes by default, at least 5). "Sync now" starts a sync at once; the status shows the result.
3. Photos from Nextcloud are hidden or shown like local ones, but not deleted: they would come back with the next
   sync. "Disconnect" removes them from the frame again and revokes the app password on the server.

Where things are stored: the app password only in the app's private storage (`nextcloud.json`, never in an API
answer or the log), the downloaded originals in the app's cache folder, next to the rendered sizes, with a budget of
their own (2,000 MB by default). When it is exceeded, the originals unused for the longest time are removed and
fetched again when a rendering needs them. A photo stays on the frame without its original.

An address with `http://` sends the access without encryption; the interface says so. With `https://` the frame
trusts what the system trusts plus the root certificates delivered with the app, so that Let's Encrypt works on the
old devices (Android 5 and 6). A server with a certificate of another authority is refused.

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
