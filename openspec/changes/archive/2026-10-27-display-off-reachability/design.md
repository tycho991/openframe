# Design

## Context

`DisplayController` (in `:core`) owns the display state in one coroutine: it re-evaluates every 30 s and on every event, `DisplayPolicy.decide` is the pure decision, and `DisplayPower` (implemented by `AndroidDisplayPower`) switches with `lockNow()` and a `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`. Screen events are matched against the desired state: a screen that goes on while the frame wants it off counts as waking. `FrameCore` lets the slideshow rest while `display.state` reports the display off. `FrameService` already holds a partial wake lock and a Wi-Fi lock (`WIFI_MODE_FULL_HIGH_PERF` below API 29) and registers a `NetworkCallback` that only logs "Network lost/available" and rebinds the server. The Wi-Fi lock did not prevent finding 46. See proposal.md for the findings and the specs for the required behavior.

## Goals / Non-Goals

**Goals:**
- The decisions (when to ask for a reconnect, when to wake dark, whether the clock counts) stay in `:core`, testable with virtual time like the rest of `DisplayController`.
- The dark wake is a state of its own, never confused with waking by the power button.

**Non-Goals:**
- Changing how Android connects while the display is on.
- Any other light source at night than the dark wake: no notification LED, no hint text.

## Decisions

### 1. A platform interface for the network: `NetworkLink`

`:core` gets `NetworkLink` in `core/.../platform/`: `connected: Boolean`, `requestReconnect()` and a listener for changes. `:android` implements it in `AndroidNetworkLink`: `connected` from the `NetworkCallback` that `FrameService` already registers (a network with internet or Wi-Fi transport counts), `requestReconnect()` calls `WifiManager.reconnect()` and `startScan()` (permission `CHANGE_WIFI_STATE`, a normal permission). The JVM fake records the requests and lets tests drop and restore the network. A device without it (`NetworkLink.None`) is always connected, so nothing changes for it.

*Alternative:* doing it all inside `FrameService`. Rejected: the timing (one minute, ten minutes, the dark wake's minute) would then be untestable without a device, and the controller is the one that knows whether the display is off by the frame.

### 2. Reconnect and dark wake in `DisplayController`, not in the policy

The policy decides what the display should show; the network handling is a timer on top. The controller keeps `lostAt` (monotonic time the network was lost while the display was off by the schedule or the manual switch, `null` otherwise) and `darkUntil`. On each evaluation:
- Off by the frame and not connected: `requestReconnect()` at most once a minute.
- `lostAt` is ten minutes old, or the last dark wake ended ten minutes ago: start a dark wake (`darkUntil` = now + 1 min), switch the display on.
- Connected again, or `darkUntil` passed: end the dark wake and switch off again (the policy still says off).
The evaluation tick stays 30 s; reconnect and dark wake times are checked on it, so they hold within half a minute, which the specs allow ("within a minute").

*Alternative:* a separate coroutine for the network. Rejected: the controller must already tell the dark wake's screen-on event from waking, which is only safe inside the loop that owns the state.

### 3. The dark wake is the frame's own: screen events and the API

During a dark wake `applied` stays "off" in the sense of the decision, and a flag `dark` marks that the screen is on by the frame. A screen-on event while `dark` is set is the frame's own and is not waking. `DisplayState` keeps `on = false` with the decision's cause while `dark` is set, so `GET /display` reports off and the slideshow keeps resting (it follows `display.state`). A touch (`userActivity`) during a dark wake ends it and starts a normal waking period (`wakeUntil`), as the power button would.

*Alternative:* reporting `on: true` with a new cause `network`. Rejected: it changes the API contract and the web UI for a minute of black that nobody sees as "on".

### 4. Black at the lowest brightness in `DisplayActivity`

`DisplayController` exposes `dark: StateFlow<Boolean>`. While it is true, `DisplayActivity` shows a black view above the slideshow and sets the window's `screenBrightness` to the lowest value (`0.01f`; `0f` means "off" on some devices and may be ignored or treated as default). It restores the previous value when the flag clears. The backlight is still on, as the user accepted: the point is to be as dark as an app can make a lit display.

### 5. Clock plausibility as an input of the policy

`DisplayPolicy.decide` gets the rule: if `now` is before `2026-01-01T00:00:00Z` (`CLOCK_FLOOR`), the decision is "decide nothing" (`on = null`, cause `NONE`), as without an active schedule. The controller then switches a display that it had switched off back on, as it does today when the schedule is switched off. When the clock crosses the floor, the controller treats it like a change of the schedule and calls `grace()`, so a frame whose clock is set at night goes off after the minutes after waking.

A fixed date rather than the build date: it needs no build setup, is the same in tests and on devices, and every real clock of a frame running this app is later. It has to be moved only if someone runs this build with a clock deliberately set into the past, which is not a supported setup.

*Alternative:* the build date from `BuildConfig`. Rejected: `:core` has no build config, and passing it in through `PlatformInfo` adds a dependency for no gain.

### 6. Logging

"Network lost while the display is off: asking for a reconnect", "Dark wake for the network", "Dark wake ended (network back | after a minute)", "Clock not set (…): schedule paused" and "Clock set: schedule applies" go to the app log at info level, so that a night can be read afterwards from `GET /api/v1/logs`, as was done for finding 54.

## Risks / Trade-offs

- [`WifiManager.reconnect()` is ignored for apps from Android 10 on, and `startScan()` is throttled] → the dark wake is the fallback that worked on both frames; the Galaxy Tab S7 (Android 16) is paused, its row stays open.
- [Whether Android 5 reconnects on a request with the screen off is unproven] → the device test drops the Wi-Fi at night on the Lenovo and the Frameo and records whether the reconnect alone suffices.
- [A dark wake is visible as a faint glow at night] → accepted by the user; at most one minute in ten, only while the network is missing.
- [A frame whose Wi-Fi is off for good (router away) wakes dark every ten minutes all night] → accepted as the price of getting back on the network; the log shows it.
- [A clock before 2026 on a device that really is in 2025 or earlier] → not a supported setup; the frame then simply runs without schedule.

## Migration Plan

No data migration and no API change. The new permission `CHANGE_WIFI_STATE` is a normal permission, granted on install and update. Rolling back removes the reconnect, the dark wake and the clock rule; nothing stored changes.

## Open Questions

- The lowest window brightness that still lets the display wake on each device (some firmwares treat `0.01f` like their minimum, others may need a higher value); measured in the device test, changes neither specs nor tasks.
