# Proposal

## Why

A frame can switch its display on and off, but it cannot change how bright it is: a frame that suits the living room by day glares in the evening, and Home Assistant cannot dim it like a lamp. The Home Assistant integration (`ha-openframe`) wants to show the display as a light with on/off and brightness, and needs the frame's API for it.

## What Changes

- **Brightness setting.** A new setting `brightness` with a mode, `system` (default: openframe leaves Android's brightness alone, as today), `manual` (a fixed level of 1 to 100 %) or `auto` (Android's automatic brightness, only on devices with a light sensor), and the level for `manual`.
- **Android's system brightness.** openframe sets the brightness through Android's system settings. That needs the permission to modify system settings: granted at installation on Android 5, granted once by hand from Android 6 on. A new entry in the frame's device menu opens the system screen for it.
- **Dimming in the display schedule.** Each window of the schedule (weekdays, weekend) gets an optional dim time; from there to the off time, and while the display is woken during the night that follows, the frame uses one dim level (default 20 %).
- **Brightness now, through `PUT /display`.** The body may carry a `brightness` level besides or instead of `on`. Outside a dim period it becomes the stored `manual` level; during a dim period it holds until that period ends.
- **Brightness in the display state.** `GET /display` reports whether brightness can be set, whether automatic brightness is supported, the brightness and mode Android has now, and whether the schedule dims right now.
- **Changes on the device are respected.** A brightness changed in Android's quick settings holds until openframe next applies its own: at start, on a change of the setting, and when a dim period begins or ends.
- **Web UI.** The settings get a brightness section (mode, level, dim level) and a dim time per schedule window; the display state gets a brightness slider that acts at once.

### Out of scope

- The Home Assistant light entity itself: a separate change in `ha-openframe`, after a release of openframe with this change.
- Window brightness of openframe's own activity (except the existing dark wake for the network, which stays as it is).
- More than one dim time per window, or brightness curves over the day.
- Root access or device owner provisioning to grant the permission without a tap.

## Capabilities

### New Capabilities

- `display-brightness`: the brightness setting and its modes, the permission and its device menu entry, applying the brightness, brightness now through the API, changes made on the device, and the brightness in the display state.

### Modified Capabilities

- `display-schedule`: the dim time per window and the dim level, when the frame dims, and the brightness while woken at night.
- `web-admin`: brightness in the settings and the display state, and the dim time in the schedule section.

## Impact

- `api/openapi.yaml`: `Settings.brightness`, `DisplaySchedule.dimLevel`, `DisplayWindow.dim`, the matching `SettingsPatch` fields, `DisplaySwitch` with optional `on` and `brightness`, `DisplayState.brightness`, the conflict code `brightness_unavailable`. Additive for existing clients; `ha-openframe` 0.1.0 ignores the new fields.
- `:core`: settings, patch validation, a pure brightness policy next to `DisplayPolicy`, a `ScreenBrightness` platform interface with a JVM fake, a controller wired into `FrameCore`, the display routes.
- `:android`: `WRITE_SETTINGS` in the manifest, `AndroidScreenBrightness`, the device menu entry, strings in English and German.
- `webadmin/`: settings section, schedule fields, slider in the display state, `en.json` and `de.json`.
- `README.md`: the permission, how to grant it on the frame or with `adb`.
- `../openframe-private/DEVICE_TEST.md`: rows for the brightness on the Lenovo (Android 5.0.1) and the Frameo (Android 6.0.1).
- Follow-up in `ha-openframe`: the switch `display` becomes a light with brightness.
