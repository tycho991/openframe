# openframe

Digital picture frame for old Android tablets and Frameo frames. The slideshow
runs natively on the device and is managed in the browser of a laptop or phone.
There is no cloud service of its own: photos get onto the frame by USB, by upload
or from Nextcloud (a share link or an account), and the frame itself is the server.

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
with "local only", the display schedule, "Revoke all pairings" and info.

## Display off at night

The frame can switch its display off and on by a schedule, with one window for weekdays and one for the weekend, set
in the admin interface under "Settings". The display is really off, as after the power button, with the backlight
off. The power button wakes it at night; it goes off again a few minutes after the last touch. "Turn display off" in
the settings switches it until the next switching time.

For this openframe has to be a **device administrator**. It asks for nothing but locking the screen, which is what
switching the display off is to Android. Activate it once on the frame: press a corner for three seconds, choose
"Display schedule: not allowed" and confirm the system's dialog with "Activate". From Android 6 on it also works
over `adb`:

```bash
adb shell dpm set-active-admin io.github.tycho991.openframe/.android.DisplayAdminReceiver
```

Android 5 (the Lenovo) knows no such command; use the device menu there.

- **Before uninstalling**, deactivate it: "Display schedule: allowed" in the device menu, or in the Android settings
  under Security › Device admin apps. Android does not uninstall an active device administrator, and `adb` cannot
  deactivate it. Deactivating switches the display on and keeps the schedule in the settings, where it applies again
  once device administration is back.
- **No screen lock.** Like starting as the home screen, this assumes that no PIN, pattern or password is set up;
  otherwise the lock screen would appear in the morning instead of the slideshow.

## Captions and place names

Every photo can carry a comment of up to 500 characters, written in its detail view in the admin interface. The
frame shows it as a caption over the photo; under "Settings" › "Caption" it can put the album title (the last level
of the folder) in front of it and the place in a line below, in a corner of its own. When the caption shares the
corner with the clock, it stands next to the clock towards the middle. Comments are part of the frame's state, like
the hidden state: they also work for photos of a remote source, nothing is written to the server or into the file,
and they stay when the file is replaced at the same path.

The place comes from the GPS position in the photo's EXIF data. The frame names the nearest place within 50 km as
"place, country", in the frame's language (German names from a German name pack, otherwise the dataset's own names).
**No place lookup leaves the frame**: the places (the GeoNames dataset `cities500`, about 237,000 places) ship in the
APK under `core/src/main/resources/places/`, about 2.7 MB. Photos that were on the frame before this version get their
position once, in the background.

