# Tasks

Implement after `place-search` is merged (it provides `GET /places` and `PlaceResult`).

## 1. Contract (first, `api/openapi.yaml` before the code)

- [ ] 1.1 `DisplaySchedule`: `dimMode` (enum `DimMode`: `fixed`, `sun`) and `place` (`PlaceResult` or `null`), both
  required in responses; the settings patch schema takes both as optional, `place` as a whole object or `null`.
  `BrightnessState`: `sun` with `sunrise` and `sunset` (`date-time` or `null`), itself `null` in mode `fixed`, required.
  Descriptions name the 30-minute fade, the elevation limits and the hold until sunrise (design decisions 2, 3, 7).
  Verify: `npm run lint` in `webadmin/` (Redocly) is green.
- [ ] 1.2 `npm run gen:api` in `webadmin/`. Verify: `npm run typecheck` is green.

## 2. Solar position

- [ ] 2.1 `SolarPosition` in `core/.../display/` with `elevation(instant, lat, lon)` and the crossings of a local day
  for a given limit, by 10-minute steps and bisection (design decision 1). Verify: unit tests against NOAA calculator
  values for 51° N 10° E, 0° 0° and 70° N 25° E at both solstices and equinoxes, within 1 minute; a polar day and a
  polar night give no crossing; `./gradlew :core:check` (API 21 check) is green.

## 3. Settings

- [ ] 3.1 `DimMode`, `FramePlace`, `DisplayScheduleSettings.dimMode` and `.place` with defaults; `SettingsPatch` for
  both, with field validation (`displaySchedule.place.*`) and the rule "mode `sun` needs a place" on the patched
  settings, naming `dimMode` or `place` (design decision 7). Verify: `SettingsApiTest` with the contract check covers
  `display-schedule` › Dim mode (› Defaults, › Dim times kept), › Place of the frame (› Place chosen, › Invalid
  latitude), › Mode sun needs a place (all three); settings JSON written before the change loads as `fixed` without
  a place.

## 4. Brightness policy

- [ ] 4.1 `BrightnessPolicy`: the phases of design decision 3 for both modes, the logarithmic interpolation of
  decision 2, the day brightness of decision 5, and the dim period start of mode `sun` at the sunset crossing for
  `dimmed`, override and snapshot. Verify: pure tests with fixed instants cover `display-schedule` › Brightness by
  the sun (› Winter evening, › Winter morning, › Summer evening, › Sun does not set), › Fade at the dim time (› Half
  way, › Off time inside the fade), › Dim periods (› Weekday evening, › Dim time ignored in mode sun), › Smooth fades
  › Fade near the low end (half way at 20 % from 80 % to 5 %), › Brightness after a dim period › On before sunrise in
  mode sun, and a DST night in both modes.

## 5. Controller

- [ ] 5.1 `DisplayController`: `FADE_TICK_MS = 5 s` while in an unpaused fade phase, writes when the raw target
  changes, `lastWritten`, pause on a differing read until the edge changes (decision 4), snapshot at the start of the
  dim period also in mode `auto`, and a snapshot from the current value when one is missing (decision 5). The log line
  names the mode, never the place. Verify: controller tests with `TestClock` and `FakeScreenBrightness` cover
  `display-brightness` › Changes on the device (› Next dim period, › Quick settings during the evening fade, › Quick
  settings during a fixed fade, › Quick settings by day unchanged), `display-schedule` › Dim period by the sun (› Power
  button at night, › Home Assistant during the evening fade, › Light-sensor automation by day), › Day brightness
  (› Automatic by day, › Restart at night), › Smooth fades › Restart during a fade, › Fade at the dim time › Woken
  later that night, › Dimming changed during a dim period (all three); no write between two equal raw targets.
- [ ] 5.2 `GET /display` reports `brightness.sun` (decision 7). Verify: an API test with the contract check covers
  `display-schedule` › Sun times in the display state (› Sun times in December, › Mode fixed).

## 6. Web UI

- [ ] 6.1 `PlaceSearch` component (decision 8) and the schedule section of `SettingsView`: dim mode, dim time fields only
  for "At a fixed time", place, today's sun times, attribution, save disabled without a place in mode "By the sun";
  texts in `en.json` and `de.json`. Verify: `npm test` unit tests for debounce, abort of a stale request and keyboard
  selection; `npm run lint` and `npm run typecheck` are green.
- [ ] 6.2 e2e (own `OPENFRAME_E2E_PORT`): choose "By the sun", search "kass", pick the place, save; reload shows the
  place and the sun times; "neustadt" lists regions; a name without a match shows the empty state; mode "By the sun"
  without a place cannot be saved; the attribution is next to the place. Verify: `npm run e2e` is green and covers
  `web-admin` › Display schedule in the interface › Dim by the sun, › Place search in the interface (all four),
  › Attribution of the place data › Frame's place.

## 7. Documentation

- [ ] 7.1 README: the dim modes, the elevation limits with an example winter and summer evening, that in summer the
  fade often lies after the off time, the 30-minute fade at a fixed time, the place stored only on the frame, and for
  Home Assistant: a brightness holds until sunrise in the dim period, use `dimmed` as a condition, and let
  light-sensor automations send only changes of at least 5 % or once a minute. Verify: the text reads correctly in
  the diff.

## 8. Checks

- [ ] 8.1 Run `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e`.
  Verify: all are green.

## 9. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 9.1 Update and add rows in `../openframe-private/DEVICE_TEST.md`:
  - update the existing dim rows ("within a minute of the dim time `screen_brightness` reads 51") to the fade: at the
    dim time + 30 min it reads 51, and samples in between fall step by step (`display-schedule` › Dim periods ›
    Weekday evening);
  - Lenovo and Frameo, mode `sun` with a place near the frame: the evening fade starts within a minute of the shown
    sunset and reaches the dim level at the end of civil dusk, sampled every 10 s (`display-schedule` › Brightness by
    the sun › Winter evening);
  - the same with the display on before sunrise in the morning (› Winter morning);
  - in a dark room, the owner judges a fade to 1 % and to 20 % for visible steps (› Smooth fades);
  - quick settings on the Lenovo during a fade: the value holds until the end of dusk (`display-brightness` › Changes
    on the device › Quick settings during the evening fade);
  - Home Assistant's display light at 35 % during the fade holds until sunrise (› Home Assistant during the evening
    fade);
  - `logcat` count of brightness writes during one evening fade.

  Verify: the rows exist with their references, without the place of any real frame.
- [ ] 9.2 Run the rows of 9.1 after the release, updating through the updater with the owner's consent for the update,
  the dim mode, the place and the schedule; afterwards the owner's settings are restored. Verify: results are entered,
  and anything that differs gets a finding in the private findings list.

## Workflow follow-up

- After the release: a change in `ha-openframe` for a select of the dim mode (owner's decision).
- Archive the change after the device rows of 9.2 are done.
