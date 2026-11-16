# Tasks

## 1. Device menu entry in `:android`

- [ ] 1.1 Entry "Android settings" in `DisplayActivity.openDeviceMenu()` per design decisions 1, 2 and 4: shown only when `Settings.ACTION_SETTINGS` resolves, `health.plannedExit("android settings", ADMIN_SCREEN_VALID_MS)` before `startActivity` with `FLAG_ACTIVITY_NEW_TASK`, an `ActivityNotFoundException` only logged. Labels `menu_android_settings` in `values/strings.xml` ("Android settings") and `values-de/strings.xml` ("Android-Einstellungen"). Verify: `./gradlew check` is green
- [ ] 1.2 A unit test for the visibility rule, if the entry list can be built without the activity (for example a small function that takes "settings resolve" and returns the entries' keys, alongside `TouchReportingTest`); otherwise note in the task why the emulator check in 2.1 covers it. Verify: `./gradlew :android:testDebugUnitTest` is green

## 2. Emulator

- [ ] 2.1 On the API 37 emulator with the debug build as HOME app: open the device menu, choose "Android settings", then press Home; open it again and press Back on the start page; open the menu in safe mode (entered with three `kill -9` of the app process in a row, each within 10 minutes of its start). Verify: screenshots show Android's settings, then the slideshow after Home and after Back, the entry in safe mode, and `GET /info` answers while the settings are in front (`kiosk-host` › Android settings from the device menu › Open the settings, › Back to the slideshow, › In safe mode)

## 3. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 3.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references: Frameo, open the settings from the device menu, check that the run journal logs a planned end if the firmware ends openframe, that the server answers within 40 seconds, and that Home brings the slideshow back (› Open the settings, › Firmware ends the app, › Back to the slideshow); Lenovo, the same plus the settings still on screen after 10 minutes without touch (› Settings left open). Verify: the rows exist with their references
- [ ] 3.2 Run the rows of 3.1 after the release. Verify: results entered, a finding for anything that differs

## Workflow follow-up

- Archive the change after the device rows of 3.1 are done.
