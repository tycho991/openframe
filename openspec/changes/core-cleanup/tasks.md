# Tasks

One implementation pull request for the whole change. Line references are to `main` at 0.14.0 (ea357e6); check them
again before editing.

## 1. Contract first

- [x] 1.1 `api/openapi.yaml`, before any Kotlin: the description of `patchSource` says that every field is checked by
  its JSON type and that a switch sent as a string (`"true"`) is invalid and refused with 400 `validation_failed`
  naming the field, with nothing changed (design decision 2). `SourceSettingsPatch` keeps `type: boolean`; no schema
  changes, so no DTO changes. Then `npm run gen:api` in `webadmin/`. Verify: `npm run lint` (Redocly included) and
  `npm run typecheck` are green, the generated types differ only in descriptions, and `RouteAccessTest` stays green.

## 2. Strict JSON fields in one helper

- [x] 2.1 Failing tests first, run against `main` and noted in the pull request:
  - `SourcesApiTest` (`TestFrame`, contract check on): `PATCH /api/v1/sources/{id}` with `{"enabled":"true"}`,
    `{"includeSubfolders":"FALSE"}` and `{"removeVanished":"false"}` each gives 400 `validation_failed` naming exactly
    that field (`remote-sources` › Configuration › Switch sent as text); `{"name":"New","includeSubfolders":"false"}`
    is refused as a whole and `GET` shows name and switch unchanged (› Valid and invalid field together);
  - `LibraryApiTest`: `{"hidden":"true"}` added to the table of invalid photo patches; `{"quarantined":"false"}` gives
    400 naming `quarantined`; `PATCH /albums` with `"hidden":"true"` gives 400 (already refused by `ApiJson`, kept as
    a guard);
    *(Done without the albums guard: `ApiJson` accepts `"true"` for `AlbumPatchDto.hidden`; see design.md, After
    implementation.)*
  - `PATCH /photos/{id}` with the body `[true]` gives 400 `bad_request` with the message "Expected a JSON object".

  Verify: the sources cases fail on `main` (as contract violations or 200). The `[true]` case fails on `main` only in
  its message: today it answers 400 `bad_request` "Invalid body" (`Http.kt:70-82` through `LibraryRoutes.kt:325`),
  and 2.3 changes that. The other cases pass already.
- [x] 2.2 `core/.../http/JsonFields.kt` with `internal object JsonFields` (`bool`, `int`, both taking the error map) and
  `HttpRequest.bodyObject()` in `http/Http.kt` (design decision 1). New `JsonFieldsTest`: `bool` for `true`, `false`,
  `"true"`, `"TRUE"`, `1`, `JsonNull`, `[]` and an object; `int` for `20`, `"20"`, `20.5`, `1e1`, both range limits and
  one past each; the messages "Expected true or false" and "Expected an integer from 5 to 1440". Verify: the test
  passes and `./gradlew :core:check` is green.
- [x] 2.3 Switch the callers (design decision 1): `SourceConfigStore.patch` (drop its private `bool` and `int`),
  `SettingsPatch` (drop `strictBoolean`, `bool`, `int`; `autoUpdateCheck` and `brightness.level` included),
  `PUT /display` (`on` and `brightness` into its `fields`, still `bad_request`), `photoPatch` (`hidden`, and
  `quarantined` through `bool` with its own message kept), and `bodyObject()` in `SettingsRoutes`, `SourcesRoutes`,
  `DisplayRoutes`, `SlideshowRoutes` and `PATCH /photos`. Verify: the tests of 2.1 pass; `DisplayApiTest` (`{"on":
  "false"}`, `[true]`), `SettingsRoutesTest`, `MalformedJsonTest` and `SlideshowApiTest` pass unchanged;
  `grep -rn "strictBoolean\|booleanOrNull\|as? JsonObject ?: throw" core/src/main` finds only `JsonFields.kt` and
  `bodyObject()`.
