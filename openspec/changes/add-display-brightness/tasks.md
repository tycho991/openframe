# Tasks

## 1. Contract first

- [x] 1.1 `api/openapi.yaml` per design decision 7: `Settings.brightness` (`mode` enum `system|manual|auto`, `level` 1 to 100), `DisplaySchedule.dimLevel`, `DisplayWindow.dim` (`HH:MM` pattern or null), the matching partial fields in `SettingsPatch`, `DisplaySwitch` with optional `on` and `brightness` and `minProperties: 1`, `DisplayState.brightness` (`available`, `autoSupported`, `level` 0 to 100, `auto`, `dimmed`), `brightness_unavailable` in the `Conflict` description and the `PUT /display` description. Verify: `npm run lint` in `webadmin/` passes (Redocly)
- [x] 1.2 Run `npm run gen:api` in `webadmin/`. Verify: `schema.d.ts` has the new fields, and `git diff --stat` shows only generated changes there

## 2. Settings and schedule arithmetic in `:core`

- [x] 2.1 `BrightnessSettings` (`mode`, `level`) in `Settings`, `dimLevel` in `DisplayScheduleSettings` and `dim: String?` in `DisplayWindow` with the defaults of the specs; `SettingsPatch` partial with ranges, the time pattern, `null` removing a dim time, and the dim time checked against the window after the patch (design decision 3). Verify: tests for `display-brightness` › Brightness setting and `display-schedule` › Dim settings (all scenarios), for `dimLevel` 0 and 101, an unknown field `brightness.foo`, and that a stored `settings.json` without the new fields loads with the defaults and keeps its other values
- [x] 2.2 `SettingsRepository.patch` with an injected brightness capability: mode `manual` or `auto` rejected on `brightness.mode` while not available, `auto` also without a light sensor; level alone and mode `system` accepted. Verify: tests for › Permission as precondition › Manual without permission, › Automatic brightness needs a light sensor › No light sensor, and a level-only patch while not available
- [x] 2.3 `BrightnessScale` per design decision 2. Verify: unit tests for 1 % → 3, 100 % → 255, raw 0 → 0 %, raw 1 → 0 % read but never written, and that every level 1 to 100 survives the round trip
- [x] 2.4 `DisplaySchedule.dimAt` and `nightAfterDim` per design decision 3. Verify: unit tests for › Dim periods › Weekday evening, a dim time after midnight in a window across midnight, a whole-day window, Friday's dim period followed by a weekend window, a dim time in the DST gap in `Europe/Berlin`, and `nightAfterDim` true after a dimmed evening and false after an undimmed one

## 3. Brightness in the display controller

- [x] 3.1 `ScreenBrightness`, `RawBrightness` and `ScreenBrightness.None` in `core/.../platform/`; `FakeScreenBrightness` in `core/src/test/.../testing/` that records writes and can change the value and the permission from outside. Verify: `./gradlew :core:checkNoAndroidImports` passes
- [x] 3.2 `BrightnessPolicy.decide` as a pure function per design decision 4 (precedence: not available, override, dim period or night with the display on, stored mode) with `dimmed` and `edge`. Verify: table tests for each precedence step and for the schedule off or its clock not set
- [x] 3.3 `DisplayController` evaluates the brightness in its loop and writes only on a new `edge`; the snapshot for mode `system` in `brightness-restore.json` (design decision 5); an event for the permission and for `DisplayActivity` resuming; `DisplayState.brightness` from `read()` on each evaluation. Verify: `TestClock` tests for each scenario of › Modes of the brightness, › Losing the permission, › Changes on the device, › Brightness in the display state, `display-schedule` › Dimmed during the night, › Brightness after a dim period (including a restart between evening and morning with the snapshot) and › Dimming changed during a dim period
- [x] 3.4 Brightness through `PUT /display` per design decision 6: validation of the whole body before acting, 409 `display_unavailable` before `brightness_unavailable`, a stored `manual` level outside a dim period, a `BrightnessOverride` bound to the dim period or night inside one. Verify: `TestHttp(..., checkContract = true)` tests for › Brightness now (all scenarios), › Atomic display requests, › Permission as precondition › Brightness now without permission, an empty body (400) and a level of 101 (400); `RouteAccessTest` passes
- [x] 3.5 `FrameCore` takes a `ScreenBrightness` (default `None`) and passes it on; `TestFrame` can pass the fake. Verify: `./gradlew :core:check` is green, including the API 21 check with Animal Sniffer

