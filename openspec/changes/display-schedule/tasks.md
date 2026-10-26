# Tasks

## 1. Contract first

- [x] 1.1 `api/openapi.yaml`: `displaySchedule` in `Settings` (required, `enabled`, `weekdays`/`weekend` with `on`/`off` as `HH:MM` pattern, `wakeMinutes` 1 to 120) and partially in `SettingsPatch`; `GET /display` and `PUT /display` (`{on: boolean}`, answers 200 `DisplayState`, 400, 401, 403, 409) with tag `display`; schema `DisplayState` (`available`, `on`, `cause` enum `schedule|manual|wake|none`, `nextChange` date-time or null) as in design decision 8; verify: `npx @redocly/cli lint api/openapi.yaml` passes
- [x] 1.2 Run `npm run gen:api` in `webadmin/`; verify: `webadmin/src/lib/api/schema.d.ts` has `DisplayState` and `displaySchedule`, and `git diff --stat` shows only generated changes there

## 2. Settings and clock in `:core`

- [x] 2.1 `Clock.zone()` and `Clock.monotonicMillis()` with defaults; `TestClock` with a settable zone and a monotonic time that `advance()` moves; verify: existing tests pass unchanged, and a test shows that a jump of the wall clock leaves the monotonic time as it was
- [x] 2.2 `DisplayScheduleSettings`/`DisplayWindow` in `Settings` with the defaults of spec `display-schedule` › Schedule settings; `SettingsPatch` partial at every level with the time pattern and the range of `wakeMinutes`, error paths such as `displaySchedule.weekend.on`; verify: tests for every scenario of "Schedule settings", for `25:00`, `7:00`, `wakeMinutes` 0 and 121, an unknown field `displaySchedule.foo`, and that a stored `settings.json` without `displaySchedule` loads with the defaults and keeps its other values
- [x] 2.3 `SettingsRepository.patch` with the injected `scheduleAllowed`: only `enabled: true` while it is not allowed is rejected with `displaySchedule.enabled`; verify: tests for "Schedule without device administration", for a patch of only the times while not allowed (accepted), and for a stored `enabled: true` that stays after `scheduleAllowed` turns false

## 3. Schedule and decisions in `:core`

- [x] 3.1 `DisplaySchedule.periodAt`/`nextChange` per design decision 2 in the new package `core/.../display/`; verify: unit tests for each scenario of "Periods of the schedule", for a Sunday window crossing into Monday, equal times on a weekday, and both DST changes in `Europe/Berlin` (on time in the gap at 02:30, off time in the doubled hour)
- [x] 3.2 `DisplayPolicy.decide` as a pure function per design decision 4 (precedence: not available, override bound to its period, waking/grace, schedule, none) with `cause` and `nextChange`; verify: table tests for each precedence step, for an override ending at the next period, for an override without a schedule, and for `nextChange` as the earliest of the period end and `wakeUntil`
- [x] 3.3 `DisplayPower` in `core/.../platform/` with `DisplayPower.None`, and `FakeDisplayPower` in `core/src/test/.../testing/` that records calls and sends screen and availability events; verify: `./gradlew :core:checkNoAndroidImports` passes
- [x] 3.4 `DisplayController`: a coroutine re-evaluating every 30 s and at once on events, acting only on changes of the desired state, matching screen events against the desired state (waking, foreign off as an override), `userActivity()` throttled to one per second, grace at start and on schedule changes, switching on once when availability ends; `state: StateFlow<DisplayState>`; verify: `TestClock` tests for each scenario of "Switching on time", "Waking during an off period", "Grace at start and on changes", "Manual switch" and "Losing device administration", plus a clock jump of five years forward at night that neither ends a waking period nor skips the switch, and a test that a foreign `SCREEN_OFF` by day is not undone

## 4. Wiring, API and slideshow in `:core`