- [x] 2.4 Source names through `singleLine` (design decision 3): `SourceConfigStore.normalizeName` trims with
  `singleLine` and then checks 1 to 64 characters. Verify, `SourcesApiTest` with the contract check: a rename to
  "Summer\n2026" stores "Summer 2026" (`remote-sources` › Source names on one line › Name with a line break); a name of
  only control characters gives 400 naming `name` and the old name stays (› Only control characters); a
  `NextcloudConnectorTest` case: a share whose name has a tab is added with that name on one line, not with the host.

## 3. Visibility in `:core`

- [x] 3.1 `FrameCore` (design decision 4): `signer`, `localSource`, `trash`, `sources`, `nextcloud`, `cache`, `library`,
  `places`, `visiblePhotos`, `router`, `server`, `reserveBytes` and `requestScan` become `internal`; new public
  `serverState` and `photoFolder`; `DisplayActivity` uses them instead of `server.state` (:354, :531, :546) and
  `localSource.root` (:553). `requestScan` gets the KDoc of decision 4. Verify: `./gradlew check` with the Android SDK
  from `local.properties` (so `:android` compiles) is green; `ScanCadenceTest` and `FrameCoreTest` pass.
- [x] 3.2 `HttpRequest.clientId` with `internal set` and the KDoc "Set only by `HttpPipeline`"; `ClientRegistry`'s
  `load`, `create`, `authenticate`, `remove`, `rename`, `removeAll`, `flush` and `PairingManager`'s `setScreenUsable`,
  `requestDisplay`, `tick`, `pair`, `pairWithSecret`, `createInvite`, `dropInvite`, `reset` become `internal`; the
  fsync KDoc on `ClientRegistry.create`, `remove`, `rename`, `removeAll`, `PairingManager.pair`, `pairWithSecret`,
  `ImageSigner.rotate`, `load` and `PairingAdmin.resetAll`. Verify: `./gradlew check` with the SDK is green; locally,
  without committing, a call of `core.signer.rotate()`, `core.clients.removeAll()` or `core.pairing.pair(…)` in
  `DisplayActivity` does not compile.
- [x] 3.3 `UpdateManager.check`, `start`, `networkChanged` and `flush` and `SlideshowController.start`, `setPaused`,
  `setResting` and `control` become `internal`; `state`, `current()`, `install()` and `onInstallResult()` and the
  display's slideshow members stay public. Verify: `UpdateApiTest`, `SlideshowControllerTest`, `SlideshowApiTest`,
  the `:android` unit tests (`UpdateOnDisplayTest`, `UpdateRestartTest`, `AndroidSettingsMenuTest`) and
  `RouteAccessTest` pass.

## 4. Unused i18n keys

- [x] 4.1 `webadmin/src/lib/i18n/unusedKeys.test.ts` per design decision 5 (`node:fs`, `*.ts` and `*.svelte` under
  `src/` without `*.test.ts`, quoted literals, prefixes from template literals and from `option("…"`). Verify: it fails
  on `main` naming exactly `common.retry` and `photos.source.nextcloud`, and a key added only to `en.json` and `de.json`
  for a moment is reported as well.
- [x] 4.2 Delete `common.retry` and `photos.source.nextcloud` from `en.json` and `de.json`. Verify: `npm test`
  (`unusedKeys.test.ts` and `detect.test.ts` included), `npm run lint` and `npm run typecheck` are green.

## 5. HOME choice after an update

