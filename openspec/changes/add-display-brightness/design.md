# Design

## Context

See proposal.md for the motivation. What is already in place shapes the approach:

- `DisplayController` owns the display: one coroutine re-evaluates every 30 s and at once on events, with the pure `DisplaySchedule` (periods) and `DisplayPolicy` (decision). It acts only when the desired state changes and never holds a level, so it does not fight a user who pressed the power button.
- `DisplayPower` in `core/.../platform/` is the pattern for a platform capability that may be missing: `available`, a listener, `DisplayPower.None`, a JVM fake in tests, availability in `GET /display`, 400 on the setting and 409 on `PUT /display` while it is missing, and an entry in the device menu.
- The only brightness openframe sets today is the window brightness 0.01 of the dark wake (`DisplayActivity`), restored to the previous window value afterwards.
- Settings are stored with `JsonStore` (`ignoreUnknownKeys = true`), validated field by field in `SettingsPatch` with paths such as `displaySchedule.weekend.on`; HTTP bodies are parsed strictly.
- The frames: Lenovo, Android 5.0.1 (API 21), with a light sensor; Frameo `PFF_1024`, Android 6.0.1 (API 23), light sensor unknown. On the Frameo the system ends openframe when another activity starts, and `FrameService`'s watchdog brings it back.
- The owner chose Android's system brightness over the window brightness, a dim time per window, a brightness set during a dim period holding until it ends, and changes on the device holding until openframe next applies its own.

## Goals / Non-Goals

**Goals:**

- All decisions in `:core`, tested on the JVM with `TestClock` and a fake brightness: dim periods across midnight, whole-day windows, DST, restarts during a dim period, changes on the device.
- `:android` stays thin: read and write two system settings, report the permission and the light sensor.
- Mode `system` with no dim time behaves exactly as openframe does today.

**Non-Goals:**

- A brightness curve, more than one dim time per window, or dimming without the schedule being active.
- Android 9+'s perceptual brightness curve: the level is linear on the system setting's scale (see Risks).

## Decisions

### 1. Android's system brightness, not the window brightness

The frame writes `Settings.System.SCREEN_BRIGHTNESS_MODE` and `Settings.System.SCREEN_BRIGHTNESS` (0 to 255). That is what the owner asked for, and it is the only way to switch on Android's automatic brightness. It needs `WRITE_SETTINGS`: a normal permission on API 21 and 22, an app-op that the user allows in the system's "Modify system settings" screen from API 23.

- *Alternative:* `WindowManager.LayoutParams.screenBrightness` of `DisplayActivity`. No permission, but no automatic mode, no effect while another app is in front, and the system's own slider would show a different value from the one the display has.

The dark wake keeps its window brightness: a window value overrides the system value while it is set, and is restored afterwards as today.

### 2. A `ScreenBrightness` platform interface with raw values

```kotlin
interface ScreenBrightness {
    val available: Boolean      // may write system settings
    val autoSupported: Boolean  // the device has a light sensor
    fun read(): RawBrightness   // value 0..255 and automatic on/off; needs no permission
    fun write(target: RawBrightness): Boolean
    companion object { val None: ScreenBrightness }
}
data class RawBrightness(val value: Int, val auto: Boolean)
```

The percent scale lives in `:core` (`BrightnessScale`): `raw = max(1, round(level × 255 / 100))`, `level = round(raw × 100 / 255)`. It is pure and tested, and `:android` only moves integers. `write` with `auto = true` sets only the mode, leaving the manual value for when automatic is switched off again. `None` is not available, does not support automatic and reads 100 %, not automatic. `FakeScreenBrightness` in `core/src/test/.../testing/` records writes and lets tests change the value "on the device" and the permission.

On Android: `available` is `Settings.System.canWrite(context)` from API 23, `true` below; `autoSupported` is `SensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null`.

### 3. Dim periods in `DisplaySchedule`

`DisplaySchedule.dimAt(at, settings): DimPeriod?` returns the dim period around `at`, or `null`. It looks at the day periods before merging: for the day on which an on period starts, the window's dim time on that date (resolved with `ZonedDateTime.of` as the on and off times are) starts the dim period, and the end of that day's period ends it; for a whole-day window, the end of the day. A dim period that ends where the next day's on period starts, in a schedule whose periods touch, ends there anyway: that is an edge, and the stored brightness applies.

`DisplaySchedule.nightAfterDim(at, settings): Period?` returns the off period containing `at` if a dim period ended at its start, for spec `display-schedule` › Dimmed during the night.

Validation of a dim time (spec `display-schedule` › Dim settings) uses the same arithmetic on minutes of the day: for `on < off`, `on < dim < off`; for `off < on`, `dim > on` or `dim < off`; for `on == off`, `dim != on`. Because a patch may change the on or off time alone, `SettingsPatch` validates the dim time against the window after the patch, and names the dim field.

### 4. `BrightnessPolicy`, pure, evaluated in `DisplayController`'s loop

The brightness depends on the display's periods, waking and the manual switch, which `DisplayController` already owns, so its loop also computes the brightness rather than a second controller with a second clock. `BrightnessPolicy.decide(inputs, now, zone)` returns a `BrightnessDecision(target, dimmed, edge)`:

1. Not `available`: target `null` (leave alone), `dimmed = false`.
2. A brightness override (decision 6) bound to the current dim period or night: its level.
3. Schedule active, its clock set, and `dimAt` or (`nightAfterDim` while the display is on) returns a period: the dim level, `dimmed = true`.
4. Otherwise the stored mode: `manual` → the level, `auto` → automatic, `system` → the snapshot if one exists (decision 5), else `null`.

`edge` identifies the decision's cause: the dim period's start, the night's start, the override, or the stored settings' version. As with the display, the controller writes only when `edge` changes, never because the read value differs from the target. That is what lets a change on the device hold until the next edge (spec `display-brightness` › Changes on the device). The start of the app, a change of the brightness or dim settings, and the permission becoming available are edges as well; the end of a dark wake is not one.

- *Alternative:* re-apply whenever the read value differs. Simpler, but it fights the user, which the owner ruled out.

### 5. Snapshot for mode `system`

When a dim period begins in mode `system`, the controller reads the current `RawBrightness` and stores it in `brightness-restore.json` with `JsonStore` before writing the dim level. At the next edge that is not a dim period, it writes the snapshot back and deletes the file. The file survives a restart, so a reboot at night still restores in the morning. Changing the mode to `manual` or `auto` deletes it.

### 6. Brightness through `PUT /display`

`DisplaySwitch` becomes `{on?: boolean, brightness?: integer 1..100}` with `minProperties: 1`. The route validates the body, then checks availability of each part it carries (409 `display_unavailable` before 409 `brightness_unavailable`), then acts. A brightness:

- outside a dim period or night: patches the settings to `{"brightness": {"mode": "manual", "level": n}}` through `SettingsRepository`, so it is stored and the settings version changes; the next evaluation applies it at once.
- inside one: becomes a `BrightnessOverride(level, edge)` in the controller, bound to that dim period or night like the display's `Override` is bound to its period; it ends with that period and is not stored, so it does not survive a restart.

The answer is the `DisplayState` after both parts were applied.

### 7. API shapes

- `Settings.brightness: {mode: system|manual|auto, level: 1..100}`, required in the response, partial in `SettingsPatch`. Errors on `brightness.mode`, `brightness.level`.
- `DisplaySchedule.dimLevel: 1..100` and `DisplayWindow.dim: "HH:MM" | null`, required (nullable) in the response; in the patch `null` removes a dim time. Errors on `displaySchedule.weekdays.dim`, `displaySchedule.dimLevel`.
- `DisplayState.brightness: {available, autoSupported, level: 0..100, auto, dimmed}`, required. `level` and `auto` come from `read()`, refreshed on every evaluation, so a change on the device shows within 30 s.
- `Conflict` gains `brightness_unavailable`.

All additions are fields in objects that existing clients read by key; `ha-openframe` 0.1.0 and the web UI before this change ignore them.

### 8. Permission on the frame

The manifest declares `android.permission.WRITE_SETTINGS`. From API 23 the device menu shows an entry "Allow brightness control" with its state, which starts `Settings.ACTION_MANAGE_WRITE_SETTINGS` with `package:<applicationId>`. No broadcast reports the grant, so the controller reads `available` on every evaluation, and `DisplayActivity.onResume` sends an event so that returning from the system screen is seen at once. The README documents `adb shell appops set <package> WRITE_SETTINGS allow` for devices whose settings lack the screen.

### 9. Web UI

The settings get a brightness section (mode as three choices, level slider, dim level in the schedule section, a dim time with "none" per window), using the existing field error display. The display state section gets a level, a "dimmed by the schedule" note and a slider that sends `PUT /display {brightness}` on release, not while dragging, so a drag is one request.

## Risks / Trade-offs

- [The Frameo's ROM lacks the "Modify system settings" screen, or the system ends openframe when it opens] → The watchdog brings openframe back; the README's `adb appops` command grants the permission without the screen. A device test row checks the screen on the Frameo.
- [Raw value 1 is black on a panel] → The device test checks 1 % in a dark room on both frames. If a panel goes dark, `BrightnessScale` gets a higher floor, which changes no spec text.
- [Android 9+ maps the slider perceptually, so 50 % in openframe looks brighter than 50 % in the system slider] → Accepted: both frames run Android 5 and 6. The Galaxy Tab S7 is paused.
- [A brightness changed on the device during a dim period in mode `system` is overwritten by the snapshot in the morning] → Accepted: the snapshot is what the user had before the evening.
- [The system brightness stays as openframe left it after uninstalling] → The README says so; mode `system` before uninstalling restores nothing but changes nothing either.
- [On Android 6.0 `Settings.System.canWrite` is known to misreport on some builds] → The device test reads `available` before and after granting on the Frameo; if it misreports, `available` falls back to a write test of the current value.

## Migration Plan

- Additive: stored settings without `brightness` or dim fields load with the defaults (mode `system`, no dim time), so an update changes nothing on the display.
- Downgrade: older versions read the settings with `ignoreUnknownKeys` and ignore the new fields; `brightness-restore.json` stays unused.
- Release as a minor version (`feat:`). `ha-openframe` follows with its own change and raises its minimum openframe version for the light's brightness.