- [x] 4.1 `SlideshowController.setResting` per design decision 9, separate from the user's pause; verify: tests for "Morning" and "Paused before the night" of "Slideshow rests while the display is off", that no `prefetch` happens while resting, and that a deleted current photo is replaced only when resting ends
- [x] 4.2 `FrameCore` takes a `DisplayPower` (default `DisplayPower.None`), builds and starts `DisplayController`, hands `scheduleAllowed` to the settings and drives `setResting` from the display state; `TestFrame` can pass a `FakeDisplayPower`; verify: `FrameCoreTest` passes, and a `TestFrame` test switches the display off by the schedule and sees the slideshow resting
- [x] 4.3 `displayRoutes`: `GET /display` and `PUT /display` (400 for a body without a boolean `on`, 409 while not available); verify: `TestHttp(..., checkContract = true)` tests for "State at night", "Without token", "Manual switch without device administration" and a manual off with its `nextChange`; `RouteAccessTest` passes with the new routes
- [x] 4.4 Run `./gradlew :core:check`; verify: green, including the API 21 check with Animal Sniffer

## 5. Android

- [x] 5.1 `DisplayAdminReceiver` with `res/xml/device_admin.xml` (`force-lock` only) in the manifest, `AndroidDisplayPower` per design decision 6, `FrameApp` passes it to `FrameCore`, `FrameService` registers `SCREEN_ON`/`SCREEN_OFF` and passes them on, `DisplayActivity` reports touches; verify: `./gradlew :android:lint` without new warnings; the mapping of events to decisions stays in `:core` and is covered by 3.4
- [x] 5.2 Switching on the API 37 emulator and the API 21 emulator (`docs/EMULATOR.md`), device administration activated with `adb shell dpm set-active-admin`; verify: `PUT /display {"on":false}` makes `dumpsys power` show `mWakefulness=Asleep`, `{"on":true}` makes it `Awake` with `DisplayActivity` in front; if `ACQUIRE_CAUSES_WAKEUP` has no effect on API 37, the fallback of the design's first risk is built in and the check is repeated
- [x] 5.3 Device menu entry for device administration (activate with `ACTION_ADD_DEVICE_ADMIN` and an explanation, deactivate with `removeActiveAdmin`, label shows the state), strings in `values/strings.xml` and `values-de/strings.xml`; verify: on the API 37 emulator the entry activates through the system dialog and deactivates again, and `GET /display` shows `available` following within a minute
- [x] 5.4 README: device administration (why, how to activate in the device menu or with `adb`, deactivating before uninstalling, no screen lock); verify: the `adb` command in the README works on the emulator as written
- [x] 5.5 Run `./gradlew check`; verify: green

## 6. Web UI

- [x] 6.1 API client for `GET /display` and `PUT /display`; verify: `npm run typecheck` passes
- [x] 6.2 `SettingsView`: schedule section (switch, four times, minutes after waking) with errors at the field, the switch disabled with the device menu notice while `available` is false; texts in `en.json` and `de.json`; verify: e2e tests "Set the schedule" and "No device administration" pass
- [x] 6.3 Display state with cause and next change, and the button "Turn display off / on", disabled while not available, `GET /display` every 30 s while the settings are open; verify: e2e tests "Switch off now" and "Changed on the frame" (state changed by the mock server, shown within 30 s) pass
- [x] 6.4 Run `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e` in `webadmin/`; verify: all green

## 7. Device tests

- [x] 7.1 `docs/DEVICE_TEST.md`: rows for the display schedule (off and on by schedule on charger, power button at night with the timer after the last touch, manual switch, reboot at night, admin interface and sync during six hours off, deactivating device administration at night), each with its spec scenario; the Lenovo row notes pictureframe's own schedule; verify: every scenario of `display-schedule` that needs hardware has a row
- [x] 7.2 Ask the user before activating device administration on each device (persistent change, CLAUDE.md), then run the rows on the Lenovo, the Frameo and the Galaxy Tab S7 with read-only `dumpsys power` samples; verify: results with date and device are in `docs/DEVICE_TEST.md`, and the open question of the design (backlight off on the Frameo) is answered there. Completed on 26 Oct 2026 at the user's decision with three rows left open (web UI part of the manual switch, Frameo reboot at night, deactivating device administration): the user excluded the Lenovo from further tests, the Frameo was off the network and the Galaxy Tab S7 is paused; see `docs/DEVICE_TEST.md`

## Workflow follow-up

- Sync the delta specs and archive the change after review (`/opsx:archive`); verify that `openspec validate --specs` passes afterwards.
