# Proposal

## Why

Three loose ends are left after 0.14.0, and the owner wants them in one change and one release.

- **Core cleanup (audit package P11).** The re-check of 22 Nov 2026, verified again against `main` (0.14.0), still
  finds three things. `PATCH /sources/{sourceId}` takes the string `"true"` for a switch, because kotlinx
  `booleanOrNull` reads strings as well, while `PATCH /settings`, `PUT /display` and `PATCH /photos` refuse it. The
  contract has always said `boolean`. The checks of JSON fields are copied into five places, and the copies keep
  growing: brightness and `quarantined` came after the audit. Second, `:android` can still reach core internals that
  block on `fsync` or bypass the API's rules, such as `core.signer`, `core.clients.removeAll()` and
  `core.pairing.pair(...)`. Third, two i18n keys are unused and nothing notices new ones.
- **HOME preference lost after an update.** On an Android 5 frame with a second launcher, Android sometimes drops the
  preferred HOME activity while it replaces the package ("Removing dangling preferred activity"). This happened in
  about every second update through the built-in updater; `pm install -r` kept the preference. Afterwards the Home key
  opens Android's chooser instead of the slideshow. An app without system rights cannot set the preferred activity
  itself. Today openframe only logs "openframe is not the HOME app" at start, and nobody sees that log at the frame.
- **Flaky Firefox e2e.** `update.spec.ts` › "Install on the frame: …" sometimes does not show the cancelled text
  within 10 s; a rerun passes. The test server can hand the dialog's answer to the fake installer before the core has
  linked the session to its dialog. The core then drops the answer as "not the open dialog".

## What Changes

- **Strict switches on `PATCH /sources/{sourceId}`** (owner's decision). A switch sent as a string (`"true"`,
  `"FALSE"`) gets 400 `validation_failed`, naming the field, and the whole patch is refused, as on the other PATCH and
  PUT endpoints. **BREAKING** only for hand-made clients that send booleans as text: the web UI already sends real
  booleans, and stored sources are unaffected. The implementation pull request's body carries a release-note line on
  it.
- **One JSON field helper.** A new `internal object JsonFields` (`bool`, `int`) and `HttpRequest.bodyObject()` replace
  the copies in `SettingsPatch`, `SourceConfigStore`, `PUT /display` (switch and brightness), `PATCH /photos`
  (`hidden`, `quarantined`) and the five JSON-object casts. Field messages become uniform ("Expected true or false",
  "Expected an integer from 5 to 1440"); codes and fields stay as they are.
- **Source names on one line.** A control character in a source name becomes a space, as in the frame name, device
  names and comments, instead of rejecting the name (decided in design.md).
- **Narrower visibility in `:core`.** Members that `:android` does not need become `internal`: most `FrameCore`
  members, `HttpRequest.clientId`'s setter, the `ClientRegistry` and `PairingManager` mutators, `UpdateManager`'s
  `check`, `start`, `networkChanged` and `flush`, and `SlideshowController`'s `start`, `setPaused`, `setResting` and
  `control`. `:android` gets two read-only accessors instead of `server` and `localSource`: `serverState` and
  `photoFolder`. The members `:android` and its unit tests use stay public, `clock` included.
- **`requestScan` stays.** Since `library-performance` it has callers (the display turning on, a client's fetch). It
  becomes `internal` with a corrected KDoc.
- **Unused i18n keys.** A Vitest fails on any key of `en.json` that no source file uses; dynamic prefixes are found
  from the source, not listed by hand. `photos.source.nextcloud` and `common.retry` are deleted (owner's decision; a
  later package adds `common.retry` again if it needs it). A probe on `main` finds no other unused key.
- **HOME choice after an update** (owner's decision). After a new installation has replaced an earlier one, the frame
  opens Android's HOME choice once if openframe is a HOME app but Android has no preferred HOME app and would ask.
  The person at the frame can then choose openframe with "Always". The frame does this only when the slideshow is in
  front with the display on and outside safe mode. It does nothing when openframe or another app is the preferred
  HOME app. It never opens the choice again for the same installation, whatever the answer. The choice counts as a
  system screen from the device menu: a planned end, the display held, the record withdrawn when the slideshow is
  back. The log line stays.
- **Flaky install e2e.** The test server answers the installation dialog only once the fake installer has the session
  committed to the open dialog, as on Android. It answers 409 if no dialog opens within a few seconds. The software
  section keeps polling after a failed request, which the spec ("follow changes as in Current state") already
  requires.
- **Not in this change:** owner and guest roles, a later "retry failed" button, a numbers helper for the crop, and the
  `limit` query parsing.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `remote-sources`: "Configuration" counts a value of the wrong JSON type as invalid. New requirement "Source names on
  one line".
- `kiosk-host`: "Restart after an update" points to the HOME choice. New requirements "HOME choice after an update" and
  "HOME choice as a system screen".

The visibility work, the JSON helper on the endpoints that are already strict, the i18n test and the e2e fix change no
spec-level behaviour. The software section's polling after a failed request is already required by `web-admin`.

## Impact

- `api/openapi.yaml` first: the description of `patchSource` says that a switch sent as a string is invalid. No
  schema change; `npm run gen:api` changes descriptions only.
- `core`:
  - `http/JsonFields.kt` (new), `http/Http.kt` (`bodyObject()`, `clientId` with `internal set`),
    `settings/SettingsPatch.kt`, `sources/StoredSource.kt` (strict `bool`, `normalizeName` through `singleLine`),
    `api/DisplayRoutes.kt`, `api/LibraryRoutes.kt`, `api/SettingsRoutes.kt`, `api/SourcesRoutes.kt` and
    `api/SlideshowRoutes.kt`.
  - `FrameCore.kt` (visibility, `serverState`, `photoFolder`, `requestScan`), `auth/ClientRegistry.kt`,
    `auth/PairingManager.kt`, `update/UpdateManager.kt` and `slideshow/SlideshowController.kt`.
  - Tests: `JsonFieldsTest` (new), `SourcesApiTest`, `LibraryApiTest`, `DisplayApiTest`, `SettingsRoutesTest`; the
    test server's `/update/answer` and `FakeAppInstaller`, with a `TestServerMainTest` case.
- `android`:
  - `HomeChoice.kt` (new): the pure decision, reading the HOME apps and the record per installation.
  - `FrameApp.kt` (arming after a replacing installation), `DisplayActivity.kt` (opening the choice, `serverState`,
    `photoFolder`) and `BootReceiver.kt` (KDoc).
  - Tests: `HomeChoiceTest` (plain JVM) and `HomeChoiceOnDisplayTest` (Robolectric, like `AndroidSettingsMenuTest`).
- `webadmin`: `src/lib/i18n/unusedKeys.test.ts` (new), `en.json` and `de.json` (two keys less), the update poll in
  `SettingsView.svelte` with a helper in `src/lib/update.ts`, and its Vitest.
- No new texts on the frame: the HOME choice is Android's own dialog.
- Behaviour visible to users: the HOME choice after an update on a frame that lost its HOME preference, and a 400 for
  switches sent as text on `PATCH /sources`.
- Shipped through the updater in one release. Device rows follow the release: the HOME choice after an update through
  the updater on a frame with a second launcher, and no choice on a frame that kept its preference.
