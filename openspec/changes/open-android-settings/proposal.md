# Proposal

## Why

On a frame where openframe is the HOME app, Android's settings are hard to reach. The slideshow runs in immersive
fullscreen, so there is no launcher icon, and the status bar appears only for a moment after a swipe from the edge.
Today the device menu (a 3-second press in a corner) only opens two single system screens, device administration and
"Modify system settings", so changing the Wi-Fi, the date and time or anything else in Android needs `adb` or a
factory reset.

On the Frameo, opening the settings is not an option: its firmware ends openframe whenever another app's activity
starts, on every subpage of the settings again, and device tests put the frame into safe mode that way. The owner
decided on 19 Nov 2026 that the entry SHALL NOT be offered there. A review of the first draft also found that the
planned end before a system screen covers one end only, is never withdrawn, and that a crash under the screen brings
the slideshow back over it; those gaps concern the existing device administration and brightness entries as well.

## What Changes

- The device menu on the frame SHALL offer an entry "Android settings" that opens the start page of Android's own
  settings each time, from which everything else in Android is reachable, also in safe mode, when the device has an
  activity for them.
- The entry SHALL NOT be offered on a device whose firmware ends openframe when another activity starts. The frame
  recognizes one by its firmware (the Frameo's frame app is known to the system) or because a run ended right after it
  opened a system screen from the device menu.
- Before any system screen from the device menu, the planned end SHALL stay valid across further ends while the
  screen is in front and SHALL be withdrawn when the slideshow is back; a Java crash SHALL still count.
- openframe SHALL NOT bring the slideshow back over a system screen from the device menu, also not after a crash. The
  way back is the Home button (while openframe is the HOME app) or Back on the settings' start page. No timer, no
  extra protection beyond the 3-second press in the corner.
- While a system screen from the device menu is in front, the display SHALL stay on for up to 10 minutes, as during
  the installation dialog, and then follow the schedule again.
- A changed HOME app SHALL be logged when the slideshow is back after the settings.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `kiosk-host`: new requirements "Android settings from the device menu", "Android settings hidden where the firmware
  ends the app", "Way back from Android's settings", "System screen from the device menu" and "Display while a system
  screen is open"; "Self-healing" changed so that the display does not come back over a system screen while its
  record is valid, and "Planned process end" changed to point to the renewal and withdrawal of a system-screen record.

## Impact

- `:core`: `RunJournal` (a separate slot for system-screen records, carried across starts, withdrawn by
  `cancelSystemScreen`, Java crash before such a record, the learned flag), `HealthState`, `Health`, `PlatformInfo.otherActivityEndsApp`, `FrameCore.systemScreenOpened`/
  `systemScreenClosed`/`androidSettingsOffered`, the log line of `DisplayController.hold`; tests with the JVM fakes.
- `:android`: `DisplayActivity.openDeviceMenu()` gets the entry and routes the two existing system-screen entries
  through `systemScreenOpened`; `onResume` withdraws the record and logs the HOME app; `CrashHandler` skips the display
  restart under a system screen; `AndroidPlatformInfo` checks the firmware; the manifest gets `<queries>`;
  `strings.xml` and `values-de/strings.xml` get the label.
- API and web UI unchanged: the entry is only on the frame.
- The comment on `installUpdate` ("never opens the system settings") stays true: the update path still does not open
  them; only this explicit entry does.
- Rows in the private device checklist, run on a debug build before the release.
