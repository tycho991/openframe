# Design

## Context

See proposal.md for the motivation. The current state that shapes the approach:

- Every decoding of a foreign file goes through the platform interface `MediaEngine` (`probe`, `readMetadata`, `render`), which works file to file: paths in, a small result or a written JPEG out. Its callers are `LocalPhotoSource` (scan), `RemoteSource` (sync), `PhotoLibrary.check` (upload), `RenderCache` (one render worker thread) and `MetadataBackfill`. The display (`SlideRenderer`) only decodes the display-size JPEG that `RenderCache` wrote.
- `AndroidMediaEngine` runs in the app process, which is also the HOME activity's and the foreground service's process. A native crash in `BitmapFactory`, `BitmapRegionDecoder` or `ExifInterface` ends the display and the server together; after the restart the scan or the slideshow reaches the same file again.
- `FrameApp`'s crash handler logs through the lazy `core` (which may not exist yet), schedules the display restart and kills the process; `FrameService` comes back through `START_STICKY` and its watchdog alarm. Nothing records how a run ended, so native crashes and kills leave no trace in the app log.
- `JsonStore.load` renames an unreadable file to `<name>.broken` at once (an older `.broken` is overwritten) and returns `null`, so the caller starts from defaults: a transient read error loses the settings, the index with all comments, or the identity. `SettingsRepository` then starts with local-only mode off.
- `ClientRegistry` swallows a failed write after a revocation; the revoked token is valid again after a restart. `PairingAdmin.resetAll` can throw midway on a full disk.
- `FrameServer.start` throws when the port is taken, which ends `FrameCore.start` after the slideshow has started; three callers (start, the local-only collector, `onNetworkChanged` on the main thread) restart the server independently.
- `FrameApp` chooses `getExternalFilesDir("photos")` and falls back to `filesDir/photos` when it returns `null`, which happens while the storage is not yet mounted after a boot; the next scan of the empty internal folder removes every local entry with its comment and section.
- The probe picks its sample size from the short edge, so a strip of 500,000 × 127 pixels is decoded at full width.
- app-auto-update is being revised and split into a release-path change and an updater change. The updater needs a way to repair a frame whose new release crashes early, which this change provides.

## Goals / Non-Goals

**Goals:**
- No single file and no single failed write brings the kiosk into a crash loop or loses stored data.
- A native crash while decoding a foreign file ends neither the display nor the server.
- A release that crashes early leaves the frame reachable, so that the updater can install a fixed release (fix-forward).
- The behaviour is testable on the JVM, apart from the decoder process itself and the Android wiring.

**Non-Goals:**
- Recovering from a release that crashes while loading the stores, starting the server or inside `FrameService` itself; only a manual `pm install -r` helps there (documented by app-auto-update).
- Applying scan and sync results in batches.
- A negative cache for Java render failures; they cost CPU, not a crash.
- Changing the rebind after every network change (kept until a device test shows it is not needed).

## Decisions

### 1. A decoder process behind `MediaEngine`

`:android` gets a `DecoderService` declared with `android:process=":decode"` and `android:exported="false"`, and a `RemoteMediaEngine` in the app process that implements `MediaEngine` by calling it. `DecoderService` wraps the existing `AndroidMediaEngine`, so the decoding code itself does not change. `:core` keeps seeing only `MediaEngine`.

The protocol is a raw `Binder`: `onTransact` in the service and `IBinder.transact` in the client, with one transaction code each for `probe`, `readMetadata`, `render` and `pid`. Arguments (paths, sizes, region, quality) and results (format, dimensions, capture date, GPS, or a skip reason with a message) go in the `Parcel`. *Alternatives:* `Messenger` is asynchronous, so every call would need its own reply handler and timeout; AIDL generates the same code with a build feature. A synchronous `transact` blocks the calling worker exactly like the in-process call did, and it throws `DeadObjectException` when the decoder dies during the call, which is precisely the signal for the quarantine.

