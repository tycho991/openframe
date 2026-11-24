# Design

## Context

See proposal.md for the motivation. Line references are to `main` at 0.14.0 (commit ea357e6), where
`library-performance` and `paired-device-guards` are implemented (both still active as changes) and
`android-platform-fixes` is implemented as well. This change touches none of their requirements: it modifies
`remote-sources` › Configuration and `kiosk-host` › Restart after an update, which no active change touches
(`android-platform-fixes` modifies `kiosk-host` › Keep the display awake, `library-performance` modifies
`remote-sources` › Storage of the originals and Remove a source, `paired-device-guards` adds to `remote-sources`).

**JSON fields on `main`:**

- `SourceConfigStore.bool` (`sources/StoredSource.kt:242-250`) uses `(value as? JsonPrimitive)?.booleanOrNull` without
  an `isString` check, so `"true"`, `"TRUE"` and `"false"` pass. Its messages are "true or false" and "5 to 1440"
  (:248, :262). Its `int` (:252-264) is already strict.
- `SettingsPatch.strictBoolean` (`settings/SettingsPatch.kt:245`), with private `bool` (:247-255) and `int`
  (:257-269), the latter also for `brightness.level`.
- `PUT /display` (`api/DisplayRoutes.kt:54-62`): `on` through `SettingsPatch.strictBoolean`, `brightness` inline.
  Field errors there go into `HttpException.badRequest("Invalid display switch", fields)` (code `bad_request`), not
  into a `ValidationException`.
- `PATCH /photos/{photoId}` (`api/LibraryRoutes.kt:420-470`): `hidden` (:431) and `quarantined` (:461) inline. The body
  comes through `bodyAs(JsonObject.serializer())` (:325), which answers "Invalid body" for a non-object.
- JSON-object casts with "Expected a JSON object": `SettingsRoutes.kt:16`, `SourcesRoutes.kt:186`,
  `DisplayRoutes.kt:51` and `SlideshowRoutes.kt:47` (new since the audit).
- DTO bodies through `bodyAs` (albums, pairing, batch) are decoded by `ApiJson`, which is not lenient and refuses a
  quoted boolean for a `Boolean` field already.
- Names: frame name, device names and comments go through `singleLine` (`SingleLine.kt`); source names go through
  `SourceConfigStore.normalizeName` (:300-304), which refuses control characters. `NextcloudConnector.kt:246` and :294
  use it for a share's name.

**Visibility on `main`:** `:android` uses from `FrameCore`: `platform`, `clock` (only `UpdateRestartTest:139`),
`health`, `log`, `settings`, `display` (`dark`, `userActivity`, `recheck`; `hold` in `AndroidSettingsMenuTest:226`),
`transitions`, `clients.clients`, `pairing.display` and `setShownOnFrame`, `update` (`state`, `install`,
`onInstallResult`; `current()` in `UpdateOnDisplayTest:154`), `identity.id`, `ready`, `slideshow` (`state`, `paused`,
`nextUp`, `next`, `previous`, `togglePause`, `reportUndecodable`), `caption`, `start`, `stop`, `adminUrl`,
`setLocalOnly`, `resetAllPairings`, `onNetworkChanged`, `androidSettingsOffered`, `systemScreenOpened`,
`systemScreenClosed`, `leaveSafeMode`, and two internals: `core.server.state` (`DisplayActivity.kt:354`, :531, :546)
and `c.localSource.root` (:553). `HttpRequest.clientId` (`http/Http.kt:53`) has a public setter; only
`HttpPipeline.kt:86` and :91 write it.

**`requestScan`** (`FrameCore.kt:631`) now has callers: the display turning on (:438) and `scanForClient` (:656), and
`ScanCadenceTest` in `:core`.

