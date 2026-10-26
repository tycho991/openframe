# Proposal

## Why

A picture frame that lights up the bedroom or the living room all night wastes power, wears the backlight and disturbs. pictureframe switches the display off on a day/night schedule. openframe has no such schedule: its spec even requires that the display never goes off. A black screen is not enough, because an LCD backlight still glows. The display should really be off.

## What Changes

- **Display schedule.** The frame switches its display off and on by itself. There are two windows, one for weekdays (Monday to Friday) and one for the weekend (Saturday and Sunday), each with an "on" and an "off" time. A window may cross midnight. The schedule is off by default.
- **Real display off.** The display is switched off the way the power button does it, through Android's device administration (`lockNow`, policy `force-lock` only). Black screens, dimming and root are not part of this change. The only night mode is "off".
- **Device administration is a precondition.** The schedule and the manual switch work only while openframe is an active device administrator. Without one, the schedule cannot be switched on (400) and the manual switch answers 409. The user activates or deactivates device administration once, in the device menu on the frame (3-second press in a corner) or with `adb`. Deactivating it switches the display on and leaves the stored schedule inactive.
- **Waking at night.** When the display is really off, the touchscreen is off too. The power button wakes the frame. After that the frame reacts to touches as usual, and it goes off again N minutes after the last touch (1 to 120, default 5). If a boot or a change to the schedule falls into the night, the display also goes off only after these N minutes, not at once.
- **Manual switch.** "Display off now / on now" in the web UI and in the API. It holds until the schedule's next switching time. Without an active schedule, "off" holds until someone switches the display on again (web UI or power button).
- **The slideshow rests while the display is off.** No photo changes and no rendering for the display. When the display comes on, the next photo appears at once. The server stays reachable and the sync of the sources continues.
- **API.** New endpoints `GET /display` and `PUT /display`. `Settings` gains `displaySchedule`. Both are additive: clients that do not know them keep working.

### Out of scope

- Night modes "dim" and "black", auto brightness and brightness control.
- Root as a fallback for switching the display off.
- A window per single weekday, holidays and switching times from the sun.
- Waking by touch while the display is really off, which the hardware does not allow.
- Home Assistant or other remote control beyond the frame's own API.

## Capabilities

### New Capabilities

- `display-schedule`: switching the display off and on by schedule, by the manual switch and by waking, the precondition of device administration with its entry in the device menu, the resting slideshow and the display state in the API.

### Modified Capabilities

- `kiosk-host`: "Keep the display awake" applies only while neither the schedule, the manual switch nor the end of a waking period calls for the display to be off.
- `web-admin`: the settings gain the schedule, the current display state and the manual switch, with a notice when device administration is not active.

## Impact

- `api/openapi.yaml` first: `GET /display`, `PUT /display`, the `DisplayState` schema, and `displaySchedule` in `Settings` and `SettingsPatch`. Then the Kotlin DTOs by hand and `npm run gen:api`. `RouteAccessTest` covers the new routes (token required).
- `:core`: a new package `display` (the schedule's arithmetic, a controller with its own state machine, routes), a new platform interface `DisplayPower` with a JVM fake in tests, a time zone on `Clock`, `displaySchedule` in `Settings`/`SettingsPatch` with validation, and a resting mode in `SlideshowController`. `FrameCore` builds and starts the controller.
- `:android`: a `DeviceAdminReceiver` with `res/xml/device_admin.xml` (`force-lock`), `AndroidDisplayPower` (`DevicePolicyManager.lockNow`, a wake lock with `ACQUIRE_CAUSES_WAKEUP` to switch on), `SCREEN_ON`/`SCREEN_OFF` receivers in `FrameService`, touch reports from `DisplayActivity`, the entry in the device menu, and strings in `values` and `values-de`.
- `webadmin/`: `SettingsView` (schedule, state, switch), the API client, `en.json` and `de.json`, unit and e2e tests.
- Devices: activating device administration is a persistent change on the device and needs the user's consent each time (CLAUDE.md). pictureframe is a device administrator on the Lenovo with a schedule of its own, and it stays untouched; the device tests note how the two coexist. Before uninstalling openframe, its device administration has to be deactivated.
- `docs/DEVICE_TEST.md`: switching off and on by schedule on the Lenovo (API 21), the Frameo (API 23) and the Galaxy Tab S7 (API 36), waking with the power button, reachability over a night, a reboot at night.