- **Binding.** `RemoteMediaEngine` binds lazily with `BIND_AUTO_CREATE` from the application context on the first call and waits up to 10 s for `onServiceConnected`, where it asks the service for its pid (again on every connection, so the pid is never stale); it holds the binding while calls come, unbinds after 2 minutes without a call, and then ends the decoder process with `Process.killProcess` on that pid. In safe mode it never binds.
- **Decoder unavailable.** If the service cannot be bound in time, the call throws the new `DecoderUnavailableException` (an `IOException` in `core/.../platform`). Today scan and sync turn any non-`ImageException` into a `corrupt` skip entry and the backfill marks the metadata as read; all three now pass over this exception without recording anything, so the file is checked again at the next scan, sync or backfill run, with a throttled warning. An upload answers 503 `decoder_unavailable`.
- **Death.** A `DeadObjectException` during a call becomes `ImageException(CRASHED)`. After a death `RemoteMediaEngine` unbinds and binds afresh for the next call instead of waiting for AMS to restart the service on the old binding, which AMS does with a growing backoff and may give up after repeated crashes. If more than one call was in flight when the decoder died, each of them is repeated once, one at a time, and only a call that kills the decoder on its own becomes `CRASHED`; the others get their normal result. A single call in flight becomes `CRASHED` at once (the owner's decision: one hit is enough).
- **Hang.** A watchdog ends the decoder process when a call has not returned after 60 s. The call then fails with `DeadObjectException` and is handled as a crash.
- **The decoder process.** `FrameApp.onCreate` reads the process name from `/proc/self/cmdline` (`Application.getProcessName` exists only from API 28). In the `:decode` process it creates no core, no run journal and no alarms; it installs a crash handler that only writes to Logcat and kills the process, because the default handler can keep a process alive behind an error dialog on the Frameo. `DecoderService` catches every `Throwable` of a call (an `OutOfMemoryError` included) and returns it as a result, so only a native crash or a kill ends the process.
- **Memory.** The second process costs its own runtime (estimated 15 to 20 MB). The device test measures it on the Frameo (1 GB RAM) with `dumpsys meminfo`. `android:largeHeap` applies to both processes.

*Alternative:* in-process decoding with marker files written before each native step and evaluated at the next start (the audit's variant A). It keeps one process, but every native crash still ends the display and the server, AMS may mark the process as bad, and a kill by the firmware during a render cannot be told apart from a crash. The owner chose the separate process now.

### 2. Quarantine and the `crashed` skip reason

`SkipReason` gets `TOO_LARGE` and `CRASHED`. What `CRASHED` does depends on the caller:

- **Scan and sync** (`LocalPhotoSource.read`, `RemoteSource.fetch`): the file becomes a skip entry with the reason `crashed`. Scan and `needsDownload` skip it until size or modification time change, as for any skip entry. For a sync the size and time come from the listing, so that the entry matches the next listing. A changed file of a photo already in the library goes the same way as any changed file that cannot be read today (`LibraryEdit.skip` removes the entry with its comment, section and hidden state); keeping such entries is left to a later change.
- **Render** (`RenderCache`): the photo entry gets `quarantinedVersion = version`. `Library.visible` (the slideshow's list) leaves it out, `RenderCache.get` and `prefetch` refuse it without calling the engine, and the image route answers 503 `render_failed`, also for old browser tabs. `Library.isVisible`, which the API reports as `visible` and filters by, stays unchanged; otherwise a quarantined photo would show as hidden. `Album.newest` becomes nullable and takes the newest photo that is not quarantined; an album with only quarantined photos has the cover `null`, which the contract already allows.
- **Backfill** (`readMetadata`): the photo's metadata version is set to the current one without a GPS position, so it is not read again.
- **Upload** (`PhotoLibrary.check`): the upload is rejected like an unsupported format (415 `unsupported_format`), and the log names the file.

`PATCH /photos/{id}` with `quarantined: false` clears `quarantinedVersion`; a new version of the file clears it as well, because the stored version no longer matches. The photo list reports `quarantined: true` for an entry whose `quarantinedVersion` equals its version.

### 3. Backward-compatible storage of the new fields

`PhotoEntry.quarantinedVersion` is a new optional field that older builds ignore. A skip entry with a new reason is stored as `reason: CORRUPT` plus an optional `detail` (`too_large`, `crashed`), following the pattern of `hiddenAlbums`; the API reports `detail ?: reason`, and an unknown stored reason loads as `CORRUPT`. A debug build installed over this one therefore still reads `index.json` instead of setting it aside and starting with an empty library without comments.

### 4. Image limits

After reading the bounds, `AndroidMediaEngine` rejects images over 100 megapixels or with an aspect ratio over 32:1 as `TOO_LARGE`. The probe picks its sample size from a budget of about one megapixel (doubling while `(w/s)·(h/s)` is above 1,000,000) instead of from the short edge. An `OutOfMemoryError` in the probe becomes `TOO_LARGE` (a valid photo that is only too big for the heap is not "damaged"); one in `render` becomes `CORRUPT` for this render only, which is not stored. `JvmMediaEngine` applies the same limits. The KDoc of `MediaEngine` names the limits, that only `ImageException` and `IOException` (including `DecoderUnavailableException`) are thrown, and that the methods are called from several threads at once (render worker, scan, syncs, backfill, uploads); the current sentence "run on the render worker" is wrong.

An upload over the limit answers 415 `image_too_large`. The callers outside the engine (`RemoteSource.fetch`, `SourceScheduler.sync`, `MetadataBackfill`) catch every `Throwable` except `CancellationException` and `DecoderUnavailableException` (decision 1), and `RenderCache` wraps an `Error` in `RenderFailedException`, so that a fake or future engine cannot end a coroutine or the render worker.

*Limit:* the owner fixed 100 MP without a device test. A 200 MP phone photo is skipped as `too_large`; 48 MP photos stay within the limit, and "Rendering a section within memory" stays as it is.

### 5. Run journal and safe mode

A new package `core/.../health` holds `RunJournal`. It uses plain `java.io`, never throws, and keeps its state in `state/health/runs.json`, written through the atomic file helper (decision 7).

- **Start.** `FrameApp.onCreate` (app process only) installs the crash handler first, then calls `journal.onProcessStart(wallMs, elapsedRealtime, bootId, installId)`. `bootId` comes from `/proc/sys/kernel/random/boot_id` if readable. `installId` is `versionCode` plus `PackageInfo.lastUpdateTime`; a debug build has the same `versionCode` as the release it was built from, so the version code alone would count every test installation as an unexplained end.
- **Classification of the previous end.** `replaced` (other `installId`), `planned` (a valid record, decision 6), `rebooted` (other `boot_id`, or without one an `elapsedRealtime` smaller than the last one the previous run recorded; the journal records it at start and once a minute while running, without `fsync`, because two starts right after boots would otherwise both sit near the same uptime), `crashed` (a Java crash, recorded by the crash handler with its exception class) and `died` (everything else: a native crash, a kill by the firmware, the low-memory killer or the system). The result is one log line at start, including whether `boot_id` was readable.
- **Early ends.** A run that ended `crashed` or `died` before `markStable()` (called by the core after 10 minutes in normal mode) is an early end. Three in a row lead to safe mode; a stable run, `rebooted`, `planned` and `replaced` reset the count.
- **Crash handler order.** First `scheduleRestart` in its own `try`; from the third early end in a row it uses a growing delay (30 s, 2 min, then 10 min) so that the display does not cover an open installation dialog, while the service returns through `START_STICKY` and its watchdog anyway. This only works for Java crashes: after a `died` end the system may bring the HOME activity back by itself, and the spec limits the delay accordingly. Then `journal.onJavaCrash(class)`, then `core.log.e` only if the lazy core already exists, then `killProcess`.
- **`startCore`** catches a `Throwable` only to log it safely and throws it again; a swallowed start failure would leave a half-started core that never counts as an early end.
- **Safe mode in the core.** `FrameCore` gets `health: Health` and the new platform interface `ProcessControl` in `core/.../platform` (`restart(reason)`; on Android `plannedExit`, `scheduleRestart`, `killProcess`; a JVM fake in tests). In safe mode `start()` loads all stores and starts the rebind path (decision 8), pairing, settings, the display schedule, the routes and the updater hook, but not the slideshow, the scan, the source schedulers, the backfill or the prefetch. The image, upload and sync routes answer 503 `safe_mode`; delete, hide and settings work. Adding, changing or reconnecting a source stores its configuration without launching a scheduler or requesting a sync (`SourceRegistry.add`, `patch` and `reconnect` do both today), and a network change does not reach the sources; the sources start with the next normal run.
- **Leaving.** A new `installId` ends safe mode at the next start. "Start normally" in the device menu and `POST /safe-mode/exit` call `ProcessControl.restart`; the API answers 202 first and restarts about a second later, and answers 409 `not_in_safe_mode` outside safe mode. The automatic probe restarts the app in normal mode 1 hour after safe mode began, then after 2, 4, 8, 16 and 24 hours. The run after any of these exits is a probation run: an early end in it leads straight back into safe mode, a stable run ends the probation.
- **The display.** `DisplayActivity` reads the health state in `onCreate`. In safe mode it creates neither `SlideRenderer` nor `GpuProbe` and shows a static notice with the address or the port state. The device menu keeps local-only mode, device administration, unpairing and info, and gets "Start normally"; app-auto-update adds its update entry there.

### 6. Planned exits

`health.plannedExit(reason, validForMs)` records the reason, the `boot_id` and the expiry; `cancelPlannedExit()` removes it. An expired record does not count. The app records it before restarting from the device menu or the API, before starting a system activity (device administration; the setting for unknown sources from app-auto-update) with a short validity, because the Frameo's firmware may end the app when another activity starts. The comment in `FrameService` about the watchdog alarm is corrected: AOSP refuses to start a process that AMS marked as bad from an alarm.

### 7. Stores: load, backup, broken files, critical writes

- **Atomic file helper.** The write path of `JsonStore` (temporary file, `fsync`, `rename`) becomes `AtomicFile` in `core/.../store`, used by `JsonStore`, `RunJournal` and the stored photo root (decision 10).
- **Load.** An `IOException` while reading is retried three times with 100 ms pauses. A format error, an `OutOfMemoryError` or a `StackOverflowError` while decoding, or a lasting read error returns `null` and marks the store unreadable without renaming anything. The first write over an unreadable file first renames it to `<name>.broken-<epochMs>`; the three newest by modification time are kept (the Frameo's clock is wrong after a boot), an existing broken file is never overwritten, and an old `<name>.broken` of earlier builds stays untouched.
- **Backup.** With `backupOnLoad`, a successful load writes `<name>.bak` once per start (a full disk is caught), and `load` falls back to it when the main file is unreadable.

| File | `.bak` | Why |
|---|---|---|
| `index.json`, `settings.json`, `identity.json`, `transitions.json` | yes | losing them loses comments, sections, settings or the frame's ID |
| `clients.json` | no | a backup would bring back revoked tokens |
| `secret.json` | no | a new key is the safe fallback |
| `sources.json` | no | it would bring back removed sources with their credentials |
| `sources-status.json`, `runs.json` | no | status only |

The updater's `update.json` (app-auto-update) needs no backup either.

- **No secrets in the log.** `StoreJson` sets `exceptionsWithDebugInfo = false`; a format error is logged with the file, the exception class and the offset only, without its message (which quotes the input) and without a stack trace.
- **Local-only fail-closed.** `SettingsRepository.load` checks `exists()` first, like `SourceConfigStore.load`. If `settings.json` exists but is unreadable, local-only mode is turned on in memory for this run, also when the other values come from `.bak` (that copy is from the previous start and may predate turning local-only on), with an error in the log. It is not saved: saving would be the first write over the unreadable file and set it aside, which would turn a short read error into lost settings. The next start reads the file again and forces the mode again if it is still unreadable; once the owner changes a setting or turns local-only mode off at the frame, the file is written and the broken one kept. The existing frame hint shows local-only mode; no new UI is needed.
- **Sources kept.** `SourceRegistry.start` removes the photos of sources that are not stored (`removeOrphans`). When `SourceConfigStore.load` reports `UNREADABLE`, it skips that step, so an unreadable `sources.json` does not delete the remote photos with their comments and sections; they stay until the file is readable again or a source is removed.
- **`saveCritical`.** `JsonStore.saveCritical(value)` takes the write lock, replaces any pending state and tries a chain that needs no extra space: write atomically; else overwrite in place (`RandomAccessFile`, write, `setLength`, `fsync`) if the new content is not longer; else delete the file. In-place writing is not atomic, so it is allowed only for files whose unreadable state is fail-closed: `clients.json` (no clients, everyone pairs again) and `secret.json` (a new key). `ClientRegistry` uses it after a revocation (the content only gets shorter); `ImageSigner.rotate` sets the new key at once and stores it with it. `PairingAdmin.resetAll` guards each step and resets the pairing code and writes its log entry in a `finally`; `FrameCore.resetAllPairings` catches everything except cancellation. The owner chose fail-closed over answering 507 and keeping the revocation only until a restart.
- **Start on a full disk.** `FrameIdentity.loadOrCreate` and `ImageSigner.load` keep a new value in memory and write it later instead of throwing.

*Accepted residual:* `sources.json.broken-*` keeps credentials in the private storage (at most three files) that cannot be revoked because the file cannot be read.

### 8. One rebind path

`FrameServer.start` catches the `IOException` of the bind, logs it (throttled) and publishes `state: StateFlow<ServerState>` (`Running(port)` or `Failed(message)`). `FrameCore` gets one coroutine with a conflated channel that handles the first start, local-only changes, network changes and a retry timer (30 s, doubling to 5 min) one after another, so the callers no longer race and nothing binds on the main thread. It also runs in safe mode. `adminUrl()` returns `null` while unbound; the frame hint and the empty state tell "no network" from "admin interface not reachable (port taken)" (`values`, `values-de`). There is no blanket coroutine exception handler; the self-healing restart stays the last resort.

### 9. The interface for app-auto-update

app-auto-update builds on this, in `core/.../health`:

- `core.health.state: StateFlow<HealthState>` with `mode` (`NORMAL` or `SAFE`), `safeMode` (`since`, `reason`) or `null`, `recentEarlyEnds` and `previousEnd`.
- A new installation (other `versionCode` or `lastUpdateTime`) always counts as the planned end `replaced` and ends safe mode; the updater needs no record for it.
- `health.plannedExit(reason, validForMs)` before committing the `PackageInstaller` session, valid beyond the 10 minutes the confirmation dialog may stay open; this also covers the Frameo's firmware ending the app when the installer starts. `health.cancelPlannedExit()` on cancel, failure or abandon.
- The update manager runs in both modes. Its first check runs at once when `mode == SAFE` or `recentEarlyEnds > 0`, otherwise after 3 minutes. Every step catches everything except `CancellationException` and reports `update_failed`; a test feeds it broken JSON from the fake GitHub.
- The decoder process is not bound in safe mode, so a broken decoder cannot keep the updater from running.

### 10. The photo folder is held

A pure function in `:core` (`PhotoRoot.resolve`) picks the folder from the stored path, the external path and the storage state. The result is stored as an absolute path in `state/storage.json` through `AtomicFile`, not `JsonStore`, because `FrameApp` needs it before the core exists. `FrameApp` takes `getExternalFilesDir("photos")` when it returns a path (and stores it), otherwise the stored path. `filesDir/photos` is used only if no path was ever stored and `Environment.getExternalStorageState()` reports that there is no external storage at all (`removed` or `unmountable`), as on a device without shared storage. Storage that is only not mounted yet makes the folder unavailable until a later check.

`PlatformInfo.storageMounted(dir)` is new (Android: `Environment.getExternalStorageState(File) == MEDIA_MOUNTED`, the internal folder counts as mounted; default `true`, controllable in the JVM fake). `LocalPhotoSource.scan` checks it before `mkdirs()`: if the storage is not mounted or `root.list()` returns `null`, the scan creates nothing, removes nothing and logs a throttled warning. A folder missing on mounted storage is created again and its entries removed as before. `PhotoLibrary.upload` answers 503 `storage_unavailable` before creating the staging folder. The empty state names the real folder; for the internal folder it says that photos come only through the web interface. `FrameCore.start` deletes `photos/.upload/*.part` before the first bind when the folder is available.

### 11. The log

- `AppLog.log` never throws: the file append and every sink sit in their own `try`. After a file error the file log pauses for 60 s; the memory buffer and Logcat go on. `recent()` reads the file while it is readable and appends the entries kept only in memory since the pause; without a readable file it returns the buffer. With a file log the buffer keeps 100 entries, without one 1,000.
- Stack traces are cut to 30 frames per cause, entries to 8 KiB.
- Identical errors (level, tag, message, exception class) within 10 minutes: the first with its stack trace, then at most every 10 minutes one line "still failing (N times since …)", and one line when it recovers. This affects the store writer's retry (every 10 s) and the sync status poll.
- `RotatingFileLog` escapes `\r` and every character below 0x20 in addition to `\n`, `\t` and `\\`, and unescapes them.
- Device and frame names go through one normalization like `Comments.normalize` (control characters become a space) before trimming and the length rules; the contract describes it for `deviceName`, `ClientUpdate.name` and `Settings.name`.

### 12. API

`api/openapi.yaml` first, then the Kotlin DTOs by hand, then `npm run gen:api`:

- `SkippedFile.reason`: `unsupported`, `corrupt`, `too_large`, `crashed`.
- `Photo.quarantined` (required boolean); `PhotoPatch.quarantined` accepts only `false`.
- `State.safeMode` (required boolean), so the web UI can show its banner from the existing poll.
- `POST /safe-mode/exit`: 202 with an empty object, 409 `not_in_safe_mode`, 401 without a token, 403 for a foreign origin like every other mutating operation. `RouteAccessTest` keeps router and contract in step.
- 503 `safe_mode` on `GET /photos/{id}/image`, `POST /photos` and `POST /sources/{id}/sync`; 503 `storage_unavailable`, 503 `decoder_unavailable` and 415 `image_too_large` on `POST /photos`.

### 13. Test hooks

- A debug-only broadcast `DEBUG_DECODE_CRASH` makes the decoder process call `Process.sendSignal(myPid, 11)` in its next render. The receiver is registered in the app process with the broadcast permission `android.permission.DUMP`, which only the shell (adb) holds, and forwards the request to the decoder through a transaction.
- `TestServerMain` gets control commands that quarantine a photo and set safe mode, so that e2e can check the placeholder, the missing image requests and the banner.

### 14. Three pull requests under this change

1. **Hardening:** log, stores with backup and `saveCritical`, rebind path, image limits, photo folder, name normalization; contract: `too_large`, 415 `image_too_large`, 503 `storage_unavailable`.
2. **Decoder process and quarantine:** `DecoderService`, `RemoteMediaEngine`, `CRASHED`, quarantine, web UI placeholders; contract: `crashed`, `Photo.quarantined`, `PhotoPatch.quarantined`.
3. **Run journal and safe mode:** `health` package, crash handler, safe mode in core and display, "Start normally", `POST /safe-mode/exit`, banner; contract: `State.safeMode`, 503 `safe_mode`, the new endpoint.

The overlapping packages (lan-hardening: log, image signer, client registry; the later remote source, scan and web UI packages) rebase onto this change.

## Risks / Trade-offs

- **False quarantine** → when the low-memory killer or the firmware ends the decoder process during a call, the file counts as crashed. Mitigation: the log line, "Try again" for photos; a skipped file from a scan or sync needs a change to the file. With several calls in flight, only the call that kills the decoder alone is quarantined.
- **Crash dialog for the decoder** → on Android 5 and 6, AMS may show an error dialog for the crashed `:decode` process over the slideshow. The device test checks it on the Lenovo and the Frameo; the quarantine makes it happen at most once per file.
- **Repeated decoder crashes** → AMS restarts a crashed service with a growing backoff and may stop after several crashes in a short time. Binding afresh after each death avoids depending on that; if binding still fails, files are retried later instead of being skipped. The device test checks the restart timing.
- **Memory of the second process** → about 15 to 20 MB on the Frameo while bound; it is unbound and ended after 2 minutes without decoding. Measured in the device test.
- **Latency** → a binder call and, after an idle period, a process start before the first render; a render already takes hundreds of milliseconds on the reference devices.
- **False safe mode** → three unexplained early ends in a row, for example by firmware kills, pause the photos until the probe or someone at the frame or in the browser starts normally. Planned-exit records before system activities lower the risk. Without a readable `boot_id`, a device restart within the first minute of a run, or after a power loss that dropped the last uptime record, may count as an early end.
- **"Start normally" and the probe end the process** → on the Frameo the firmware may refuse an activity start from the background; the server then returns through the watchdog and the display with the Home button (device test).
- **Safe mode does not cover every crash** → a crash while loading the stores, starting the server or in the service itself still loops; only a manual install of a fixed release helps.
- **Fail-closed has costs** → after a full disk and a restart every device may have to pair again; with an unreadable `settings.json` the frame is local-only until the file reads again or someone switches it at the frame. An in-place write interrupted by a power loss also makes `clients.json` unreadable, which is fail-closed as well.
- **The `.bak` fallback restores an older state** → comments and sections since the last start are missing, new photos are scanned again; better than an empty index.
- **Downgrade to an older debug build** → it drops `quarantinedVersion` and `detail` when it rewrites `index.json`, but still reads it.
- **Size** → three pull requests touching many files that the next packages (lan-hardening, sources, scan, web UI) also change; this change lands first.

## Migration Plan

- No migration of stored data: the new fields are optional, the skip detail is backward compatible, `storage.json` is written at the first start of the new version from the folder it would have chosen anyway.
- Frames updated by hand (debug builds) during development; the first release with the updater includes all three pull requests.
- Rollback: installing the previous build keeps the data readable (decision 3); `runs.json`, `storage.json` and `.bak` files are ignored by it.

## Open Questions

None. The owner decided: one hit is enough for a quarantine, but only for a death during a decoding; safe mode is left by a new installation, "Start normally", an automatic probe and the API; the decoder process comes now; a failed write of the client list fails closed; local-only mode is forced on when `settings.json` is unreadable; the pixel limit is 100 MP without a device test.
