# Tasks

## 1. Clock rule in `:core`

- [x] 1.1 `DisplayPolicy.decide` with `CLOCK_FLOOR` (`2026-01-01T00:00:00Z`) per design decision 5: before it the decision is "decide nothing" with cause `none`; verify: table tests for a clock in 2021 with an active schedule at night and by day (both `on = null`, `NONE`, `nextChange` null), and that the existing policy tests pass unchanged
- [x] 1.2 `DisplayController` treats the clock crossing the floor like a schedule change (`grace()`) and logs "Clock not set … schedule paused" and "Clock set: schedule applies" once each; verify: `TestClock` tests for each scenario of "Schedule only with a set clock" (boot at 15 May 2021 00:00 with the display staying on; a jump to 23:00 on a weekday going off 5 minutes after the last touch; a jump to 10:00 reporting on by the schedule until 22:00), and that a display the frame had switched off goes on within a minute when the clock jumps back before the floor

## 2. Network and dark wake in `:core`

- [x] 2.1 `NetworkLink` in `core/.../platform/` (`connected`, `requestReconnect()`, listener) with `NetworkLink.None`, and `FakeNetworkLink` in `core/src/test/.../testing/` that records requests and lets a test drop and restore the network; `FrameCore` takes it (default `None`) and `TestFrame` can pass the fake; verify: `./gradlew :core:checkNoAndroidImports` passes and `FrameCoreTest` passes unchanged
- [x] 2.2 `DisplayController` asks for a reconnect at most once a minute while the network is missing and the display is off by the schedule or the manual switch, and stops when it is back or the display goes on, per design decision 2; verify: tests for "Router drops the frame at night" and "Network back", that nothing is asked while the display is on by day or woken by the power button, and that a network lost by day and still missing at the off time is asked for from the off time on
- [x] 2.3 Dark wake per design decisions 2 and 3: after ten minutes without network, display on for one minute with `dark` set, off again when the network is back or the minute is over, the next one ten minutes after the previous ended; the screen-on of a dark wake is not waking; `DisplayState` reports off with the decision's cause; `dark: StateFlow<Boolean>`; the log lines of design decision 6; verify: tests for each scenario of "Dark wake for the network" (including 25 minutes without network giving exactly two dark wakes of one minute, and a touch during one starting a normal waking period with `cause: wake`), and a `TestFrame` test that the slideshow keeps resting during a dark wake
- [x] 2.4 Run `./gradlew :core:check`; verify: green, including the API 21 check with Animal Sniffer

## 3. Android

- [x] 3.1 `AndroidNetworkLink` per design decision 1 (`connected` from the `NetworkCallback` of `FrameService`, `requestReconnect()` with `WifiManager.reconnect()` and `startScan()`), permission `CHANGE_WIFI_STATE` in the manifest, passed to `FrameCore` by `FrameApp`; verify: a Robolectric test that `requestReconnect()` reaches the shadow `WifiManager` and that a lost and a regained network reach the listener
- [x] 3.2 `DisplayActivity` shows a black view above the slideshow and sets the window brightness to `0.01f` while `dark` is true, and restores both when it clears, per design decision 4; verify: a Robolectric test of the activity with a `dark` flow switched on and off (black view visible, `screenBrightness` 0.01 then back), and `./gradlew :android:lintDebug` without new warnings
- [x] 3.3 Run `./gradlew check`; verify: green, including `checkNoAndroidImports`, the API 21 check and Android Lint

## 4. Devices

- [ ] 4.1 Rows in `docs/DEVICE_TEST.md` under "Display schedule", each with its spec scenario: Wi-Fi dropped at night on the Lenovo and the Frameo (display-schedule › Asking for the network while the display is off › Router drops the frame at night; whether the reconnect request alone brings it back, and if not, how long the dark wake takes), how dark the dark wake is (display-schedule › Dark wake for the network › Still no network after ten minutes; the brightness of design.md, Open Questions), and a boot without network on the Frameo (display-schedule › Schedule only with a set clock › Boot without network at night); verify: the rows exist with their references
- [ ] 4.2 Ask the user before installing the build on each device, before dropping a device's Wi-Fi (and agree how: on the router, or with root on the device) and before a reboot without network; run the rows of 4.1, reading the night afterwards from `GET /api/v1/logs`; verify: results with date and device in the rows, and findings 46 and 54 in `docs/FINDINGS.md` marked fixed or with what is still open

## 5. Integration

- [ ] 5.1 `openspec validate display-off-reachability`; verify: exit 0 without warnings

## Workflow follow-up

- Review, merge, then archive the change after the device rows of 4.2 (`/opsx:archive`).
