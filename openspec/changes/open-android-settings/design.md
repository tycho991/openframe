# Design

## Context

`DisplayActivity.openDeviceMenu()` builds the device menu as a list of label/action pairs, opened by a 3-second press
in a corner. Two entries already start system screens (device administration, "Modify system settings"); before each
they call `health.plannedExit(reason, ADMIN_SCREEN_VALID_MS)` (10 minutes), because the Frameo's firmware ends the
HOME app as soon as another activity starts. After such an end the watchdog alarm brings the service back (below
API 31) and the display returns with the Home button (`kiosk-host` › Server independent of the display › App ended by
the firmware). openframe is the HOME app on both frames; the Frameo has a working Home button. See proposal.md for why.

## Goals / Non-Goals

**Goals:**
- One entry that reaches every part of Android's settings, with no change to the API or the web UI.
- The firmware's end of the app after opening the settings is not counted as a crash.

**Non-Goals:**
- Direct entries for single screens (Wi-Fi, date and time); the start page reaches them.
- Bringing the slideshow back by a timer, or a PIN for the entry (the owner's choice).
- Opening the settings from the web UI or the API.

## Decisions

### 1. `Settings.ACTION_SETTINGS`, shown only when it resolves

The entry starts `Intent(Settings.ACTION_SETTINGS)` with `FLAG_ACTIVITY_NEW_TASK`, so the settings run in their own
task and Back from their start page ends that task and shows the HOME app, openframe. The menu shows the entry only
when `packageManager.resolveActivity(intent, MATCH_DEFAULT_ONLY)` is not `null`, checked each time the menu opens.
`startActivity` is wrapped like the other entries so that an `ActivityNotFoundException` between the check and the
start only logs.

*Alternative:* always show the entry and show a notice when nothing resolves. Rejected: an entry that never works on
a device is noise; both frames and the emulator have the Settings app.

### 2. Planned end with the existing validity

Before `startActivity` the entry calls `health.plannedExit("android settings", ADMIN_SCREEN_VALID_MS)`. The firmware
ends the app right after the start, so 10 minutes is enough; a longer stay in the settings needs no longer validity.
The constant keeps its name; renaming it is not worth the churn.

### 3. No automatic return

openframe does nothing while its display is in the background: no timer, no relaunch from the service. The watchdog
only starts the service, never the display, so it does not pull the slideshow over the settings either. This is the
owner's choice: whoever opens the settings leaves them with Home or Back.

### 4. Position and label

The entry stands after "Brightness control" and before "Unpair all devices": the system screens stay together.
Label "Android settings" / "Android-Einstellungen".

## Risks / Trade-offs

- [Settings left open on a frame without a Home button] → Both frames have one; Back also leads out. The spec
  scenario "Settings left open" makes the behaviour explicit; a timer can follow as its own change if it is missed.
- [The device's screen timeout applies while the settings are in front, since openframe's keep-awake is only on its
  own window; on the Lenovo the display goes off after a minute] → Accepted; the power key wakes it into the settings.
  The display schedule keeps working, because it runs in the service.
- [Someone changes a setting openframe relies on (HOME app, Wi-Fi, screen timeout)] → The same risk as today's adb
  access; openframe already logs a lost HOME preference after an update. The 3-second corner press stays the barrier.
- [Opening the settings leaves the kiosk for anyone near the frame] → Accepted by the owner; the device menu already
  allows unpairing all devices.

## Migration Plan

None: one more menu entry. Rollback is an older APK.
