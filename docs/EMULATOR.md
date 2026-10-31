# Emulator checks

How the emulator checks were run, so that anyone can repeat them. The emulators need
about 8 GB under the Android SDK (system images 7 GB, emulator 0.9 GB).

## Images and virtual devices

```bash
sdkmanager "emulator" "system-images;android-21;default;x86" "system-images;android-37.0;google_apis;x86_64"
avdmanager create avd -n of21 -k "system-images;android-21;default;x86" -d "Nexus 7"
avdmanager create avd -n of37 -k "system-images;android-37.0;google_apis;x86_64" -d pixel_tablet
```

- **API 21 needs an SD card image**, otherwise the app falls back to internal storage and the MediaStore check
  shows nothing: `mksdcard 512M ~/.android/avd/of21.avd/sdcard.img`, start with `-sdcard <that file>`.
- **Panel size.** `wm size` only changes the logical size, `screencap` keeps the physical one. Set the real panel in
  `~/.android/avd/of21.avd/config.ini` instead: `hw.lcd.width=600`, `hw.lcd.height=1024`, `hw.lcd.density=160`
  for 1024×600, `1200`, `1920`, `240` for 1920×1200 (the natural orientation is portrait, the app runs in landscape,
  so rotate screenshots by 90°). `skin.name` in the same file overrides `hw.lcd.*`; set it to the same size
  (`600x1024` or `1200x1920`).
- **API 37 needs a bigger data partition** (`disk.dataPartition.size=6G` in its `config.ini`, start once with
  `-wipe-data`) and crashes SurfaceFlinger in a loop without a GPU (`RegionSamplingThread`, started by the Google
  launcher). Disable the launcher right after the first boot:
  `adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher`. The app is then the only HOME app.

- **API 21 freezes while its display is off.** Its kernel has no `/sys/power/wake_lock`, so wake locks do not keep
  the guest running; `adb` hangs until a key event from the console wakes it:
  `adb -s emulator-5554 emu event send EV_KEY:KEY_POWER:1 EV_KEY:KEY_POWER:0`. Android 5 also has no
  `dpm set-active-admin`; activate device administration through the frame's device menu there.

Start headless, for example:

```bash
emulator -avd of21 -port 5554 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
  -memory 1536 -sdcard ~/.android/avd/of21.avd/sdcard.img
```

## adb next to the Frameo

The default adb server also knows the Frameo, and `adb forward` then refuses with "more than one device". Use a
second server for the emulator: `adb -P 5038 connect localhost:5557` (adb port of console port 5556, `localhost:5555`
for 5554), then `adb -P 5038 -s <device> forward tcp:18080 tcp:8080`. Do not use `pkill -f` with the AVD name from
inside a command that contains that name, it kills the calling shell too.

## Pairing without looking at the screen

The pairing code on the frame is a text view, so `adb shell uiautomator dump /sdcard/ui.xml` lists it as
`123 456`. Pair with `POST /api/v1/pairing` (`code`, `deviceName`) and use the returned token. The empty state shows
the code only while no device is paired, the hint (tap) shows it always.

## What was checked

The author's results are kept in a private companion repository together with the findings; the
expected behaviour is in the scenarios of `openspec/specs`. The screenshots in `screenshots/` come from the API 21
emulator at both panel sizes. The four `*-menu.png` show the current device menu and typeface, in landscape with
`core/src/test/resources/fixtures/landscape.jpg` as the only photo. All others are historical: they
were taken before the frame used the Inter typeface and before the device menu had the display schedule, so they
can differ in details from the current build.