**HOME on `main`:** `BootReceiver` starts the service on `BOOT_COMPLETED`, `QUICKBOOT_POWERON` and
`MY_PACKAGE_REPLACED`, and the display below API 29. `BootReceiver.homePackage` resolves `MAIN`/`HOME` with
`MATCH_DEFAULT_ONLY`: it returns the preferred HOME app, the only one, or the system's resolver (`android` on AOSP)
when Android would ask. `logHome` warns in the core's log; `FrameApp.startCore` calls it after every core start, and
`DisplayActivity.finishSystemScreen` after a system screen opened in this run. The run journal tells a new
installation by `installId` (version code and `lastUpdateTime`, `FrameApp.installId`): the first start of a new
installation has `HealthState.previousEnd == RunEnd.REPLACED`. A first installation has `RunEnd.FIRST`. The system-screen
machinery (`FrameCore.systemScreenOpened/Closed`, `Health.systemScreenOpenedInThisRun`,
`DisplayActivity.withdrawSystemScreen`) records a planned end that is renewed for 10 minutes at each start, holds the
display for 10 minutes, and withdraws both in `onResume`.

**Tests in `:android`:** most use Robolectric (`UpdateRestartTest`, `AndroidSettingsMenuTest`, with
`ShadowPackageManager` and `shadowOf(app).nextStartedActivity`). Pure logic is tested without it
(`PairingShownTest`, `CornerOffsetsTest`). Robolectric resolves an intent to its first match, so it cannot show the
system's resolver.