Place names: [GeoNames](https://www.geonames.org/), licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); the admin interface shows the attribution next to the place
setting, and `SOURCE.txt` next to the data names the dumps it was built from. The data is regenerated on purpose, not on
every build:

```bash
# Dumps from https://download.geonames.org/export/dump/, into a folder outside the repository
python3 scripts/build-places.py cities500.zip alternateNamesV2.zip core/src/main/resources/places
```

## Photos from other sources

Next to the local photo folder the frame can show photos from up to eight remote sources at the same time: the link
of a shared Nextcloud folder, or a folder of a Nextcloud account. The frame only reads them; nothing is changed on
the server. Each source has its own settings, status, schedule and storage for downloaded originals, and adding,
syncing or removing one leaves the others as they are. Folders of the same name in different sources are one album.

Open "Sources" in the admin interface and choose "Add a source":

- **Share link**: paste the link that Nextcloud shows for a shared folder (`https://cloud.example.org/s/AbC123`, also
  with `/index.php/` or in a sub-path) and, if the share has one, its password. No account is needed. The frame reads
  the share once before it keeps it and syncs it at once; the shared folder is the folder of the source, and its name
  is the name in the list unless you give another. The share of
  a single file and a "file drop" share, which only accepts uploads, are refused.
- **Nextcloud account**: enter the address of the server and choose "Connect". A Nextcloud page opens in a new tab;
  approve the access there. The frame receives an app password, so no password is typed into the frame or the
  interface. Then choose the folder in the settings of the source; nothing is synced until one is chosen, `/` means
  the whole account. Logging in again to the same account renews its access and keeps its photos.

For each source: whether subfolders are included (the first one is the album), how often the frame syncs (every 20
minutes by default, at least 5), whether photos deleted on the server are removed, and the storage for its originals.
"Sync now" starts a sync at once; the status shows the result or a readable error, for example when the owner
withdrew the share or changed its password. Photos of a source are hidden or shown like local ones, but not deleted:
they would come back with the next sync. "Remove" takes the source and its photos off the frame and revokes the app
password of an account on the server.

Where things are stored: the app password, the share link and the password of a share only in the app's private
storage (`sources.json`, never in an API answer or the log), the downloaded originals in the app's cache folder,
next to the rendered sizes, with a budget per source (2,000 MB by default). When it is exceeded, the originals of that
source unused for the longest time are removed and fetched again when a rendering needs them. A photo stays on the
frame without its original. A download never leaves less than 100 MB free.

A frame that had a Nextcloud connection before (0.1.0) keeps it as the source `nextcloud`, with its photos, their IDs
and hidden states, without a new login. Its old `nextcloud.json` stays until that source is removed, so the older
build can still be installed again.

### Further providers

Each kind of source only brings its connection and a way to list and download a folder; sync, schedule, status and
storage are shared. These are prepared, not built, and each is a change of its own:

| Kind | What blocks it today |
|---|---|
| A plain WebDAV address (URL, user, password) | Nothing technical; the existing WebDAV code reads it |
| Dropbox | It needs an app key of the project and an OAuth login with a way back to the frame |
| Google Drive | Google reviews apps that read Drive, and an API key for public folders would be shared by all frames |
| iCloud shared album | There is only an unofficial web interface, which can change without notice, and an album has no folders |

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
then at `http://127.0.0.1:8090/`. It listens on 127.0.0.1 only and keeps its data
in `core/build/testserver/<port>`, which it empties at start and deletes when it
stops; a second server on the same port refuses to start instead of emptying it,
and `./gradlew clean` removes what a killed server left behind. Its control
port accepts only GET requests with the header `x-openframe-control: 1`, for
example `curl -H 'x-openframe-control: 1' http://127.0.0.1:8091/code`.

`npm run e2e` starts its own test server on `OPENFRAME_E2E_PORT` (default 8090)
and `OPENFRAME_E2E_CONTROL_PORT` (default: the next port). If the port is busy,
it stops instead of testing against whatever runs there. Set `PW_REUSE_SERVER=1`
to test against a server you started yourself, and give each checkout its own
port when several run at once.

### Toolchain, dependencies and CI

- Java and Node come from `.mise.toml`; run `mise install` after pulling.
- `webadmin/.npmrc` sets `ignore-scripts=true`, so install scripts of dependencies
  never run (in `npm ci`, in the Gradle build and in CI). A package that needs its
  install script is built with `npm rebuild --ignore-scripts=false <package>`.
- `npm run lint` also checks `api/openapi.yaml` with Redocly. Every operation needs
  a 4xx response; `.redocly.lint-ignore.yaml` holds the only exception, `GET /info`.
- Gradle checks every artifact it resolves against `gradle/verification-metadata.xml`.
  Not covered are the `android-all-instrumented` jars that Robolectric downloads by
  itself into `~/.m2` for the `:android` unit tests; it checks them only against the
  `.sha512` file of the same repository. They are used by tests only, never in the APK.
  After changing a Gradle dependency, a plugin or the Gradle version, regenerate it
  with a fresh Gradle home and commit it together with the change:

  ```bash
  GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-configuration-cache --no-build-cache \
    --write-verification-metadata sha256 help check :android:assembleDebug
  ```

  The fresh home matters, because Gradle would record whatever an existing cache
  already holds. Compare new entries of declared dependencies with the `.sha256` or
  `.sha1` files on Maven Central or Google Maven, then delete the temporary home
  (about 1.5 GB). Regenerating only adds entries; to drop stale ones, empty
  `<components>` first.
- aapt2 is recorded for Linux only. On macOS or Windows the build stops with
  "Dependency verification failed" for `aapt2-<version>-osx.jar` or `-windows.jar`.
  Run the command above on that machine, compare the new entry with
  `https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/<version>/aapt2-<version>-<osx|windows>.jar.sha256`
  and commit only that entry.
- To upgrade Gradle, run
  `./gradlew wrapper --gradle-version <X> --gradle-distribution-sha256-sum <sum>`
  twice, with the sum from
  `https://services.gradle.org/distributions/gradle-<X>-bin.zip.sha256` (only the
  second run, on the new version, renews the jar and the scripts). Check
  `gradle/wrapper/gradle-wrapper.jar` against `gradle-<X>-wrapper.jar.sha256`, then
  regenerate the verification metadata.
- GitHub Actions are pinned by full commit SHA with a `# vX.Y.Z` comment, and mise
  by `MISE_VERSION` in both workflows; Renovate raises both. CI runs once per push
  to a pull request (a newer push cancels the older run), and the debug APK
  artifact exists only for runs on `main`.

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
