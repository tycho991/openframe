# Proposal

## Why

On the Frameo the owner cannot reach Android's settings. openframe is its only HOME app and keeps the slideshow in
immersive fullscreen, so there is no launcher icon and no status bar to open Settings from. Today the device menu (a
3-second press in a corner) only opens two single system screens, device administration and "Modify system
settings", so changing the Wi-Fi, the date and time or anything else in Android needs `adb` or a factory reset.

## What Changes

- The device menu on the frame SHALL offer an entry "Android settings" that opens the start page of Android's own
  settings, from which everything else in Android is reachable.
- The entry SHALL be shown on every Android version and also in safe mode, but only when the device has an activity
  for Android's settings.
- Opening it SHALL record a planned process end before the screen starts, as for device administration, because the
  Frameo's firmware ends openframe when another activity starts.
- openframe SHALL NOT bring the slideshow back over the settings by itself: the way back is the Home button (openframe
  is the HOME app) or Back. No timer, no extra protection beyond the 3-second press in the corner.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `kiosk-host`: new requirement "Android settings from the device menu": the entry, when it is shown, the planned end
  and the way back to the slideshow.

## Impact

- `:android`: `DisplayActivity.openDeviceMenu()` gets one entry; `strings.xml` and `values-de/strings.xml` get its
  label. `:core` unchanged apart from the existing `health.plannedExit`.
- API and web UI unchanged: the entry is only on the frame.
- The comment on `installUpdate` ("never opens the system settings") stays true: the update path still does not open
  them; only this explicit entry does.
- Device rows in `../openframe-private/DEVICE_TEST.md` for the Frameo and the Lenovo.