- [x] 5.1 `android/.../HomeChoice.kt`: the pure `HomeChoice.status(resolved, candidates, own)` and
  `HomeChoice.decide(record, installId, status, safeMode, displayShowing)` (design decision 6), `HomeApps.read(pm,
  own)` with `resolveActivity` and `queryIntentActivities` (`MATCH_DEFAULT_ONLY`), and the record in the
  `SharedPreferences` file `home_choice` (`"<installId>|pending"` or `"|done"`, written with `commit()`). New
  `HomeChoiceTest` (plain JUnit, no Robolectric) with the table of design decision 8. `AndroidManifest.xml` gets an
  `<intent>` with action `MAIN` and category `HOME` in `<queries>` (package visibility from Android 11, design decision
  6), with a comment naming the spec; `ManifestTest` checks it. Verify: the tests pass; Lint stays green; no AndroidX
  import. On an Android 17 emulator with a second launcher (main session, since Robolectric does not enforce package
  visibility): with the other launcher preferred, the start's warning names it; with no preferred HOME app, the
  warning names the system's resolver, and reinstalling the debug build with `adb install -r` opens the choice with
  both launchers listed.
  *(Emulator check done by the main session on Android 17 and Android 5; results in design.md, After
  implementation.)*
- [x] 5.2 Arming in `FrameApp.start(processName)`, synchronously, right after `journal.onProcessStart(…)`
  (`FrameApp.kt:154`) and before anything else of the process runs (design decision 6, "Arming"). Write `pending` with
  `commit()` for the `installId` passed to the journal when the result is `RunEnd.REPLACED` and no record for it
  exists. If `commit()` returns false, log to Logcat and write nothing (Android keeps the value in memory anyway; see
  design.md, After implementation). Add the seams `homeApps: () -> HomeApps` and the
  record's store, like `coreFactory`. `BootReceiver`'s KDoc names the choice. Verify, Robolectric:
  - `REPLACED` writes `pending` before any core exists; `FIRST` and `REBOOTED` write nothing; a second start of the
    same installation keeps `done` (`kiosk-host` › HOME choice after an update › Restart without an update);
  - with a `coreFactory` whose core throws in `start()`, the record is still `pending` after `FrameApp.start`;
  - a store whose write fails leaves no record and nothing opens later.
- [x] 5.3 Opening in `DisplayActivity` (design decisions 6 and 7): evaluated while resumed and the core is ready, once
  when `core.ready` turns true, in `onResume`, and on every change of `core.display.state` and `core.display.dark`. On
  `OPEN`, write `done` with `commit()`. Only if it returns true, call `core.systemScreenOpened("home choice")`, write
  the log line and `startActivity` the HOME intent; otherwise log `HOME choice not opened: its record could not be
  written` and open nothing. `ActivityNotFoundException` closes the system-screen record. Verify,
  `HomeChoiceOnDisplayTest`:
  - the activity is created and resumed before the core is ready; the core turns ready with the display on and no
    later change of `DisplayState`: the choice opens at that moment, not at a later resume (› Choice after the update);
  - the write of `done` fails: nothing is started and the health has no system-screen record;
  - a dark wake: nothing; display off: nothing (› Dark wake after the update);
  - `ASKS` with the display on: the HOME intent is started, the record is `done`, the health shows an open
    system-screen record and the display is held (› Choice after the update; `kiosk-host` › HOME choice as a system
    screen › Short screen timeout under the choice);
  - resumed again, and a new activity of the same installation: nothing more starts (› Chosen just once or dismissed);
  - display off, then on: nothing while off, the choice when on (› Display off after the update);
  - safe mode: nothing, the record stays `pending` (› Safe mode after the update);
  - `OWN` and `OTHER`: nothing, the record is `done`; `OTHER` keeps the start's warning (› Preference kept, › Another
    app chosen);
  - back in front after the choice with `ASKS` still: one warning `openframe is not the HOME app (android)`, record and
    hold withdrawn; with `OWN`: no warning (› openframe chosen for always; `kiosk-host` › Restart after an update ›
    HOME preference lost).

  `UpdateRestartTest` and `AndroidSettingsMenuTest` pass unchanged. The choice uses the device menu's record, so the
  existing `RunJournalTest` cases ("Ends under a system screen count as planned however many there are", and the
  learning within the minute) cover › Firmware ends the app under the choice and › Firmware ends the app right after
  the choice opens. A `HomeChoiceOnDisplayTest` case checks that the next start with that record finds the record
  `done` and opens nothing.
