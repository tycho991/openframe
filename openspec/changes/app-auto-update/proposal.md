# Proposal

## Why

Today a new openframe build reaches a frame only through `adb` or `pm install -r` from the developer's computer. With `release-pipeline`, every release is a signed APK on GitHub. A frame should find new versions by itself, and installing one should take a tap on the frame or a click in the web UI. The path must hold up when things go wrong: a release that crashes must still be replaceable by the next one (safe mode from `crash-resilience`), a manipulated or foreign APK must never be offered, and a dialog that nobody answers or an app restart in the middle must not leave the frame stuck.

## What Changes

- **The frame checks for updates.** While the new setting for automatic checks is on (default on), it asks GitHub for the latest release of the public repository: not before 3 minutes after the start and 6 hours after the last check, then every 6 hours, only with a set clock and only over https. A paired client can ask for a check at any time (at most one real check a minute). After a failure the frame retries after 15 minutes, 1 hour, then 6 hours, earlier when the network returns. A rate limit of GitHub is respected.
- **Only official builds.** If the installed app is not signed with the official certificate (pinned in the app and in the release workflow), the updater stays off and says so.
- **Download and verification.** When it finds a newer version, the frame downloads the APK into its own folder and verifies it before offering it: the checksum the release states, the ZIP structure (no data before the first entry or after the end, which closes the "Janus" gap of Android 5 and 6), package ID, a higher version code, no debug build and the same signing certificate. A download that fails a deterministic check is not fetched again. A kept download is checked again after each start and before installing.
- **Installing takes a tap, never comes unasked.** The frame never opens the installation dialog by itself. A ready update appears in the hint that a tap shows and as an entry in the device menu; a paired client can also ask the frame to install. The frame starts Android's own installation dialog (PackageInstaller session) and someone confirms it on the frame. The display stays on while the dialog is open, also at night, and a request from a client wakes it. After a cancelled or unanswered dialog, client requests pause for 30 minutes. Without the right to install apps, the device menu shows a notice instead of opening system settings.
- **Restart-safe.** Session, deadline and result survive an app restart during the dialog; a session left from before a device restart is discarded. The update announces a planned process end to the run journal of `crash-resilience`, so it never counts as a crash.
- **Safe mode.** The updater also runs in safe mode and checks at once there, so that a release that crashes can be replaced by a fixed one.
- **Restart after the update.** After Android has replaced the app, service, server and display start again without interaction. Renderings of an older format are deleted at the start.
- **Web UI.** The settings get a "Software" section with installed and latest version, release notes as plain text, the state of check, download and installation, "Check now", "Install on the frame" and the switch for automatic checks. After the frame updated itself, the page offers to reload, because its own code is from the old version.
- **API.** `GET /update`, `POST /update/check`, `POST /update/install`; the state's error codes and the refusal codes of the installation as two enums; `phase` includes `unsupported`; the setting `autoUpdateCheck`.
- **Transparency.** The README names the hosts the frame contacts for updates, when and with which data, and that "local only" does not stop it; the switch in the web UI names GitHub.

### Out of scope

- Installing without the dialog: root (`su pm install`) or device owner.
- Pre-releases or a beta channel, downgrades and rollback, delta updates.
- Uploading an APK through the web UI.
- Updating several frames at once from one browser.
- Restricting update requests to an owner role; that comes with the permission model, if it brings roles.

## Capabilities

### New Capabilities

- `app-update`: how a frame finds, downloads, verifies and installs a new version of itself: source, official builds, automatic and requested checks with their limits, retries, download and verification, the ready state on the frame, the installation dialog and its deadline, the display during it, the request from a client, safe mode, the API and its codes.

### Modified Capabilities

- `frame-settings`: "Frame settings" also includes the switch for automatic update checks (default on).
- `kiosk-host`: new requirement "Restart after an update".
- `remote-sources`: "Access to the sources" limits the traffic of the sources; update checks are governed by `app-update`.
- `web-admin`: new requirements for the software section, for explaining the installation and for reloading after a frame update.

## Impact

- Depends on `release-pipeline` (releases with signed APKs, the pinned certificate) and on `crash-resilience` (the `health` interface of its design decision 9: state, planned exit, safe mode). Both are implemented first.
- `:core`: a new `update` package (`GithubReleases`, `ApkStructure`, `UpdateManager`, state as a `StateFlow`), the platform interface `AppInstaller` with a JVM fake, `PlatformInfo.appVersionCode` and `appDebuggable`, `DisplayController.wake`, `hold` and `release` with `DisplayPower.keepOn`, `TrustAnchors` with extra anchors for the update client, `FrameDirs.updates`, the render format folder in `RenderCache`, the new setting, routes `GET /update`, `POST /update/check`, `POST /update/install`, and an injectable release source for the test server.
- `:android`: `AndroidAppInstaller` with `PackageInstaller` sessions and `InstallStatusReceiver`, `REQUEST_INSTALL_PACKAGES`, `MY_PACKAGE_REPLACED` in `BootReceiver` with a log line when openframe is not the HOME app, `AndroidDisplayPower.keepOn`, the device menu as a list of entries, the hint line, `Clock.elapsedRealtimeMillis()`, strings in `values` and `values-de`.
- `api/openapi.yaml` (also `State.appVersion`), Kotlin DTOs, `npm run gen:api`; web UI: the "Software" section, the reload notice from the version built into the web UI, total translation functions, typed code maps, `en.json` and `de.json`.
- Docs: `README.md` ("Updates" and "Privacy").
- Devices: rows in `../openframe-private/DEVICE_TEST.md`. The first release with this change is installed by hand on both frames, each install only with the owner's consent; the update path is tested on hardware with the release after it.
