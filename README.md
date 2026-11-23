<img src="webadmin/public/favicon.svg" width="96" height="96" alt="">

# openframe

Digital picture frame for old Android tablets and Frameo frames. The slideshow
runs natively on the device and is managed in the browser of a laptop or phone.
There is no cloud service of its own: photos get onto the frame by USB, by upload
or from Nextcloud (a share link or an account), and the frame itself is the server.

Target devices run Android 5.0 (API 21) or newer, tested on the Frameo `PFF_1024`
(Android 6.0.1) and the Lenovo Yoga Tablet 2 1050F (Android 5.0.1).

openframe is not related to openframe.io or any other project of that name.

## Status

A one-person project, used daily on the two frames named above and tested on the Android emulator. Signed APKs are
attached to the [GitHub releases](https://github.com/tycho991/openframe/releases) (see "Releases"); before the first
one, build the app yourself (see "Building"). Expect changes to the API and the settings between versions.
Questions and bug reports are welcome as issues; security problems go through [SECURITY.md](SECURITY.md).

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

## Installing

```bash
adb install -r android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n io.github.tycho991.openframe/.android.DisplayActivity
```

The debug build is meant for development: it is debuggable, so anyone with USB access to the frame can read the
app's stored data (including the Nextcloud credentials) with `run-as`, and it registers two crash test receivers:
`DEBUG_CRASH` crashes the app, `DEBUG_DECODE_CRASH` the decoder process at its next rendering. Only `adb` can trigger
them: they need the permission `android.permission.DUMP`, which the shell holds and an ordinary app cannot get. Use
the debug build on a frame you control, and once releases exist, never install it over a release.

openframe has its own package ID, so it can be installed next to another kiosk app. To have it appear by itself
after a restart, choose it as the home screen (HOME) in the Android settings.

## Updates

A frame running an official release finds new versions by itself. Builds signed with another key never look for
updates; the web UI says so.

- **When.** With "Check for updates automatically" on (setting `autoUpdateCheck`, on by default), the frame asks
  GitHub for the latest release 3 minutes after it starts, at the earliest 6 hours after the last check, then every
  6 hours, and only once its clock is set. After a failure it tries again after 15 minutes, 1 hour, then every
  6 hours, earlier when the network comes back. "Check now" in the settings asks at once, also with the switch off.
- **Which hosts.** `api.github.com` for the latest release, then `github.com` and
  `release-assets.githubusercontent.com` for the APK, all over https and without credentials. They see the frame's
  public IP address, the time and the User-Agent `openframe`. "Local only" limits the frame's own server, not this
  outgoing check; switch `autoUpdateCheck` off to stop it.
- **Verification.** The frame downloads the APK into its own storage and checks the release's SHA-256, the ZIP
  structure, the package ID, a higher version code, that it is no debug build, and that it is signed with the same key
  as the installed app. Only then is it offered.
- **Installing.** The frame never installs by itself. A tap on the frame names a ready update, the device menu (a
  3-second press in a corner) offers "Install update", and "Install on the frame" in the web UI asks the frame to open
  Android's installation dialog, which someone has to confirm on the frame. The display stays on while the dialog is
  open, at most 10 minutes. Android must allow openframe to install apps: up to Android 7 the setting "Unknown
  sources" under Security, which applies to the whole device and can be turned off again after the update; from
  Android 8 "Install unknown apps" for openframe under Apps › Special access.
- **Rescue.** If an update breaks a frame so that its server no longer starts, install the next release (or the one
  before, if it can read the stored data) over `adb install -r openframe-X.Y.Z.apk`. A release that crashes repeatedly
  sends the frame into safe mode, which still checks for and installs a fixed release.

### First pairing

1. Without photos the frame shows the address of the admin interface, for
   example `http://<frame-ip>:8080/`, a QR code and a six-digit pairing
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
within 5 minutes. The invited device gets the same rights as every paired
device; the list of paired devices shows how each one paired.

Deleting a local photo in the admin interface moves it into the trash, the
hidden folder `.trash` in the photo folder, for 7 days. The photo overview
lists the trash with the deleting device, and every paired device can restore
from it. Its storage is freed after the 7 days, or earlier, but at the earliest
a day after deleting, when an upload, the rendered sizes or an update run short
of storage and the old entries free enough. Deleting `.trash` over USB frees
the storage at once.

The token lives in the browser per address. If the frame gets a new IP address,
every browser has to pair again, so give the frame a fixed address (DHCP
reservation) in the router. On an iPhone, a web app added to the home screen
has storage of its own and is not paired, even if Safari is. It has no address
bar, so links do not reach it: pair it with the code ("Show code on frame").

If a phone cannot reach the frame at all while other devices can (Chrome on Android shows
`ERR_TOO_MANY_RETRIES`), its browser may lack access to the local network. Android 17 silently blocks
connections to devices in the home network for apps that were denied it: allow it under Settings ›
Apps › Chrome › Permissions.

On the frame itself: a tap shows name, address, code, QR code and pause, and a ready update. Swiping pages
through the photos. Pressing a corner for three seconds opens the device menu
with "local only", the display schedule, brightness control (Android 6 and later), "Unpair all devices", a ready
update and info.

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

## Brightness

Under "Settings" the brightness is either Android's own setting (the default: openframe leaves it alone), a fixed
level, or Android's automatic brightness on devices with a light sensor. Each window of the display schedule can dim
the display from a time of its own until it goes off, also while it is woken at night; the next morning the normal
brightness applies again. The slider in the display state, and `PUT /display` with `brightness`, set a level at once.
A brightness changed on the device itself holds until openframe next applies its own: at a start, on a change of the
setting, or when dimming begins or ends.

openframe sets Android's **system brightness**, which needs the permission to modify system settings. Up to Android 5
it comes with the installation. From Android 6 on allow it once on the frame: press a corner for three seconds, choose
"Brightness control: not allowed" and switch on "Allow modifying system settings" in the screen that opens. Devices
whose settings lack that screen can grant it over `adb`:

```bash
adb shell appops set io.github.tycho991.openframe WRITE_SETTINGS allow
```

The brightness stays as openframe last set it after uninstalling; choose "Android's setting" first, or set it again in
Android's quick settings.

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
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The data is changed: converted into a compact format,
positions rounded to 1/10,000 degree, and one German name chosen per place. The admin interface shows the attribution
next to the place setting; `SOURCE.txt` next to the data, and the same note under "Licences" in the web UI, names the
dumps it was built from and the changes. The data is regenerated on purpose, not on every build:

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
  the whole account. Logging in again to the same account renews its access and keeps its photos. Every paired
  device sees the photos of the folder, with their paths. A folder that reads more than before (a higher or another
  folder, `/`, or subfolders switched on) needs a Nextcloud login to that account on the device that sets it, within
  30 minutes: the source card asks for it ("Confirm with Nextcloud"). For a frame that other people use, a share link
  of just the folder you want to show is the better choice.

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
frame without its original.

The frame keeps a storage reserve free: 10 % of its data volume, at most 500 MiB, plus 100 MiB, about 600 MiB on the
reference frames. Uploads, downloads of originals for syncs and renderings ahead of time leave it free, and the cache
of rendered sizes shrinks below its budget to keep it. Only a photo someone waits for, the one the slideshow shows
next or an image the web interface asks for, may go below it; the download of its original still leaves 100 MiB free.
The start log names the reserve (`Storage reserve: N MiB of M MiB`).

A frame that had a Nextcloud connection before (0.1.0) keeps it as the source `nextcloud`, with its photos, their IDs
and hidden states, without a new login. Its old `nextcloud.json` is deleted at the next start with a readable list of
sources, and an app password in it that the source no longer uses is revoked on the server; going back to 0.1.0
then means logging in again.

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

Uninstalling deletes the photo folder as well. Back it up first, into a folder outside any repository:
`mkdir -p ~/openframe-backup && adb pull /sdcard/Android/data/io.github.tycho991.openframe/files/photos ~/openframe-backup/`
(the photos land in `~/openframe-backup/photos/`).

## Security model

openframe is made for a home network you trust. It defends against websites that try to reach the frame through
your browser, and it limits what a hostile device on the LAN can do, but it does not replace a network you trust.
Read this before you install it.

- **LAN only, without TLS.** The frame serves the web UI and the API over plain HTTP on port 8080. Never make it
  reachable from the internet: no port forwarding in the router, and switch off UPnP port mappings for it. Anyone on
  the same network can see the traffic between a browser and the frame.
- **Pairing.** A browser gets a token by entering the code shown on the frame, by scanning its QR code or through an
  invitation from a paired device. Every paired device has the same rights, however it paired: it can change
  settings, delete photos, add and change sources, rename and unpair other devices and invite further ones. "Paired
  devices" shows how each device paired: with the code, with the QR code or by invitation of which device ("origin
  unknown" for pairings from before the frame recorded it). "Unpair all devices" in the device menu removes every token and is the
  remedy against a hostile paired device; review the list after an update and unpair devices nobody uses.
- **Trash.** Deleting a local photo moves it into the trash (`.trash` in the photo folder) for 7 days, so no paired
  device can delete a photo for good. Under storage pressure, an upload, the rendered sizes or an update short of
  storage, entries deleted more than a day ago may go earlier, oldest first, and only if that frees enough. Every
  paired device sees the trash with thumbnails and can restore from it; only USB frees its storage at once. Deleting,
  restoring and purging are logged with the acting device.
- **Widening an account folder needs a login.** A change that makes an account source read more (the first folder, a
  higher or another folder, subfolders switched on) succeeds only if the same device completed a Nextcloud login for
  that very account within the last 30 minutes. A visitor's phone can therefore not turn a source of one folder into
  the whole account. Narrowing, the other settings and share links need no login.
- **Accepted residual risks.** Every paired device can still revoke, rename and invite other devices; change settings,
  `autoUpdateCheck` included (local-only mode stays on the frame); hide photos and overwrite comments and sections;
  remove a source, which loses the hidden states, comments and sections of its photos and revokes its app password;
  and restore and see photos another device deleted. The log (`GET /api/v1/logs`, open to every token) shows device
  IDs and photo paths. An account owner who approves a login page that another device started, for example from a
  link that device sent, gives that device the proof for widening. A forward jump of the frame's clock ages every
  trash entry and can purge the whole trash at the next hourly check. Roles for owners and guests are to come as a
  change of their own.
- **Guessing the code.** A code exists only while the frame shows it on a display that is on. After 5 wrong codes,
  pairing by code is locked for 60 seconds, twice as long at each further lockout, at most an hour. After 20 wrong
  codes within 24 hours it pauses until the oldest of them is a day old. A pairing of any kind, or "Unpair all
  devices", ends both. Pairing by QR code and by invitation always keeps working.
- **Without a token** only three API endpoints answer: `GET /api/v1/info` (name and versions of the frame),
  `POST /api/v1/pairing` and `POST /api/v1/pairing/code`. The static files of the web UI are open to everyone on the
  LAN; they hold no data. Image URLs carry a signature instead of the token, valid for one photo and at most two
  hours, and only while the device it was issued to stays paired. The app and Android versions in `GET /api/v1/info`
  stay public: the web UI's file names give the build away anyway, and a client needs the frame's identity before it
  pairs.
- **Websites in your browser.** The frame answers only requests whose `Host` is an IP address, `localhost`, a name
  without dots or a local name (`.local`, `.home.arpa`, `.localdomain`, `.lan`, `.fritz.box` and similar). A website
  that points a name of its own at the frame's address (DNS rebinding) gets 421 and neither reads nor changes
  anything. If you reach the frame through a DNS name under a public domain, open it by its IP address instead.
  Changes from foreign origins are refused as before.
- **Limits.** The frame holds at most 48 connections, 16 per device, and closes idle ones first when full. Headers
  must arrive within 10 seconds, JSON bodies within 10 seconds, and a pairing body has at most 4 KiB. What remains:
  a device on the LAN can keep pairing by code paused for up to a day, and attackers with several addresses, or one
  that reads answers very slowly, can still block the web UI. A frame with a slow network stack (seen on an Android 5
  tablet) accepts only a few dozen connections per second: while a device on the LAN floods it with new
  connections, the web UI answers within seconds instead of a fraction of a second. The slideshow keeps running
  meanwhile.
- **Local addresses only.** The frame answers only connections that arrive on the device itself (`127.0.0.1`, for
  example through `adb forward`) or on its Wi-Fi, Ethernet, hotspot, tethering or VPN addresses, IPv4 and IPv6. A
  VPN such as Tailscale or WireGuard therefore reaches the frame. On a mobile data address it closes the connection
  without an answer, so a frame with a SIM is not reachable from the carrier's network.
- **Local only.** "Local only" in the device menu binds the server to `127.0.0.1`, so nothing on the LAN reaches it.
- **Changes to the paired devices are logged.** Each pairing, rename, revocation and invitation writes a line with
  the IDs of the device that made it and of the device it concerns, readable with `GET /api/v1/logs`. Tokens,
  pairing secrets and invitation links never appear there.
- **Sources point anywhere (accepted).** A paired device can set a source to any `http` or `https` address the frame
  reaches, also the frame's own loopback services or, on a device with a SIM, the carrier's network. The frame then
  sends requests there in its own name (a blind server-side request forgery): it reports only whether the sync
  worked and a class of error, never the content of an answer. This stays accepted while every paired device has the
  same rights (change `paired-device-guards`): adding a share needs no account, so the widening proof does not apply.
  It is to be checked again with roles, when they decide who may add sources.
- **Answers of a source are bounded.** A Nextcloud server, or anything a source points at, can send at most fixed
  amounts: a listing of 64 MiB after decompression, 50,000 entries per folder answer, 32 levels of subfolders,
  5,000 folders and 100,000 files per sync, 64 KiB per login answer, and per download the listed size plus 1 MiB.
  Every answer has a total time, the status line and headers included, and a minimum speed; a sync as a whole ends
  after 2 hours and goes on at the next one. Paths are at most 1,024 characters long. A listing with a `DOCTYPE` or with an entry outside its folder
  fails the sync instead of being read. Such a failure keeps the photos of the source.
- **Plain http.** A Nextcloud address with `http` works on every Android version, also from Android 9 on. The web UI
  warns at the address field and on the card of every source with `http`: its app password, share password and
  photos then cross the network unencrypted. The updater accepts only `https`, also for every redirect. A login that
  starts on `https` never continues on `http` or on another port.
- **Refused access.** When the server refuses the credentials of a source, or a share is withdrawn or only accepts
  uploads, the frame tries that source again at most once a day, also after a restart, so that it does not keep
  failing logins on the server. Logging in again, changing the source or "Sync now" try at once. An app password
  that a new login replaces is revoked on the server.
- **Stored credentials.** App passwords, share links and share passwords of Nextcloud sources are stored
  unencrypted in the app's private storage. They never appear in an API answer or in the log, but anyone who can
  read the app's data (root, or `run-as` on a debug build) can read them.
- **Image decoding.** Android's native image decoders parse every photo the frame shows. On Android 5 and 6 with a
  security patch level before 2016-09-01 these decoders have known flaws (for example CVE-2016-3862), so a crafted
  file could take over the app. Check the patch level under Settings › About (Android 5.0 shows none, which counts
  as before that date); on such a device connect only sources you trust: your own photos, your own Nextcloud.
  Share links from other people and USB sticks of unknown origin count as untrusted.

## Privacy

openframe has no telemetry and no account of its own. The frame connects only to the sources you configure (your
Nextcloud server) and, for updates, to GitHub (see "Updates"; switch `autoUpdateCheck` off to stop it, "local only"
does not); place names are looked up offline in data shipped with the app. The web UI loads nothing from outside the
frame.

## Licence

openframe is licensed under the [MIT licence](LICENSE). The licences of the bundled libraries, the typeface, the
icons and the place data ship with the app; the web UI links them under "Appearance" › "Licences".

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security problems: [SECURITY.md](SECURITY.md).

## Developing

```bash
./gradlew check                 # formatting, :core tests, Android imports, API 21, Android Lint
./gradlew :android:testDebugUnitTest
cd webadmin
npm ci
npm run lint && npm run typecheck && npm test
npm run e2e                     # builds the web UI and tests it with Playwright against a :core test server
OPENFRAME_URL=http://<frame-ip>:8080 npm run dev   # Vite with hot reload, API proxied to the frame
```

The `:core` test server with fake images can also be started with
`./gradlew :core:runTestServer --args="8090 8091 webadmin/dist"`; the web UI is
then at `http://127.0.0.1:8090/`. It listens on 127.0.0.1 only and keeps its data
in `core/build/testserver/<port>`, which it empties at start and deletes when it
stops; a second server on the same port refuses to start instead of emptying it,
and `./gradlew clean` removes what a killed server left behind. Its control
port accepts only GET requests with the header `x-openframe-control: 1`, for
example `curl -H 'x-openframe-control: 1' http://127.0.0.1:8091/code`.

The test server reports the version of `version.txt`. Its fake GitHub and installer are steered with these control
commands:

| Command | Effect |
| --- | --- |
| `/update/publish?version=0.4.0&notes=…` | publishes a release with an APK; `apk=0` without one |
| `/update/fail?code=update_unreachable` | GitHub fails with that code; without `code` it answers again |
| `/update/installed?version=9.9.9` | the frame runs another version from now on |
| `/update/answer?result=confirmed` | answers the open installation dialog: `confirmed`, `cancelled` or `failed` |
| `/update/allowed?on=0` | Android allows openframe to install apps (`1`) or not (`0`) |
| `/update/official?on=0` | an official build (`1`) or one signed with another key (`0`); the core restarts |
| `/update/phase` | the phase of the update, e.g. `ready` |

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
- The app icon's source is `webadmin/public/favicon.svg`. The launcher icons in
  `android/src/main/res/mipmap-anydpi/` and `drawable/ic_launcher_frame.xml` copy its shapes, which `checkAppIcon`
  verifies; after changing it, update them and run `scripts/render-icon.sh` (Inkscape) for
  `webadmin/public/apple-touch-icon.png`.
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
  `<components>` first. On Renovate's pull requests the workflow `renovate-artifacts`
  does this: it runs the command, compares every new checksum with the `.sha256` or
  `.sha1` the repository publishes (`scripts/verify-new-checksums.py`) and pushes the
  file to the branch.
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
  by `MISE_VERSION` in every workflow that installs it; Renovate raises both. CI runs once per push
  to a pull request (a newer push cancels the older run), and the debug APK
  artifact exists only for runs on `main`.

What can be verified only on real hardware, and the findings of the device tests, are kept
privately by the maintainer, because they hold details of the devices and the network they were measured on.
Archived changes under `openspec/changes/archive/` still name these documents by their old paths in `docs/` and
`scripts/`.

## Releases

Versions come from the pull request titles. Every pull request is squash-merged with its title as the commit
subject, and the title is a [Conventional Commit](https://www.conventionalcommits.org/) (`pr-title.yml` checks it).
release-please (`release-please.yml`) keeps a release pull request `chore(main): release X.Y.Z` open with the next
version and its `CHANGELOG.md` entries:

- Below 1.0, `feat` and breaking changes raise the minor version, `fix` and `perf` the patch version. `chore`,
  `docs`, `test`, `ci`, `build` and `refactor` alone lead to no release. Renovate collects a week's updates into two
  pull requests on Monday morning: `fix(deps): update app dependencies` for what ends up in the APK or the web
  UI bundle, and `chore(deps): update tools` for tools and tests. Majors wait for approval on the dependency
  dashboard; security fixes come at once.
- The version lives in `version.txt`, which only the release pull request changes, together with
  `webadmin/package.json` and its lock file. Gradle derives `versionName` from it and `versionCode` as
  `X*10000 + Y*100 + Z`; CI checks that all of them agree.

Merging the release pull request, which only the owner decides, starts the release:

1. release-please creates the tag `vX.Y.Z` and a draft release.
2. `checks` runs the full CI (`ci.yml`) on the tag's commit.
3. `build` builds the unsigned release APK with a read-only token, without secrets and without caches, checks
   package, version, version code and that it is not debuggable, and keeps it as an artifact for one day.
4. `sign` runs in the environment `release` and waits for the owner's approval ("Review deployments" in the run).
   It checks out no project code, signs with the key from the environment secret `FRAME_KEYSTORE_BASE64`, stops
   unless the certificate's SHA-256 is the pinned
   `2f1a67021c705203a0cb8f0324229abdd42a31d69d6c4907ca2ae41538ce34fe` (`FRAME_CERT_SHA256` in the workflow), attaches
   `openframe-X.Y.Z.apk` and publishes the release. It becomes the latest release only if no higher version is
   published.

Until the release is published, a frame asking for the latest release gets the previous one.

Runbook:

- A flaky test in the gate: "Re-run failed jobs" in the run. Every new sign job needs a new approval.
- The approval came more than a day later and the sign job finds no artifact: re-run the `build` job, which re-runs
  `sign` after it. "Re-run all jobs" would not help, because release-please would find no pending release and skip
  everything.
- Approve one release at a time, so that two sign jobs never decide at the same moment which one is the latest.
- A published release is never changed (releases are immutable). A bad one is fixed forward with a `fix:` pull
  request. A draft that is never published can be deleted; its tag stays, and the fix gets the next version.
- Checking a release, in a folder outside any repository:

  ```bash
  gh release download vX.Y.Z --repo tycho991/openframe --pattern 'openframe-*.apk' --dir ~/openframe-release
  "$ANDROID_HOME"/build-tools/<version>/apksigner verify -v --min-sdk-version 21 --print-certs \
    ~/openframe-release/openframe-X.Y.Z.apk
  ```

  The output must show the v1 scheme verified and the pinned SHA-256 digest above.

GitHub settings, all made by the owner (release-pipeline, design decision 8):

- Before the first release pull request: Actions may create and approve pull requests (Settings › Actions › General
  › Workflow permissions), and the labels `autorelease: pending` and `autorelease: tagged` exist.
- With the repository public, and before any release pull request is merged: the rulesets for `main` and `v*`, then
  the environment `release` with deployment branches only `main`, the owner as required reviewer, "Prevent
  self-review" off and no admin bypass. A job that names a missing environment would create it without protection.
- Right before the first release: the secret `FRAME_KEYSTORE_BASE64` in the environment `release` only, never as a
  repository secret. It holds the frames' keystore (the developer's debug keystore, whose alias and passwords are
  public) as `base64 -w0`, taken from the restored backup after `keytool -list -v` showed the pinned SHA-256.

## Workflow with OpenSpec

Changes are planned as an OpenSpec change (proposal, specs, design, tasks under
`openspec/changes/`) and then implemented. In Claude Code:

1. `/opsx:propose` for a new change
2. `openspec validate <name>` and `openspec show <name>`
3. `/opsx:apply` to implement
4. `/opsx:archive` or `openspec archive <name> --yes`

Node and OpenSpec are pinned in `.mise.toml`. Renovate raises the version by PR, and
the workflow `renovate-artifacts` pushes the regenerated skills to its branch. By hand,
in a branch:

```bash
mise install
mise run openspec-sync
git status --short   # only what openspec update wrote, as the drift check sees it
git add -A && git commit -m "Update OpenSpec skills"
```

The drift check in `.github/workflows/openspec-drift.yml` makes sure that the
committed skills and commands match the pinned version exactly.