- [x] 5.4 README, section on the home screen (around line 68): after an update that lost the HOME preference, the
  frame opens Android's choice once; choose openframe with "Always". Verify: the text is there and names no device or
  household detail.

## 6. Install e2e and update polling

- [x] 6.1 `FakeAppInstaller.awaitCommitted(timeoutMs)` with `committed` as a `ConcurrentHashMap` and a monitor
  signalled by `commit`, so the control thread sees a commit made on the core's IO thread. `/update/answer` in
  `TestServerMain` waits up to 5 s and answers the open session committed last, or 409 without one. `TestServerMain`
  gets an internal `installerFactory` used at start (:67) and on reset (:223) (design decision 9). Verify:
  - a new `FakeAppInstallerTest`: `awaitCommitted` returns the newest of two committed open sessions, sees a commit
    made on another thread, and returns `null` after the timeout when none is committed;
  - a new `TestServerMainTest` case with a slow fake put in through `installerFactory` (its `createSession` pauses
    300 ms after opening the session): `/update/answer?result=cancelled` right after `POST /update/install`, and
    `GET /update` reports `ready` with `install_cancelled` within 5 s; it fails before the change;
  - the existing `TestServerMainTest` cases pass.
- [x] 6.2 The update poll in `SettingsView.svelte` through a helper in `src/lib/update.ts` that schedules the next poll
  after every attempt with `updatePollMs(phase)` and stops when the view goes. Verify: a Vitest with fake timers in
  `update.test.ts` (a rejected load, then a successful one, then the next poll at the interval of the new phase);
  `npm run e2e` with an own `OPENFRAME_E2E_PORT` passes `update.spec.ts` in all configured browsers, and the install
  test passes ten runs in a row in Firefox (`--project=firefox --repeat-each=10`).
- [x] 6.3 In `update.spec.ts` › "A version change reloads on the photo page as well", the marker poll catches an
  evaluate that fails because the page is navigating ("Execution context was destroyed") and returns a value that
  keeps polling; any other error still fails the test. Verify: the test passes ten runs in a row in WebKit
  (`--project=webkit --repeat-each=10`).

## 7. Checks

- [x] 7.1 `./gradlew check` with the Android SDK, and in `webadmin/` `npm run lint`, `npm run typecheck`, `npm test`
  and `npm run e2e` with an own `OPENFRAME_E2E_PORT` (for example 8190) are green. Verify: the command outputs.
- [x] 7.2 `openspec validate core-cleanup --strict` exits 0 without warnings. Verify: the output.
- [x] 7.3 The pull request body has the release-note line on `PATCH /sources` (design decision 2) and names the HOME
  choice as a visible change. Verify: the body.
  *(Drafted outside the repository for the main session, which opens the pull request.)*

## 8. Device rows after the release (main session, privately)

Each persistent device change only with the owner's consent at that moment.

- [ ] 8.1 The main session adds rows to the private device checklist, each with its spec reference: the update to this
  release through the updater on a frame with a second HOME app, and the later updates until Android has dropped the
  preference once, with
  the choice appearing over the slideshow and "Always" restoring the Home key (`kiosk-host` › HOME choice after an
  update › Choice after the update, › openframe chosen for always); "just once" and a dismissal with no second opening,
  also after a restart (› Chosen just once or dismissed); a frame that kept its preference with no choice (› Preference
  kept); the log lines in both cases. Verify: the rows exist with their references.
- [ ] 8.2 Run the rows of 8.1 after the release, with the owner's consent for each update and each change of the HOME
  app. Verify: results entered; anything that differs gets a finding in the private findings list.

## Workflow follow-up

- Archive the change after the device rows of 8.2 are done.
