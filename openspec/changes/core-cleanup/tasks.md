# Tasks

One implementation pull request for the whole change. Line references are to `main` at 0.14.0 (ea357e6); check them
again before editing.

## 1. Contract first

- [ ] 1.1 `api/openapi.yaml`, before any Kotlin: the description of `patchSource` says that every field is checked by
  its JSON type and that a switch sent as a string (`"true"`) is invalid and refused with 400 `validation_failed`
  naming the field, with nothing changed (design decision 2). `SourceSettingsPatch` keeps `type: boolean`; no schema
  changes, so no DTO changes. Then `npm run gen:api` in `webadmin/`. Verify: `npm run lint` (Redocly included) and
  `npm run typecheck` are green, the generated types differ only in descriptions, and `RouteAccessTest` stays green.

## 2. Strict JSON fields in one helper

- [ ] 2.1 Failing tests first, run against `main` and noted in the pull request:
  - `SourcesApiTest` (`TestFrame`, contract check on): `PATCH /api/v1/sources/{id}` with `{"enabled":"true"}`,
    `{"includeSubfolders":"FALSE"}` and `{"removeVanished":"false"}` each gives 400 `validation_failed` naming exactly
    that field (`remote-sources` › Configuration › Switch sent as text); `{"name":"New","includeSubfolders":"false"}`
    is refused as a whole and `GET` shows name and switch unchanged (› Valid and invalid field together);
  - `LibraryApiTest`: `{"hidden":"true"}` added to the table of invalid photo patches; `{"quarantined":"false"}` gives
    400 naming `quarantined`; `PATCH /albums` with `"hidden":"true"` gives 400 (already refused by `ApiJson`, kept as
    a guard);
  - `PATCH /photos/{id}` with the body `[true]` gives 400 `bad_request` "Expected a JSON object".

  Verify: the sources cases fail on `main` (as contract violations or 200), the rest pass already.
- [ ] 2.2 `core/.../http/JsonFields.kt` with `internal object JsonFields` (`bool`, `int`, both taking the error map) and
  `HttpRequest.bodyObject()` in `http/Http.kt` (design decision 1). New `JsonFieldsTest`: `bool` for `true`, `false`,
  `"true"`, `"TRUE"`, `1`, `JsonNull`, `[]` and an object; `int` for `20`, `"20"`, `20.5`, `1e1`, both range limits and
  one past each; the messages "Expected true or false" and "Expected an integer from 5 to 1440". Verify: the test
  passes and `./gradlew :core:check` is green.
- [ ] 2.3 Switch the callers (design decision 1): `SourceConfigStore.patch` (drop its private `bool` and `int`),
  `SettingsPatch` (drop `strictBoolean`, `bool`, `int`; `autoUpdateCheck` and `brightness.level` included),
  `PUT /display` (`on` and `brightness` into its `fields`, still `bad_request`), `photoPatch` (`hidden`, and
  `quarantined` through `bool` with its own message kept), and `bodyObject()` in `SettingsRoutes`, `SourcesRoutes`,
  `DisplayRoutes`, `SlideshowRoutes` and `PATCH /photos`. Verify: the tests of 2.1 pass; `DisplayApiTest` (`{"on":
  "false"}`, `[true]`), `SettingsRoutesTest`, `MalformedJsonTest` and `SlideshowApiTest` pass unchanged;
  `grep -rn "strictBoolean\|booleanOrNull\|as? JsonObject ?: throw" core/src/main` finds only `JsonFields.kt` and
  `bodyObject()`.
- [ ] 2.4 Source names through `singleLine` (design decision 3): `SourceConfigStore.normalizeName` trims with
  `singleLine` and then checks 1 to 64 characters. Verify, `SourcesApiTest` with the contract check: a rename to
  "Summer\n2026" stores "Summer 2026" (`remote-sources` › Source names on one line › Name with a line break); a name of
  only control characters gives 400 naming `name` and the old name stays (› Only control characters); a
  `NextcloudConnectorTest` case: a share whose name has a tab is added with that name on one line, not with the host.

## 3. Visibility in `:core`

- [ ] 3.1 `FrameCore` (design decision 4): `signer`, `localSource`, `trash`, `sources`, `nextcloud`, `cache`, `library`,
  `places`, `visiblePhotos`, `router`, `server`, `reserveBytes` and `requestScan` become `internal`; new public
  `serverState` and `photoFolder`; `DisplayActivity` uses them instead of `server.state` (:354, :531, :546) and
  `localSource.root` (:553). `requestScan` gets the KDoc of decision 4. Verify: `./gradlew check` with the Android SDK
  from `local.properties` (so `:android` compiles) is green; `ScanCadenceTest` and `FrameCoreTest` pass.
- [ ] 3.2 `HttpRequest.clientId` with `internal set` and the KDoc "Set only by `HttpPipeline`"; `ClientRegistry`'s
  `load`, `create`, `authenticate`, `remove`, `rename`, `removeAll`, `flush` and `PairingManager`'s `setScreenUsable`,
  `requestDisplay`, `tick`, `pair`, `pairWithSecret`, `createInvite`, `dropInvite`, `reset` become `internal`; the
  fsync KDoc on `ClientRegistry.create`, `remove`, `rename`, `removeAll`, `PairingManager.pair`, `pairWithSecret`,
  `ImageSigner.rotate`, `load` and `PairingAdmin.resetAll`. Verify: `./gradlew check` with the SDK is green; locally,
  without committing, a call of `core.signer.rotate()`, `core.clients.removeAll()` or `core.pairing.pair(…)` in
  `DisplayActivity` does not compile.
