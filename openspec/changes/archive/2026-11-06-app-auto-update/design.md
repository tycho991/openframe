# Design

## Context

What exists today, or will exist when this change is implemented:
- **Releases** come from `release-pipeline`: tags `vX.Y.Z`, one asset `openframe-X.Y.Z.apk` per release with its SHA-256 as the asset's `digest`, signed in CI with the frames' key. The certificate's SHA-256 is pinned in `release-please.yml` as `FRAME_CERT_SHA256`. `version.txt` is the single version source, and `versionCode = X*10000 + Y*100 + Z`.
- **Health** comes from `crash-resilience` (its design decision 9): `core.health.state` with `mode` (`NORMAL`, `SAFE`), `recentEarlyEnds` and `previousEnd`; `health.plannedExit(reason, validForMs)` and `cancelPlannedExit()`; a new installation counts as the planned end `replaced` and ends safe mode. Its run journal reads the `boot_id` (decision 5). Its crash handler schedules the display restart with a growing delay from the third early end. In safe mode the slideshow, the sources and all image work rest; server, pairing, settings, display schedule and device menu run.
- **The frames** run Android 5 and 6 with debug builds (version code 1) signed with the developer's debug key, the key of the releases. Which key each frame really carries is checked read-only before the first install (migration plan step 2), and recorded privately.
- **Clock.** `Clock.monotonicMillis()` is `System.nanoTime()`; only differences within one process mean something. There is no uptime that survives a process restart.
- **HTTP in `:core`.** OkHttp 4.12 with `TrustAnchors`, which bundles ISRG Root X1, USERTrust ECC and Sectigo E46 under `resources/trust` for Android 5 and 6. On 27 Oct 2026, `api.github.com` and `github.com` chained to USERTrust ECC, and `release-assets.githubusercontent.com` (the target of the download redirect) to ISRG Root X1.
- **Display.** `DisplayController` treats a screen that goes on while the frame wants it off as waking (`woken()`, `wakeUntil`), and a screen that goes off while it wants it on as a manual off. `NetworkLink` has a single listener, which `DisplayController` uses; `FrameCore.onNetworkChanged()` forwards network changes to the sources.
- **Device menu and hint.** `DisplayActivity.openDeviceMenu()` builds an array of labels and dispatches with `when (which)` over fixed indexes. A tap shows the hint bar.
- **Stores and folders.** `JsonStore.save` writes after a debounce of one second, `saveNow` at once. `FrameDirs.cache` holds the renderings under `renders`. Image URLs carry the photo version `v` and the render key `p`, and the current URL is served `immutable` for a year. `SyncSchedule.retryDelayMs` shapes the backoff of the sources; `DisplayPolicy.clockSet` tells whether the clock was ever set.
- **Restarts.** `BootReceiver` starts `FrameService` and, below API 29, `DisplayActivity`.
- **Web UI.** `t()` returns the key itself for an unknown key, but throws for `undefined`, because the template goes through `format()`, which calls `.replace` on it. Enum mappings have no fallback to the raw value. Error codes map to texts in `errorCodes.ts`, which a test finds by a regular expression over `HttpException(…, "code")` literals; errors use the `Error` envelope of the contract. `startStatePolling` polls `GET /state` every 10 s on every page.

See proposal.md for the motivation and the specs for the required behavior.

## Goals / Non-Goals

**Goals:**
- Check, download, verification and the state machine live in `:core`, testable with `TestClock` and a fake GitHub over TLS.
- One Android code path for API 21 to 36, framework APIs only.
- Nothing is offered that a frame should not install: a foreign signature, a lower version, a debug build, a manipulated archive.
- An update never leaves a frame stuck: not in a dialog, not in a crash loop, not after an app or device restart in the middle.

**Non-Goals:**
- Resuming interrupted downloads; the APK is about 7 MB.
- Release notes as rendered Markdown.
- Tests on the Galaxy Tab S7, which is paused.

## Decisions

### 1. Official builds and versions

