# Proposal

## Why

A single bad file or a single failed write can bring the kiosk into a crash loop today: a native crash of the image decoder kills the HOME and server process, the next scan decodes the same file again, and an unreadable store file is renamed away so that settings, comments or pairings are silently lost. Once the frames update themselves (app-auto-update), a broken release must also not leave a frame unreachable: the updater needs a safe mode from which it can install a fixed release. That is why this change comes before the updater and defines the interface the updater builds on.

## What Changes

- **Image decoding in its own process.** Probe, render and EXIF reading of photos run in a bound service in the process `:decode`. The app process only loads the JPEGs it rendered itself. A native crash of a decoder ends only the decoder process; the app notices it during the call.
- **Quarantine after a crash.** A file whose check killed the decoder is skipped with the reason `crashed` until it changes; a photo whose rendering killed it is quarantined: it stays in the library, is never rendered or shown, and can be hidden, deleted (local photos) or retried from the web UI. If the decoder cannot be started, files are checked again later instead of being skipped.
- **Image limits.** Images over 100 megapixels or with an aspect ratio over 32:1 are skipped as `too_large` (upload: 415 `image_too_large`); the probe decodes at most about one megapixel; an out-of-memory error while decoding skips the file instead of ending the app.
- **Safe mode.** After three early ends in a row of the app process, the app starts in safe mode: the server, pairing, settings, display schedule, log and device menu run (and the update check that app-auto-update adds), the slideshow, the sources and all image work rest. It ends with a new installation, "Start normally" on the frame, a paired client through the API, or an automatic probe after 1 h (doubling to 24 h).
- **Run journal.** At each start the app records how the previous run ended (Java crash, unexplained end, device restart, new installation, planned end) and logs it, so native crashes and kills become visible. Planned ends (update, restart from the device menu, starting a system screen) are announced with a validity and never count as crashes.
- **Robust log.** Writing the log never throws; repeated identical errors collapse into one entry with a counter; stack traces and entries are capped; control characters are escaped, and device and frame names are normalized.
- **Robust stores.** An unreadable store file is no longer discarded: read errors are retried, a copy from the last start serves as fallback for index, settings, identity and transitions, and broken files are kept (the last three). An unreadable `settings.json` turns local-only mode on (fail-closed); an unreadable `sources.json` no longer removes the remote photos. Log entries about broken files contain no secrets.
- **Revocations survive a full disk.** Revoking a pairing and "Unpair all devices" stay effective across a restart even when the storage is full; if the client list cannot be written at all, it is deleted and every device pairs again (fail-closed).
- **Server port taken.** A bind failure no longer stops the start; one rebind path retries with backoff and after network or local-only changes, and the frame shows the state instead of a wrong address.
- **Photo folder held.** The chosen photo folder is stored; there is no silent switch to internal storage, a scan removes nothing while the storage is unmounted, and uploads answer 503 `storage_unavailable`. Partial uploads left by a crash are deleted at start.
- **API.** `SkippedFile.reason` gains `too_large` and `crashed`; `Photo.quarantined`, `PhotoPatch.quarantined` (only `false`, to retry), `State.safeMode`; new `POST /safe-mode/exit`; new error codes `safe_mode` (503), `storage_unavailable` (503), `decoder_unavailable` (503) and `image_too_large` (415).
- The work lands in three pull requests under this change: hardening (log, stores, revocations, port, limits, photo folder), decoder process with quarantine, run journal with safe mode.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `kiosk-host`: "Self-healing" logs the end of the previous run and slows the display restart after repeated early ends; "Diagnostics" never fails on log errors and collapses repeats; new requirements for the decoder process, safe mode, leaving safe mode, planned process ends and an unavailable server port.
- `photo-library`: "Local photo folder" removes no entries while the storage is unavailable; new requirements for quarantine after a decoder crash (skipped files and quarantined photos), image size limits, keeping the chosen photo folder, and deleting partial uploads at start.
- `remote-sources`: new requirement that an unreadable list of sources does not remove the remote photos.
- `frame-settings`: "Durable, atomic storage" keeps unreadable files and falls back to the copy of the last start; "Frame identity" normalizes control characters in the name.
- `admin-access`: "Local-only mode" turns on when `settings.json` is unreadable; "Manage tokens" and "Reset all pairings on the frame" stay effective on a full disk; new requirement for device names without control characters.
- `nextcloud-source`: "Secrecy of the credentials" covers log entries about an unreadable `sources.json`.
- `slideshow-display`: "Skip faulty files" covers memory errors and quarantined photos.
- `web-admin`: "Skipped files" names the new reasons; new requirements for quarantined photos in the interface and for the safe mode notice with its exit.

## Impact

- `:core`: new package `health` (run journal, safe mode state, planned exits), the platform interface `ProcessControl`, `JsonStore` (load, backup, `saveCritical`, atomic file helper), `AppLog`, `FrameServer` and a rebind path in `FrameCore`, `ClientRegistry`, `ImageSigner`, `PairingAdmin`, `SettingsRepository`, `FrameIdentity`, `PhotoIndex` (`quarantinedVersion`, skip detail), `RenderCache`, `RemoteSource`, `SourceScheduler`, `MetadataBackfill`, `LocalPhotoSource`, `PhotoLibrary`, `MediaEngine` (limits, `TOO_LARGE`, `CRASHED`, `DecoderUnavailableException`), `SourceRegistry` (no orphan removal for an unreadable `sources.json`, no launch in safe mode), routes for library, sources, state and the new safe mode exit.
- `:android`: new `DecoderService` (process `:decode`) and `RemoteMediaEngine`, `FrameApp` (process check, crash handler order, run journal), `DisplayActivity` (safe mode screen, "Start normally", port state), `FrameService`, `AndroidPlatformInfo` (`storageMounted`), manifest, strings in `values` and `values-de`.
- `api/openapi.yaml`, Kotlin DTOs, the generated web UI types; web UI components for the photo grid, detail view, albums, skipped files, a safe mode banner and the error codes, with `en.json` and `de.json`.
- Tests: JVM tests for the journal, stores, log, rebind and quarantine with a dying fake engine; Robolectric tests for the crash handler and the engine limits; contract tests; e2e through control commands of the test server.
- Device rows in `../openframe-private/DEVICE_TEST.md`: decoder crash on Lenovo and Frameo, memory of the decoder process on the Frameo, safe mode in and out, occupied port, oversized PNG, restart without index loss.
- app-auto-update (both changes after its split) builds on the `health` interface of design decision 9 and is revised after this change's artifacts are merged.