- [ ] 3.3 `UpdateManager.check`, `start`, `networkChanged` and `flush` and `SlideshowController.start`, `setPaused`,
  `setResting` and `control` become `internal`; `state`, `current()`, `install()` and `onInstallResult()` and the
  display's slideshow members stay public. Verify: `UpdateApiTest`, `SlideshowControllerTest`, `SlideshowApiTest`,
  the `:android` unit tests (`UpdateOnDisplayTest`, `UpdateRestartTest`, `AndroidSettingsMenuTest`) and
  `RouteAccessTest` pass.

## 4. Unused i18n keys

- [ ] 4.1 `webadmin/src/lib/i18n/unusedKeys.test.ts` per design decision 5 (`node:fs`, `*.ts` and `*.svelte` under
  `src/` without `*.test.ts`, quoted literals, prefixes from template literals and from `option("…"`). Verify: it fails
  on `main` naming exactly `common.retry` and `photos.source.nextcloud`, and a key added only to `en.json` and `de.json`
  for a moment is reported as well.
- [ ] 4.2 Delete `common.retry` and `photos.source.nextcloud` from `en.json` and `de.json`. Verify: `npm test`
  (`unusedKeys.test.ts` and `detect.test.ts` included), `npm run lint` and `npm run typecheck` are green.

## 5. HOME choice after an update

- [ ] 5.1 `android/.../HomeChoice.kt`: the pure `HomeChoice.status(resolved, candidates, own)` and
  `HomeChoice.decide(record, installId, status, safeMode, displayShowing)` (design decision 6), `HomeApps.read(pm,
  own)` with `resolveActivity` and `queryIntentActivities` (`MATCH_DEFAULT_ONLY`), and the record in the
  `SharedPreferences` file `home_choice` (`"<installId>|pending"` or `"|done"`, written with `commit()`). New
  `HomeChoiceTest` (plain JUnit, no Robolectric) with the table of design decision 8. Verify: the test passes;
  `ManifestTest` and Lint stay green; no AndroidX import.
- [ ] 5.2 Arming in `FrameApp.startCore`: after the core's start and `logHome`, write `pending` for the current
  `installId` (`installId()` becomes `internal`) when `health.state.value.previousEnd == RunEnd.REPLACED` and no
  record for it exists; a seam `homeApps: () -> HomeApps` like `coreFactory`. `BootReceiver`'s KDoc names the choice.
  Verify, in `HomeChoiceOnDisplayTest` (Robolectric, light `TestCore`, `Health` from a `HealthState`): `REPLACED`
  writes `pending`, `FIRST` and `REBOOTED` write nothing, a second start of the same installation keeps `done`
  (`kiosk-host` › HOME choice after an update › Restart without an update).
- [ ] 5.3 Opening in `DisplayActivity` (design decision 7): evaluated in `onResume` and on every change of
  `core.display.state` and `core.display.dark` while resumed and the core is ready; on `OPEN` the record becomes `done`,
  then `core.systemScreenOpened("home choice")`, the log line, and `startActivity` of the HOME intent;
  `ActivityNotFoundException` closes the record. Verify, `HomeChoiceOnDisplayTest`:
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
  existing `RunJournalTest` cases ("Ends under a system screen count as planned however many there are") cover
  › Firmware ends the app under the choice; a `HomeChoiceOnDisplayTest` case checks that the next start with that
  record finds the record `done` and opens nothing.
- [ ] 5.4 README, section on the home screen (around line 68): after an update that lost the HOME preference, the
  frame opens Android's choice once; choose openframe with "Always". Verify: the text is there and names no device or
  household detail.

## 6. Install e2e and update polling

- [ ] 6.1 `FakeAppInstaller.awaitCommitted(timeoutMs)` and `/update/answer` in `TestServerMain` waiting up to 5 s for a
  committed open session, 409 without one (design decision 9). Verify: a new `TestServerMainTest` case with a fake
  whose `createSession` pauses 300 ms answers `cancelled` right after `POST /update/install` and `GET /update` reports
  `ready` with `install_cancelled` within 5 s; it fails before the change; the existing `TestServerMainTest` cases pass.
- [ ] 6.2 The update poll in `SettingsView.svelte` through a helper in `src/lib/update.ts` that schedules the next poll
  after every attempt with `updatePollMs(phase)` and stops when the view goes. Verify: a Vitest with fake timers in
  `update.test.ts` (a rejected load, then a successful one, then the next poll at the interval of the new phase);
  `npm run e2e` with an own `OPENFRAME_E2E_PORT` passes `update.spec.ts` in all configured browsers, and the install
  test passes ten runs in a row in Firefox (`--project=firefox --repeat-each=10`).

## 7. Checks

- [ ] 7.1 `./gradlew check` with the Android SDK, and in `webadmin/` `npm run lint`, `npm run typecheck`, `npm test`
  and `npm run e2e` with an own `OPENFRAME_E2E_PORT` (for example 8190) are green. Verify: the command outputs.
- [ ] 7.2 `openspec validate core-cleanup --strict` exits 0 without warnings. Verify: the output.
- [ ] 7.3 The pull request body has the release-note line on `PATCH /sources` (design decision 2) and names the HOME
  choice as a visible change. Verify: the body.

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