`core/.../update/OfficialCertificate.kt` holds the certificate SHA-256 of the releases. A JVM test reads it and `FRAME_CERT_SHA256` from `.github/workflows/release-please.yml`, normalizes case and colons, and fails if they differ.

`AppInstaller.installedCertificateSha256()` (decision 6) gives the installed app's certificate. If it differs from the official one, or cannot be read, the manager stays in phase `unsupported`: no request to GitHub, also not on request. A developer's debug build signed with the frames' key stays supported.

Versions are compared by version code. The release's code is derived from its tag with the formula of `release-pipeline`; a tag with minor or patch of 100 or more counts as no update, because its code would collide. The installed code comes from the new `PlatformInfo.appVersionCode: Long?`. If it is `null`, nothing is offered and the log says why. `PlatformInfo` also gets `appDebuggable: Boolean`, and the start line of the log reads `App 0.3.0 (300, release)` or `(…, debug)`.

### 2. `GithubReleases`

- `GET https://api.github.com/repos/tycho991/openframe/releases/latest` with `Accept: application/vnd.github+json`, `User-Agent: openframe` (no version, as in the Nextcloud client) and `If-None-Match` with the stored `ETag`; a `304` does not count against GitHub's rate limit and keeps the stored release.
- It parses `tag_name`, `draft`, `prerelease`, `body`, `published_at` and `assets[]` (`name`, `size`, `digest`, `browser_download_url`). A tag that is not `vX.Y.Z` means no update. The asset must be the only one matching `openframe-*.apk`, and its `digest` must be `sha256:<64 hex>`; otherwise the check fails with `update_no_apk` or `update_no_checksum`.
- A `404` fails with `update_not_found`. A `403` or `429` with `x-ratelimit-remaining: 0` fails with `update_rate_limited` and stores `x-ratelimit-reset`. Other HTTP errors and I/O errors give `update_unreachable`. A trust failure of TLS gives `update_tls` and logs subject and issuer of each certificate in the chain.
- Its own OkHttp client: `followSslRedirects(false)` and a network interceptor (`addNetworkInterceptor`, so it sees every redirect hop) that refuses any request whose URL is not `https`: the base URL, `browser_download_url` and every redirect target. There is no switch for `http`, also not for tests. No credentials are sent, so following the redirect to `release-assets.githubusercontent.com` is safe; `NextcloudHttp` refuses cross-host redirects because of its `Authorization` header.
- Trust: the bundled anchors plus ISRG Root X2 and Let's Encrypt's roots YE and YR, for the update client only (owner's decision). If GitHub or its asset host moves to a new root, the update that would bring the new anchor could otherwise never arrive. `TrustAnchors.trustManager(extra)` replaces the bundled set, so the update client passes `delivered()` plus the three extra PEMs from `resources/trust`; the Nextcloud client keeps its set.
- `FrameCore` takes the release source as a parameter (as it takes the Nextcloud clients), so the test server can put a fake in.

### 3. `UpdateManager`: when it checks

The manager owns `state: StateFlow<UpdateState>` and a `Mutex` that makes check, download and install single-flight. It runs a loop on the core's scope in both modes of `health`.
- **Next attempt.** `update.json` keeps `lastAttemptAt` (wall clock) and `failures` (consecutive failed attempts). The next automatic attempt is at `max(start + 3 min, lastAttemptAt + delay(failures))` with `delay(0) = 6 h` (after a success), `delay(1) = 15 min`, `delay(2) = 1 h` and `6 h` from then on; a small function of its own like `SyncSchedule.retryDelayMs`, with these steps. A `lastAttemptAt` in the future is dropped. In safe mode, or with `recentEarlyEnds > 0`, the first attempt of the run is at once, without the 3 minutes and without the delay (crash-resilience decision 9): a fixed release may have appeared since. The 60-second minimum and a rate-limit reset still apply.
- **Gates.** No automatic attempt while `autoUpdateCheck` is off or `DisplayPolicy.clockSet(now)` is false; neither is an error. No request before a stored rate-limit reset. Nothing at all in phase `unsupported`.
- **Network back.** `FrameCore.onNetworkChanged()` also calls `update.networkChanged()`, which moves a waiting retry (`failures > 0`) to within one minute.
- **On request.** `check()` runs the same step at once, unless a check or download runs, the last attempt was less than 60 seconds ago, or the rate limit has not reset; then it returns the current state.
- **Errors.** Every step catches everything except `CancellationException` and reports `update_failed` for what is not mapped, so the loop always goes on. A test feeds the fake GitHub broken JSON.

*Alternative:* listening to `NetworkLink` directly: it has one listener, and `DisplayController` uses it.

### 4. Download and verification

- **Place.** `FrameDirs.updates` is `filesDir/update`, not the cache: Android may clear the cache, and a ready update must survive restarts in safe mode. `TestFrame` and `TestServerMain` get the folder as well.
- **Space.** Before downloading, `platform.freeBytes(updates)` must be at least twice the asset size plus 100 MiB; otherwise `update_storage`. A storage reserve for caches, as planned for a later change, does not apply to it (owner's decision).
- **Download.** To `openframe-X.Y.Z.apk.part` with a streaming `MessageDigest` (no `readNBytes`), then size and SHA-256 against the release, then rename. Other files in the folder are deleted.
- **Checks, in this order:** digest; `ApkStructure.check` (decision 5); `AppInstaller.verify` (decision 6) for package ID, a higher version code, no `FLAG_DEBUGGABLE` and the installed certificate. A failure deletes the file and reports `update_checksum`, `update_invalid_apk` or `update_signature`.
- **Rejected assets.** A failure other than the digest is deterministic for that file: `update.json` stores the asset digest with the installed version code, and the manager does not download it again while both stay the same. A digest failure goes into the backoff.
- **Kept download.** At start and before each `install()`, the manager checks that the ready file exists and has its digest; otherwise it goes back to `idle` and downloads again. A ready file whose version is not higher than the installed one is deleted.

### 5. `ApkStructure`

Android 5.0 to 8.0 without the December 2017 patch verify a v1-signed APK as a ZIP, but may run DEX data placed in front of it ("Janus", CVE-2017-13156); frames on Android 5 and 6 with an older patch level are common. The check uses `RandomAccessFile` (API 21, no `java.nio.file`):
- the file starts with the local file header `50 4B 03 04` at offset 0;
- the end-of-central-directory record, searched from the end, plus 22 bytes plus its comment length is exactly the file length, so nothing is appended;
- central-directory offset plus size equals the record's position;
- the smallest local-header offset among the central-directory entries is 0. The APK Signing Block between the last entry and the central directory stays allowed.

Fixtures from `scripts/generate-test-fixtures.py`: a Janus-like file (DEX bytes before a valid ZIP), a ZIP with appended data, and an APK signed with `apksigner` (v1 and v2) that passes.

### 6. Platform interface `AppInstaller`

In `core/.../platform/`:
- `installAllowed: Boolean`, read afresh each time;
- `installedCertificateSha256(): String?`;
- `verify(apk: File): ApkVerdict` (`Ok`, or `Rejected(code, detail)`);
- `createSession(): Int`, which only creates the session and returns its ID;
- `commit(sessionId: Int, apk: File)`, blocking I/O: copy, `fsync`, commit;
- `openSessions(): Set<Int>`;
- `abandon(sessionId: Int)`.

`Clock` gets `elapsedRealtimeMillis()` (`SystemClock.elapsedRealtime` on Android, controlled by `TestClock`), and `health` exposes the `boot_id` its journal reads. A persisted uptime value is always stored with the `boot_id` and counts only while it is the same; without a readable `boot_id`, an uptime below the stored one means the device restarted.

Results reach `UpdateManager.onInstallResult(sessionId, InstallResult)` with `InstallResult` = `Cancelled`, `Failed(message)` or `Success`. `AppInstaller.None` reports `installAllowed = false` and no certificate. `FakeAppInstaller` records calls, lets a test answer them, and, like Android, reports `Cancelled` after `abandon()`.

**The flow, restart-safe:**
- `UpdateManager.install()` does not block. It returns a sealed `InstallRequest`: `Accepted`, `NotReady`, `NotAllowed`, `InProgress` or `Cooldown`. The route maps it to `202` or `409`; the device menu shows a notice. Nothing throws on the main thread.
- On `Accepted`, the manager, on the core's I/O scope: calls `health.plannedExit("update", 15 min)` (the 10-minute deadline plus a margin); calls `createSession()`; writes `installRequested` (version, session ID, `boot_id`, request time and deadline on `elapsedRealtimeMillis()`) with `saveNow`; then calls `commit()`. So the ID is on disk before Android can answer, and a process end between creating and saving leaves a session that the next start abandons.
- Results of a session other than the active one are dropped. Before `abandon()` the manager clears the active ID, so that the `ABORTED` Android sends afterwards cannot turn `install_unanswered` into `install_cancelled`.
- A result that arrives before `update.json` is loaded (the broadcast started the process) is buffered and applied after loading.
- At start, the manager compares with `openSessions()`. Every open session other than the one in `installRequested` is abandoned. The one in `installRequested` is `installing` again with the rest of its deadline, and `plannedExit` is renewed for that rest plus the margin. If the device restarted since the request (other `boot_id`, or a lower uptime), the session is abandoned at once and the state is `install_unanswered`. Without an open session and without a result, the dialog counts as unanswered.
- `Cancelled` keeps `ready` with `install_cancelled`; `Failed` keeps `ready` with `install_failed` and the message; both and the deadline call `cancelPlannedExit()`. `Success` usually never arrives, because Android ends the process; the next start sees `replaced` in `health` and an installed version equal to `installRequested`, logs "Updated to X" and clears the field.
- After `install_cancelled` or `install_unanswered`, client requests get `Cooldown` for 30 minutes; the device menu is exempt. The cooldown lives in memory on `monotonicMillis()` and ends with the process. The log names the client.

### 7. `:android`: `AndroidAppInstaller`

- **`createSession` / `commit`:** `createSession(SessionParams(MODE_FULL_INSTALL))` with `setAppPackageName`; then `openSession`, copy into `openWrite`, `fsync`, `commit` with an `IntentSender` from `PendingIntent.getBroadcast` to `InstallStatusReceiver` (not exported; `FLAG_MUTABLE` from API 31, because the system fills in the extras).
- **Receiver:** `STATUS_PENDING_USER_ACTION` starts `EXTRA_INTENT` with `FLAG_ACTIVITY_NEW_TASK`, which opens the system dialog. `STATUS_FAILURE_ABORTED` maps to `Cancelled`, other failures to `Failed(EXTRA_STATUS_MESSAGE)`, `STATUS_SUCCESS` to `Success`, each with `EXTRA_SESSION_ID`. If the core is not running, the receiver starts `FrameService` before handing the result over.
- **`installAllowed`:** `canRequestPackageInstalls()` from API 26; below, `Settings.Secure.INSTALL_NON_MARKET_APPS == 1`.
- **`verify`:** `getPackageArchiveInfo(path, GET_SIGNATURES)`, from API 28 with `GET_SIGNING_CERTIFICATES` as well (API 28 may fill only `signatures` for an archive), falling back to `signatures`; no certificate at all is `Rejected`. It checks package name, version code (`longVersionCode` from API 28) above the installed one, no `FLAG_DEBUGGABLE`, certificates equal to the installed app's.
- **`openSessions` / `abandon`:** `PackageInstaller.mySessions` and `abandonSession`; after abandoning, `DisplayActivity` is started with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT` to cover the installer's stale dialog. That dialog stays in the installer's task, bound to the abandoned session: on Android 5 and 6 a tap on "Install" there crashes the package installer, and a confirm intent started with `FLAG_ACTIVITY_NEW_TASK` alone brings that task back instead of a new dialog. So `InstallStatusReceiver` starts the confirm intent with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, which replaces the stale dialog with the new session's.
- **Testability:** the class takes `sdkInt` (as `AndroidMediaEngine` does), so API 21 and 22 paths are testable; Robolectric runs with `@Config(sdk = [23, 28, 34])`. SDK 35 and later would need Java 21 in Robolectric, and the toolchain is 17; `FLAG_MUTABLE` is covered by 34.
- **Manifest:** `REQUEST_INSTALL_PACKAGES` (a normal permission that takes effect from API 26).

*Alternative:* `ACTION_INSTALL_PACKAGE` with a URI needs `file://` up to API 23 and a `FileProvider` of our own from API 24 (no AndroidX), two code paths that expose the file to other apps.

### 8. The display during the dialog

- `DisplayController.wake()` sends `Event.Wake`: `power.turnOn()` (it needs only `WAKE_LOCK`, also without device administration), then `woken()`. This is the power button's path: it ends a manual off and, in an off period, keeps the display on for the minutes after waking.
- `hold(untilMono)` and `release()`: during a hold, `wakeUntil` is at least the deadline in an off period, and a screen-off event does not count as a manual off. `release()` ends the hold and, in an off period, sets `wakeUntil` to now plus the minutes after waking; it keeps the applied decision, so a screen that someone switched off with the power button during the hold is not switched on again. The hold's deadline is converted from the persisted uptime to `monotonicMillis()` when it is set.
- `DisplayPower.keepOn(Boolean)`: during a hold the Android side keeps a screen wake lock without `ACQUIRE_CAUSES_WAKEUP`, with a timeout equal to the deadline as a safety net. Touches in the system installer never reach `userActivity()` of `DisplayActivity`, so without it a short system screen timeout would turn the screen off under the dialog.
- `FrameCore` connects the two: when the update phase becomes `installing`, it calls `wake()` and `hold(deadline)`; when it leaves `installing`, `release()`. Route and device menu go the same way, and a refused request never reaches the display.

### 9. Frame: hint and device menu

- **Hint.** While `phase == ready`, the bar gets "Update 0.4.0 ready – install from the device menu" (and the German text in `values-de`).
- **Device menu** becomes a list of label and action pairs instead of indexes. The update entry is added only when `phase == ready`, before "Info". With `installAllowed` it calls `core.update.install()` and shows a notice for every refusal. Without it, it shows a notice that names the setting for unknown sources (below Android 8 it applies to the whole device and may be turned off again after the update); it never opens system settings, which would also leave the kiosk.

The crash handler stays as `crash-resilience` defines it. After a Java crash while the dialog is open, the display restart may cover the installer; the dialog then ends at its deadline as `install_unanswered`, and the update stays ready. A rule of its own for this rare case would contradict "Self-healing".

### 10. Safe mode

The updater runs in both modes; only its first attempt is earlier (decision 3). A ready download stays in `files/update`, and `POST /update/install` works without the slideshow: `DisplayActivity` shows the safe-mode notice, and the installer opens over it. The decoder is not bound in safe mode (crash-resilience), so a broken decoder cannot block this path.

### 11. API

Under the tag `update`, all with `bearerAuth`:
- `GET /update` returns `UpdateState`;
- `POST /update/check` returns `202` and `UpdateState` (also when the check was not started, decision 3);
- `POST /update/install` returns `202` with `phase: installing`, or `409` with an `InstallRefusal`: the `Error` envelope of the contract with an enum `code`, so `ApiError` in the web UI parses it like every other error.

`State` gets `appVersion`, so that the state polling on every page sees an update (decision 12).

`UpdateState`: `installedVersion`, `phase` (`idle | checking | downloading | ready | installing | unsupported`), `latest` (version, notes, `publishedAt`), `lastCheck` (`at`, result `up_to_date | update_found | failed`), `error` (`code`, `message`) and `installAllowed`.
- `UpdateError.code` is an enum: `update_unreachable`, `update_rate_limited`, `update_not_found`, `update_no_apk`, `update_no_checksum`, `update_checksum`, `update_invalid_apk`, `update_signature`, `update_storage`, `update_tls`, `update_failed`, `install_cancelled`, `install_unanswered`, `install_failed`.
- `InstallRefusal.code` is its own enum: `update_not_ready`, `install_not_allowed`, `install_in_progress`, `install_cooldown`. They come from the sealed `InstallRequest`, not from `HttpException` literals.
- Kotlin keeps both as `enum class`; the contract tests reject a code outside them.

`Settings` gets `autoUpdateCheck: boolean` (default `true`), validated with `strictBoolean` like the other switches. `RouteAccessTest` covers the three paths.

### 12. Web UI

- **"Software" section** in `SettingsView` after "Frame": installed and latest version with `publishedAt`; the notes as plain text with `white-space: pre-line`, never as HTML; phase, last check and error; "Check now" and "Install on the frame"; the switch `autoUpdateCheck` with a help text that names GitHub. In phase `unsupported` it says that updates exist only for official builds and hides the buttons. While the section is open it polls `GET /update` at the interval of the display state, every 2 s while `checking`, `downloading` or `installing`. If a shared polling helper exists by then, it uses it.
- **Reload.** Vite's `define` embeds the version of `webadmin/package.json` (equal to `version.txt` through the release pull request) at build time. `startStatePolling` compares it with `State.appVersion` on every page. When both are `X.Y.Z` and differ, the interface reloads itself if no form has unsaved input, and otherwise shows "Frame updated – reload". Not in Vite's dev mode, and not from `GET /info`, whose `appVersion` a planned hardening of the LAN access may move behind the token. While `phase` was `installing`, network errors count as the expected restart. The test server reports the version from `version.txt`, so the e2e run sees no mismatch until a test asks for one.
- **Total translations.** `t()` no longer throws for an `undefined` key, and enum mappings (`SourceCard`, `SettingsView`, the new phase and result) fall back to the raw value.
- **Typed codes.** `errorCodes.ts` gets `Record<components["schemas"]["UpdateError"]["code"], Key>` and the same for `InstallRefusal`, so that `npm run typecheck` fails when a text is missing.

### 13. Restart after the update

`BootReceiver` also accepts `Intent.ACTION_MY_PACKAGE_REPLACED` (manifest). It does what it does after boot: it starts `FrameService`, which reschedules the watchdog, and below API 29 `DisplayActivity`. From API 29 on, the HOME role brings the slideshow back. At start and after `MY_PACKAGE_REPLACED` the app resolves `ACTION_MAIN`/`CATEGORY_HOME` with `MATCH_DEFAULT_ONLY` and logs a warning if the result is not openframe. The app cannot set the preference itself; `addPreferredActivity` is signature-protected. On Android 5 and 6 the session dialog closes by itself after "Install", so there is no "Done" screen to wait for.

### 14. Renderings after an update

The render cache folder carries a format constant: `renders-r1` instead of `renders`. `FrameCore.start()` deletes every other `renders*` folder on the core's scope; not in `RenderCache`'s constructor, because `FrameCore` is created lazily on the main thread, and deleting thousands of files there would block the start. The constant goes up with every change to `MediaEngine.render`, the presentation or the JPEG quality (KDoc at `RenderCache.target`), and it is part of the render key `p`. The current image URL is served `immutable` for a year, so only a new URL makes browsers fetch the new rendering.

### 15. Transparency

`README.md` gets "Updates": the hosts (`api.github.com`, `github.com`, `release-assets.githubusercontent.com`), when the frame contacts them, what they see (the frame's public IP address, the time, the User-Agent `openframe`), that "local only" limits the server but not this outgoing check, the switch `autoUpdateCheck`, the setting for unknown sources, and the rescue over `adb` when an update breaks a frame. "Privacy" names the update check next to the sources.

### 16. Tests without GitHub

- `FakeGithub` runs over TLS like `TrustAnchorsTest`: an HTTPS server with the certificates in `core/src/test/resources/tls` and its root passed through `TrustAnchors.trustManager(extra)`. Cases: an `http` base, an `https`→`http` redirect of the asset, an `http` `browser_download_url`, an unknown root (`update_tls`), `404`, broken JSON, `304`, rate limit with reset.
- A chain test validates recorded chains of `api.github.com` and `release-assets.githubusercontent.com` against the bundled anchors with `CertPathValidator` and a fixed validation date (`PKIXParameters.setDate`), because the recorded leaf certificates expire after 90 days.
- `TestServerMain` puts in a fake release source and a `FakeAppInstaller`, driven through its control port: publish a release, change the installed version, answer the dialog.

## Risks / Trade-offs

- [Android 5.0.1 and 6.0.1 may report `PENDING_USER_ACTION` or `FAILURE_ABORTED` differently for a third-party session] → device rows; if a cancel is not reported, the deadline still returns to the slideshow.
- [The Frameo's firmware may end openframe when the installer starts, as it does for some foreign activities] → the planned end keeps it from counting as a crash, the restart-safe flow reports the result after the restart; a device row checks it.
- [Android 5 may drop the HOME preference on every replacement while other HOME candidates exist] → the log line shows it; once openframe is the only HOME candidate on such a frame, the preference cannot get lost. Disabling other candidates is a device change for which the owner is asked.
- [The installer's stale dialog after `abandon()`] → `DisplayActivity` is brought to the front over it; from API 29 that start may be blocked in the background. A device row checks what is left on the screen; the next request clears the stale dialog's task (see `openSessions` / `abandon`).
- [From API 29 on, starting the dialog from the receiver is a background activity start] → as the HOME app openframe is in front; a request while another app is in front may not open the dialog and ends as `install_unanswered`. The Tab S7 is paused.
- [Unknown sources off on a frame] → state and web UI say so; switching it on is a device change for which the owner is asked.
- [The signature check of Android 5 and 6 is weak] → the structure check closes Janus; immutable releases and the approved sign job keep a manipulated asset from being published.
- [GitHub changes its CA or chain] → extra anchors for the update client; otherwise the rescue over `adb`.
- [60 unauthenticated requests per hour per IP] → about 4 requests a day per frame, `ETag`, the 60-second minimum and the reset.
- [Debug builds carry the frames' key] → a debug build of the same version has the same version code as its release, so the release is not offered over it; installing a debug build over a release is the developer's choice and the README warns against it.
- [Release builds are not debuggable] → `run-as` and `DEBUG_CRASH` are gone on the frames after the first manual install; diagnosis goes through `/api/v1/logs`.
- [Downgrade] → `pm install -r -d` keeps the data on Android 5.0.1 and 6.0.1, but only works if the older version can read the stored files; a new enum value would leave a `.broken` file. Fixing forward stays the rule.
- [Every paired device may install updates] → if the permission model brings owner and guest roles, `/update/check` and `/update/install` move to the owner then.
- [The scope is large] → the implementation follows the task groups; the tests carry the restart and display cases, the device rows only what needs hardware.

## Migration Plan

1. `release-pipeline` and `crash-resilience` are implemented; the environment and the secret exist.
2. Read-only, before any install: pull the installed openframe APK of each frame to a folder outside every repository and compare its certificate with the pinned fingerprint. If a frame carries another key, installing a release there means uninstalling with data loss; the owner decides.
3. This change's pull request (`feat: install updates from GitHub releases`) is squash-merged after the owner's OK. The next release pull request (probably 0.3.0) is merged when the owner says so, and the owner approves the sign job.
4. That release is installed by hand on the Lenovo and the Frameo, each only with the owner's consent at that moment. It replaces the debug build (version code 1) with the same key, so the data stays. It is the last manual install.
5. Regression round on both frames before the next release: start and HOME, pairing, slideshow, schedule, sync, with the scenarios of `kiosk-host` and `slideshow-display`; the crash restart with another trigger than `DEBUG_CRASH`, or the gap accepted. Results in `../openframe-private`.
6. Where another HOME candidate is still installed, openframe becomes the only one after the regression round (the owner's plan per frame is kept privately; each step with consent).
7. The release after that tests the update path on both frames with the device rows.

Rollback: a bad release is fixed forward with a `fix:` release, from safe mode if needed. On a frame that no longer starts the server, the next release is installed over `adb`.

## Open Questions

None. Decided by the owner: two changes; updater off for builds with a foreign certificate; extra anchors for the update client only; the device menu shows only a notice when installing is not allowed; no offline signature; the update download may go below the cache reserve; the first updater release goes onto both frames by hand.