## 4. Android

- [x] 4.1 `WRITE_SETTINGS` in the manifest, `AndroidScreenBrightness` per design decision 2 (`canWrite` from API 23, the light sensor, `Settings.System` reads and writes), wired in `FrameApp`; `DisplayActivity.onResume` sends the controller's event. Verify: `./gradlew :android:lint` without new warnings (a lint warning on the permission is suppressed with a comment giving the reason)
- [x] 4.2 Device menu entry "Allow brightness control" from API 23 with its state, opening `ACTION_MANAGE_WRITE_SETTINGS` for the package; strings in `values/strings.xml` and `values-de/strings.xml`. Verify: on the API 37 emulator the entry opens the system screen, allowing it makes `GET /display` report `brightness.available` true within a minute, and on the API 21 emulator the entry is absent and `available` is true
- [x] 4.3 On both emulators: `PATCH /settings {"brightness":{"mode":"manual","level":40}}` and then `auto` and `system`. Verify: `adb shell settings get system screen_brightness` reads 102 and `screen_brightness_mode` 0 after the first, `screen_brightness_mode` 1 after `auto` on an emulator with a light sensor (400 on one without), and nothing changes after `system`
- [x] 4.4 README: brightness control, the permission and the device menu entry, `adb shell appops set <package> WRITE_SETTINGS allow` for devices without the screen, and that the system brightness stays as set after uninstalling. Verify: the `adb` command works on the API 37 emulator as written
- [x] 4.5 Run `./gradlew check`. Verify: green

## 5. Web UI

- [x] 5.1 API client for `PUT /display` with `brightness`. Verify: `npm run typecheck` passes
- [x] 5.2 `SettingsView`: brightness section (mode, level), automatic only with `autoSupported`, only Android's setting with the device menu notice while not available; dim time with "none" per window and the dim level in the schedule section with errors at the field; texts in `en.json` and `de.json`. Verify: e2e tests for `web-admin` › Brightness in the settings (all scenarios) and › Display schedule in the interface › Dim in the evening pass
- [x] 5.3 Display state: current level, "dimmed by the schedule", a slider that sends `PUT /display` on release, disabled while not available. Verify: e2e test for › Display state and manual switch › Brightness now (one request per drag) passes, and the existing scenarios still pass
- [x] 5.4 Run `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e` in `webadmin/` (own `OPENFRAME_E2E_PORT`). Verify: all green

## 6. Devices (each persistent device change only with the owner's consent at that moment)

- [x] 6.1 Rows in `../openframe-private/DEVICE_TEST.md` under a new section "Brightness", each with its `capability › Requirement › Scenario` reference: › Modes of the brightness › Manual level and › Back to system; › Level scale › Lowest level in a dark room; › Permission on the frame › Allow on Android 6 (Frameo, including whether the system ends openframe when the screen opens, and `canWrite` before and after) and › Android 5 (Lenovo); › Automatic brightness needs a light sensor (which frame has one); › Changes on the device › Quick settings by day; `display-schedule` › Dim periods › Weekday evening, › Dimmed during the night › Power button at night, › Brightness after a dim period › Morning in mode system. Verify: every row names a scenario that exists in this change's specs
- [ ] 6.2 Ask the owner, then install a build with this change on the Lenovo and the Frameo and grant the permission on the Frameo; run the rows of 6.1 with read-only `settings get system` samples. Verify: results with date and device in the rows, and the risks of the design (Frameo screen, raw value 1, `canWrite`) are answered there

## Workflow follow-up

- After the release with this change: propose the light entity in `ha-openframe` (the switch `display` becomes a light with brightness), with its contract updated to that release.
- Sync the delta specs and archive the change after review (`/opsx:archive`); verify that `openspec validate --specs` passes afterwards.