**Install e2e on `main`:** `UpdateManager.install` publishes the dialog (phase `installing`) and returns; the route
answers at once, and the web UI shows "the dialog is open". `openDialog` runs on the core's scope:
`installer.createSession()` (the fake adds the session to its open set), then `store.saveNow` (an `fsync`), then
`d.sessionId = id`, then `commit`. The test server's `/update/answer` takes `installer.openSession()` (`open.single()`)
and answers at once. An answer that arrives after `createSession` but before `d.sessionId = id` reaches `applyResult`
with `d.sessionId == null` and is dropped as "not the open dialog" (`UpdateManager.kt:671-673`). The phase stays
`installing` until the 10-minute deadline, so the cancelled text never shows. On Android this cannot happen: the dialog
appears only after `commit`. `SettingsView.svelte:148-152` schedules the next poll from an `$effect` that runs again
only when `update` is assigned, so a failed poll (`loadUpdate`'s `catch`) ends the polling.

## Goals / Non-Goals

**Goals:**

- One place decides what a JSON switch and a JSON integer are, for every hand-validated body.
- `:android` reaches only what it uses, and nothing that blocks on `fsync` or bypasses an API rule except the
  device-menu actions that are meant for it.
- Unused texts are found by a test, not by an audit.
- A person at the frame gets the chance to restore the HOME preference after an update lost it, once, without the
  frame nagging or leaving the slideshow on its own.
- The install e2e fails only for real faults.

**Non-Goals:**

- No change of error codes or of which fields are named; messages change wording only.
- No change of the DTO bodies decoded through `bodyAs`, of the `limit` parsing or of the crop's numbers.
- No way to set the HOME preference without the person at the frame; no HOME choice at a first installation or at
  every boot; no opening of Android's settings for the HOME app.
- No visibility change in `DisplayController` and the other controllers' public API beyond the members listed below.

## Decisions

### 0. Owner's defaults confirmed by the coordinator

- When another app is the chosen HOME app after an update, the frame only logs (decision 6).
- The rare extra opening on Android 10 and later (someone who just chose openframe "just once" through the Home key) is
  accepted (Risks).
- Source names are normalised with `singleLine` (decision 3).

### 1. `JsonFields`, taking the error map

- **What:** `internal object JsonFields` in `core/http/JsonFields.kt`:
  - `bool(value: JsonElement, key: String, errors: MutableMap<String, String>): Boolean?` accepts only a JSON literal,
    `(value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull`; otherwise it puts "Expected true or false"
    under `key` and returns `null`.
  - `int(value, key, range, errors): Int?` accepts only an unquoted integer in `range`; otherwise "Expected an integer
    from a to b".
- **And:** `HttpRequest.bodyObject(): JsonObject` = `bodyJson() as? JsonObject ?: throw HttpException.badRequest(
  "Expected a JSON object")`.
- **Callers:** `SourceConfigStore.patch` (its private `bool` and `int` go), `SettingsPatch` (its `strictBoolean`,
  `bool` and `int` go; `autoUpdateCheck` and `brightness.level` included), `PUT /display` (`on` and `brightness`, still
  into its `fields` with `bad_request`), `photoPatch` (`hidden`; `quarantined` through `bool` and then "Only false, to
  try the photo again" when it is not `false`, so the message for `quarantined` stays), and the five casts, including
  `PATCH /photos` instead of `bodyAs(JsonObject.serializer())`.
- **Why the map:** every caller collects all field errors before it changes anything; a helper that returned only a
  value would leave the message in each caller and the copies would come back.
- **Not here:** `SettingsPatch`'s `string`, `enum` and `time`, the crop's numbers and `SourceConfigStore.folder` and
  `name`: each has one caller and its own message. `SettingsRepository.load` still applies stored settings through
  `SettingsPatch.apply`, with the same rules.
- **Alternative:** loosen the contract to accept strings. Rejected by the owner: it would contradict the other
  endpoints, and the web UI never sends strings.

### 2. Strict switches on `PATCH /sources`, with a release note

- The behaviour change is the `isString` check of decision 1. Stored data needs no migration: `sources.json` is read
  through its serializer, not through `patch`, and holds real booleans.
- The contract keeps `type: boolean`; the description of `patchSource` gets one sentence. The contract harness already
  treats a 2xx answer to `{"enabled":"true"}` as a violation of the request body, so the new `SourcesApiTest` cases
  fail on `main` as contract violations and pass after the fix.
- `{"removeVanished":1}` is no proof: `booleanOrNull` refuses `1` already. The tests use `"true"`, `"FALSE"` and
  `"false"`.
- The implementation pull request carries a release-note line: "PATCH /sources/{id} now refuses switches sent as text
  (`"true"`) with 400, like the other endpoints."

### 3. Source names through `singleLine`

- **Decision:** `normalizeName(raw)` becomes `singleLine(raw)` followed by the length check (1 to 64), so a control
  character becomes a space instead of refusing the name. This covers `PATCH /sources`, the name given when adding a
  share, and a share's own name from the server (`NextcloudConnector.kt:246`, :294), which today falls back to the host
  when the share's name has a control character.
- **Why:** it is the last place with its own rule; frame name, device names and comments already use `singleLine`
  (spec `frame-settings`, `admin-access` › Device names without control characters, `photo-library` comments). The web
  UI's single-line input cannot produce control characters, so nobody sees a difference there.
- **Alternative:** leave sources as they are and record the difference. Rejected: the unification costs one line and two
  tests, and the new requirement documents the rule.

### 4. Visibility: what becomes `internal`, and what stays

- **`FrameCore` becomes `internal`:** `signer`, `localSource`, `trash`, `sources`, `nextcloud`, `cache`, `library`,
  `places`, `visiblePhotos`, `router`, `server`, `reserveBytes` and `requestScan`. Login grants (`AccountGrants`) are
  reached only through `sources` and `nextcloud`, so they need no change of their own.
- **New for `:android`:** `val serverState: StateFlow<ServerState> get() = server.state` and `val photoFolder: File get()
  = localSource.root`. Both are read-only; `DisplayActivity` switches to them.
- **Stays public:** every member in the list under Context. `clock` stays public: it only reads time, and
  `UpdateRestartTest` checks that the app's core gets the uptime clock, which `:android`'s unit tests cannot do
  through an internal member. `stop()` stays public: it is part of the life cycle and used by `:android` tests.
- **`HttpRequest.clientId`:** `internal set`, KDoc "Set only by `HttpPipeline`", like `pathParams`. Inside `:core` a
  route could still write it; that stays a convention.
- **`ClientRegistry`:** `clients` stays public (the display reads it); `load`, `create`, `authenticate`, `remove`,
  `rename`, `removeAll` and `flush` become `internal`. `create`, `remove`, `rename` and `removeAll` get the KDoc
  "Blocks on fsync; never call on the main thread".
- **`PairingManager`:** `display` and `setShownOnFrame` stay public; `setScreenUsable`, `requestDisplay`, `tick`,
  `pair`, `pairWithSecret`, `createInvite`, `dropInvite` and `reset` become `internal`. `pair` and `pairWithSecret` get
  the fsync KDoc, as do `ImageSigner.rotate` and `load` and `PairingAdmin.resetAll`.
- **`UpdateManager`:** `state`, `current()`, `install()` (never blocks) and `onInstallResult()` stay public; `check()`,
  `start()`, `networkChanged()` and `flush()` become `internal`.
- **`SlideshowController`:** `start`, `setPaused`, `setResting` and `control` become `internal` (only `FrameCore`, the
  routes and `:core` tests call them); the display's members stay public.
- **`requestScan`:** stays, as `internal`, with the KDoc "Scans the photo folder soon: the display turned on, or a
  client fetched photos (design decision 7 of `library-performance`). Uploads add their files themselves."
- **Why `internal` and not new interfaces:** the module boundary is the only boundary that matters here; `:core`'s own
  tests and the dev server live in `:core`'s test source set and keep their access.
- **Check:** without an Android SDK, `./gradlew check` skips `:android`. The implementation runs it with the SDK
  (`local.properties`), and CI builds `:android` (`check :android:assembleDebug`).

### 5. Unused i18n keys

- **Test:** `webadmin/src/lib/i18n/unusedKeys.test.ts`, with `node:fs` like `errorCodes.test.ts`. It reads every
  `*.ts` and `*.svelte` under `src/` except `*.test.ts`. A key counts as used if it appears as a quoted literal
  (`"key"`, `'key'` or `` `key` ``), or starts with a dynamic prefix. Prefixes come from the source itself, and only
  from places that build a translation key:
  - template literals inside a `t(` or `tOr(` call, `` t(`prefix.${ `` and `` tOr(`prefix.${ `` (today
    `update.error.`, `update.phase.` and `update.result.` in `SettingsView.svelte`, `settings.fillMode.` in
    `PhotoDetail.svelte:167`);
  - the first argument of `option("prefix", …)` in `SettingsView.svelte` (today `settings.order`, `.transition`,
    `.fillMode`, `.orientation`, `.corner`, `.brightnessMode`, `.clockFormat`, `.display` and `.language`), whose
    helper builds `` tOr(`${prefix}.${value}`) `` (:267).

  Other template literals do not count: `` `displaySchedule.${day}.${edge}` `` (:470, :487) is a field path of the API,
  not a key. A fully dynamic key without a literal prefix, such as the helper's own, adds nothing. No list by hand.
- **The test fails with the unused keys named**, so a new dynamic key that the patterns miss is fixed in the pattern,
  not by deleting the key.
- **Probe on `main`:** 420 keys, two unused: `common.retry` and `photos.source.nextcloud`. Both are deleted from
  `en.json` and `de.json`; `detect.test.ts` keeps checking that both catalogs have the same keys.

### 6. HOME choice: when it opens

- **Signal "after an update":** the run journal's `RunEnd.REPLACED` for this start, together with the current
  `installId`. The journal decides this at every process start, whichever component Android starts first, and the
  display already reads the app's health; `MY_PACKAGE_REPLACED` is only one of the ways the first start comes about.
- **Record per installation:** a small `SharedPreferences` file of `:android` (`home_choice`, framework API, no
  AndroidX) with one value, `"<installId>|pending"` or `"<installId>|done"`. A first installation (`RunEnd.FIRST`)
  writes nothing.
- **Arming, before anything else runs:** `FrameApp.start(processName)` writes `pending` for the `installId` it has just
  handed to the journal, right after `journal.onProcessStart(…)` (`FrameApp.kt:154`) returns `REPLACED` and when no
  record for this `installId` exists. Android creates the `Application` before any service, receiver or activity of
  the process, so the record exists before the display can evaluate it. This matters because `FrameCore.start()` sets
  `ready` (`FrameCore.kt:469`) before it awaits the first server bind (:499-502), and `DisplayActivity.attach` waits
  only for `ready` (`DisplayActivity.kt:338`). Arming after `core.start()` returns would let the display evaluate
  first and find no record. A `core.start()` that throws would also lose the arming, and the next start would be
  `CRASHED`, not `REPLACED`. The write is synchronous (`commit()`) on the main thread, next to the journal's own
  synchronous write of `runs.json` at the same place. If `commit()` returns false, `start` logs to Logcat and leaves
  no record, and the choice does not open for this installation (fail closed).
- **Rejected alternative:** a record the display observes as a flow. With the arming in `start`, the value is final
  before the display exists, so there is nothing to observe.
- **HOME state:** `HomeApps.read(pm, ownPackage)` resolves `MAIN`/`HOME` (as `homePackage` does) and lists the
  candidates through `queryIntentActivities(…, MATCH_DEFAULT_ONLY)`.
- **Package visibility:** openframe targets SDK 36. From Android 11 on, `queryIntentActivities` and `resolveActivity`
  see only the apps the manifest declares in `<queries>`, and today's `<queries>` (`AndroidManifest.xml:24-29`) has
  no HOME intent. Other launchers would then be missing from the candidates, so `ASKS` could never come about. A
  hidden preferred launcher might also resolve as openframe (`OWN`), which already breaks today's `logHome` warning.
  The manifest gets `<intent>` with action `MAIN` and category `HOME` in `<queries>`, and `ManifestTest` checks it.
  Robolectric does not enforce package visibility, so the main session checks it on an Android 17 emulator with a
  second launcher (task 5.1). The pure function
  `HomeChoice.status(resolved, candidates, own)` gives:
  - `OWN`: openframe is the preferred or the only HOME app;
  - `OTHER`: another candidate resolves, so another app is preferred (or is the only one, if openframe is disabled as a
    candidate);
  - `ASKS`: the resolved activity is not one of the candidates (the system's resolver, on any vendor's package), and
    openframe and at least one other app are candidates;
  - `NONE`: openframe is not a candidate.
- **Decision:** the pure `HomeChoice.decide(record, installId, status, safeMode, displayShowing)` gives `OPEN`, `WAIT`
  or `DROP`:
  - no `pending` record for this `installId`: nothing to do;
  - `OWN`, `OTHER` or `NONE`: `DROP`, the record becomes `done` without opening (`OTHER` and `NONE` are logged by
    `logHome` already);
  - `ASKS`, but in safe mode, or the display off or in a dark wake, or the slideshow not in front: `WAIT`;
  - `ASKS` otherwise: `OPEN`.
- **Where it is evaluated:** in `DisplayActivity`, only while it is resumed and its core is ready: once when
  `core.ready` turns true (the collectors of `core.display.state` and `core.display.dark`, started after `ready` in
  `attach`, emit their current value at once), in `onResume` when the core is ready, and on every later change of
  those two flows. The record is already there at all of these moments (see "Arming"). Starting an activity is
  allowed there on every Android version, also from Android 10 on, where a background start is not.
- **At most once:** on `OPEN`, the record becomes `done` with `commit()`, and only if `commit()` returns true does the
  activity record the system screen and start the choice. If it returns false, the activity logs `HOME choice not
  opened: its record could not be written`, opens nothing and leaves the record as it was. A later evaluation may try
  again, but it opens only after a successful write, so storage that cannot be written never shows the choice. A
  dismissal, "just once", another launcher, a crash or a firmware end under the choice therefore cannot lead to a
  second opening for this installation. Only the next installation arms it again.
- **The write on the main thread:** one write of a few bytes, once per installation, directly before `startActivity`,
  which must come after it. The device menu already does the same: `FrameCore.systemScreenOpened` writes the journal
  synchronously (`AtomicFile` with `fsync`) on the main thread just before it starts a system screen. Moving the write
  to a background thread would need the activity to check again, after the write, that it is still resumed. If it is
  not, the record would already be `done` with nothing shown, or it would have to be written back. That costs more
  than it saves.
- **Why the display condition:** the system-screen hold (decision 7) would otherwise switch the display on in an off
  period, and a choice nobody can see helps nobody. With `pending` kept across restarts, the choice still comes the
  next time the display is on.
- **Why not in safe mode:** safe mode is the frame's quiet state after repeated crashes; a dialog over the safe-mode
  notice would confuse more than help. The record stays `pending` until a normal run of the same installation.
- **Why not when another app is preferred:** starting the HOME intent would then open that app, and the slideshow would
  leave the screen. Android's settings for the HOME app are a system screen that leaves the kiosk, and the Frameo's
  firmware ends openframe when they open. Someone chose that app on purpose; the warning in the log stays.
- **Alternatives:** opening the choice from `BootReceiver` (forbidden in the background from Android 10, and before
  the display exists); `Settings.ACTION_HOME_SETTINGS` (a settings screen, not a dialog over the slideshow); a notice on
  the frame asking the person to press Home (one step more; Android's own choice is clearer and offers "Always").

### 7. HOME choice: how it opens, and what happens afterwards

- **Opening:** `core.systemScreenOpened("home choice")`, then `startActivity(Intent(ACTION_MAIN).addCategory(
  CATEGORY_HOME).addFlags(FLAG_ACTIVITY_NEW_TASK))`, which Android answers with its resolver because no app is
  preferred. Picking "Always" there sets the preferred activity, as after a Home key press. An
  `ActivityNotFoundException` closes the system-screen record again (`systemScreenClosed`) and is logged, like
  `openAndroidSettings`; the `home_choice` record stays `done`. Log line: `Opening Android's HOME choice after the update: no HOME app is chosen`.
- **System screen:** the existing record gives a planned end if the firmware ends the app under the choice, renewed
  for 10 minutes at each start; it keeps the self-healing restart from bringing the display back over the choice; and
  the display hold keeps the display on for 10 minutes with a short system timeout. When the time is up, the display
  follows the schedule again; the choice stays where it is until someone answers it.
- **Coming back:** the slideshow returns after an answer (dismissed, "just once" or "always" for openframe; Android
  starts the `singleTask` display again). `withdrawSystemScreen` in `onResume` withdraws the record, ends the hold, and,
  because the screen was opened in this run, `finishSystemScreen` calls `logHome`. So "just once" and a dismissal log
  the warning again, and "always" logs nothing. If the person picks the other launcher, the slideshow stays in the
  background until someone brings it back; the record expires after 10 minutes as for the device menu.
- **Learning about the firmware (decided: the choice teaches it):** a start within a minute after the record, without
  a Java crash, teaches the journal that the firmware ends the app when another activity starts
  (`systemScreenEndsApp`, `RunJournal.kt:127-135`). The device menu then hides Android's settings until the next
  installation. That is right: such a device ends the app for any activity, and the settings would end it as well.
  Keeping the choice from teaching would need a second kind of record for no gain. The spec says so in "HOME choice as
  a system screen": the choice counts as a system screen from the device menu wherever `kiosk-host` names one,
  including "Self-healing" and "Android settings hidden where the firmware ends the app". So those two requirements
  keep their text; both are close to 500 characters, and a MODIFIED copy with one more clause would be over.
- **No new texts:** the dialog is Android's; the frame shows nothing of its own.

### 8. HOME choice: tests on the JVM

- **`HomeChoiceTest`** (plain JUnit, no Robolectric, like `PairingShownTest`): a table over `status` (resolver
  package, vendor resolver package, openframe preferred, another app preferred, only openframe, openframe not a
  candidate) and over `decide` (no record, `pending` of another installation, `done`, safe mode, display off, dark
  wake, not resumed, each status).
- **Arming** (Robolectric): `FrameApp.start("…")` with a journal whose previous run has another `installId` writes
  `pending` before any core exists; with `FIRST` or the same installation it writes nothing; with a `coreFactory` whose
  core throws in `start()`, the record is still `pending` after `start`.
- **`HomeChoiceOnDisplayTest`** (Robolectric, like `AndroidSettingsMenuTest`): `FrameApp` gets a seam `homeApps: () ->
  HomeApps` (as `coreFactory`), because Robolectric cannot produce Android's resolver, and a seam for the record's
  write result. With a `Health` whose state is `REPLACED` and a light `TestCore`:
  - the display is created and resumed before the core is ready; the core turns ready with the display on and no
    later change of `DisplayState`: the choice opens at that moment, not at a later resume;
  - the write of `done` fails: nothing is started and no system-screen record is made;
  - `ASKS` and the display on: the next started activity is the HOME intent, the record is `done`, the health has an
    open system-screen record and the display hold is set;
  - resuming again, and a new `DisplayActivity` with the same `installId`: nothing more is started;
  - the activity resumed, then the core reports the display off: nothing; on again: the choice opens;
  - safe mode: nothing, the record stays `pending`;
  - `OWN` and `OTHER`: nothing, the record is `done`;
  - `FIRST`: no record is written;
  - back in front after the choice with `ASKS` still: one warning `openframe is not the HOME app (android)`, and the
    record and hold are withdrawn.
- **`UpdateRestartTest`** keeps its two log tests unchanged.
- **`ManifestTest`**: `<queries>` contains an `<intent>` with action `MAIN` and category `HOME`.

### 9. Install e2e: answer only an open dialog

- **Fix at the cause:** `FakeAppInstaller.awaitCommitted(timeoutMs): Int?` waits until an open session has been
  committed (its `commit` has run), as Android shows the dialog only then. `/update/answer` in `TestServerMain` waits
  for it up to 5 seconds and answers that session; without one it answers 409 "No installation dialog is open", so the
  e2e fails at the step that is wrong instead of 10 seconds later. `confirmed` waits the same way.
- **Thread safety:** `commit` runs on the core's IO thread, `/update/answer` on the control server's thread. Today
  `committed` is a plain `mutableMapOf` (`FakeAppInstaller.kt:37`). It becomes a `ConcurrentHashMap`, and committing
  signals a monitor (`synchronized` with `wait`/`notifyAll`) that `awaitCommitted` waits on with its deadline.
- **Which session:** the open session committed last. `UpdateManager` keeps one dialog at a time, so normally there is
  exactly one. An older session that is still open because nobody answered it is skipped, as Android would show only
  the newest dialog. `openSession()` (`open.single()`) is no longer used by the route.
- **Seam:** `TestServerMain` creates its installer privately, at start (`TestServerMain.kt:67`) and on reset (:223). It
  gets an internal `installerFactory: () -> FakeAppInstaller`, used at both places, so the test can put in a slow
  fake.
- **Proof:** a `TestServerMainTest` case with a fake whose `createSession` pauses 300 ms after opening the session,
  which widens the window: `POST /update/install`, then at once `/update/answer?result=cancelled`, then `GET /update`
  reports `ready` with the error `install_cancelled` within 5 seconds. Before the fix it fails, with a 500 from
  `open.single()` or with the answer dropped.
- **Polling after a failed request:** `SettingsView.svelte` schedules the next update poll after every attempt, failed
  or not, through a small helper in `src/lib/update.ts` (a loop of `setTimeout` with `updatePollMs(phase)`), so the
  section keeps following the state as `web-admin` › Software update in the interface requires. A Vitest with fake
  timers covers a rejected load followed by a successful one.
- **Not done:** a longer timeout in the e2e. It would hide the dropped answer.

## Risks / Trade-offs

- [Strict switches break a hand-made client that sends `"true"`] → The contract has always said boolean; release note
  in the pull request; the web UI is unaffected.
- [`internal` breaks an `:android` access that grep missed, or an `:android` unit test] → `./gradlew check` with the
  SDK locally and CI's `:android:assembleDebug`; the accessors used by `:android` tests (`clock`, `display.hold`,
  `update.current()`, `log.recent`) stay public.
- [`internal set` on `clientId` protects only against `:android`] → Accepted; a per-request immutable identity belongs
  to the roles change.
- [Android drops the HOME preference at a later boot, not at the replace] → The choice is evaluated once per
  installation, at the first time the slideshow shows; a later loss is only logged. The device rows after the release
  check when it happens.
- [The resolver's "Always" does not set the preference when an app starts the HOME intent on some firmware] → The
  device rows check it on the reference frames; the log shows the state when the slideshow is back.
- [The person picks the other launcher in the choice] → Their choice; the slideshow comes back through the launcher
  icon, and the log warns.
- [From Android 10 on, someone who just pressed Home and chose openframe "just once" gets the choice once more] →
  Accepted: once per installation. With the HOME role, Android 10 and later keep a chosen HOME app across updates, so
  `ASKS` is rare there.
- [The Frameo's firmware ends openframe when the choice opens] → A planned end through the record; the choice stays,
  and choosing openframe starts it again. It opens only if the Frameo has a second HOME app and lost the preference.
- [The unused-key test misses a new dynamic pattern] → It fails with the keys named; the pattern is extended then.
- [The poll helper changes timing in the other update e2e tests] → Same intervals as today; only failed polls behave
  differently.

## Migration Plan

- One implementation pull request, title in Conventional Commits (for example `feat: HOME choice after an update,
  strict source patches and core cleanup`), shipped through the updater in one release. Its body has the release-note
  line of decision 2.
- No data migration. The `home_choice` preference file is new; a version without this change ignores it. A downgrade
  to an older version that has this change is a new installation too (`REPLACED`, another `installId`). It arms and
  behaves like an update, which is harmless.
- Rollback: revert the pull request. Nothing stored needs to change back.
- After the release, the main session runs the device rows privately (tasks group 8).
