# Design

## Context

See proposal.md for the six issues. What the code on main (after 0.13.0) does today, and what this design relies on:

- **One callback for two jobs.** `FrameService.watchNetwork` registers a single `NetworkCallback` with
  `NetworkRequest.Builder().build()`, which has no transport filter (its default capabilities are `NOT_RESTRICTED`,
  `TRUSTED` and `NOT_VPN`), so mobile data, Bluetooth tethering and any other network match. `onAvailable` calls
  `AndroidNetworkLink.available(network)`, and only when that reports a network not seen yet does it log "Network
  available" and post the server rebind (`FrameCore.onNetworkChanged`, which also tells the sources and the updater).
  `onLost` logs "Network lost" and calls `link.lost`. The set of networks lives in `AndroidNetworkLink`, which
  `FrameApp` creates once per process, while `FrameService` is created again by `START_STICKY`.
- **`connected` counts any network.** `AndroidNetworkLink.connected` is `networks.isNotEmpty()`, and its start value
  is `activeNetworkInfo?.isConnected`, also without a transport. Its KDoc says "any network with internet, Wi-Fi or
  Ethernet"; decision 1 of `display-off-reachability` said "a network with internet or Wi-Fi transport counts". The
  `NetworkLink` KDoc in `Platform.kt` says "a network the admin interface can be reached on" and "In :android this is
  Wi-Fi". `DisplayController.keepReachable` clears `lostAt` whenever `network.connected` is true and ends a dark wake
  with "network back"; it is the only user of `NetworkLink`.
- **The kind of a network** is known already: `AndroidPlatform.connectionKind(capabilities)` (private) maps the
  transports to `ConnectionKind` with VPN first, then cellular, Wi-Fi, Ethernet, `OTHER`; the address shown on the
  frame uses it.
- **Orientation.** targetSdk is 36. `DisplayActivity` has `android:screenOrientation="sensorLandscape"` and sets
  `requestedOrientation` from the setting; Android 16 ignores both on displays with a smallest width of 600 dp or more
  for apps that target 36. Render sizes follow the setting, not the window (`FrameCore` passes
  `RenderSizes.screen(natural…, settings.orientation)`); only the layout follows the real orientation
  (`arrangeForOrientation`). The manifest already uses a `<property>` element (on `FrameService`), which Android below
  12 ignores. `ManifestTest` reads the merged manifest through its `mergedManifest()` helper
  (`isIncludeAndroidResources`). Robolectric is 4.17; the lowest SDK any test uses is 23.
- **DST.** `DisplaySchedule.onPeriod` and `dimAt` resolve local times with `ZonedDateTime.of`, which moves a time in
  the spring gap forward by the gap's length (02:30 becomes 03:30 in Europe/Berlin), as decided in `display-schedule`.
  `onPeriod` returns `null` when `end > start` does not hold, which its KDoc explains as "both times in the same DST
  gap"; with both times shifted that never happens. It happens when the on time lies in the gap and the off time
  less than a gap's length after it: 02:30 to 03:15 gives 03:30 to 03:15, and that day has no on period.
- **Losing device administration.** `AndroidDisplayPower.available` is `isAdminActive`, and `onDisabled` calls
  `availabilityChanged()`. Android sends the disable broadcast before it removes the admin, so the event is read while
  `available` is still true; the next 30-second tick (`DisplayController.TICK_MS`) sees the loss. This was observed on
  the API 37 emulator during `display-schedule`. `FakeDisplayPower.setAvailable` sets the value first and then fires
  the event, so `DisplayControllerTest` › "Deactivated at night" passes at the event, not at the tick.
- **Pairing shown.** `DisplayActivity` writes `hintVisible || (showingEmpty && noClients)` to
  `PairingManager.setShownOnFrame` from three places (the pairing collector, `render`, `setHintVisible`), and `false`
  in `onStop`. `updateEmptyPairing` and `updateHint` hide code and QR code in local-only mode; `setShownOnFrame` does
  not know that mode, so the core creates a fresh session every 5 minutes that nobody sees. Whether the screen is
  usable (on and not dark) is fed into `PairingManager.setScreenUsable` by `FrameCore`, not by the activity. The
  collector that combines `localOnly` with the server state calls `updateHint`/`updateEmpty`, never `setShownOnFrame`.

## Goals / Non-Goals

**Goals:**
- The display schedule's network handling sees the LAN only, with the decisions still in `:core` and `:core`
  unchanged apart from KDoc.
- The server rebinds exactly when it does today.
- Orientation keeps working on Android 16 tablets until targetSdk 37, and the bump cannot happen by accident.
- Every fix pinned by a test that fails without it.

**Non-Goals:**
- Rendering for the real window size, or rotating the content on large screens: deferred to the targetSdk 37 bump
  (decision 5).
- Changing the DST behaviour (owner decision of 22 Nov 2026).
- Changing the production timing of `DisplayController` (the tick stays 30 s).
- A session created by `POST /api/v1/pairing/code` in local-only mode: the request can only come from the device
  itself then, the frame shows the QR code alone, and it creates no code since nothing is shown on the frame.
- Any new API field: whether the frame has a LAN stays internal, as it is today.

## Decisions

### 1. Only Wi-Fi and Ethernet count, through a filtered callback of its own

This replaces decision 1 of `display-off-reachability` ("a network with internet or Wi-Fi transport counts"); the
archive stays as it is. `AndroidNetworkLink` registers its own `NetworkCallback` with an internal `lanRequest()`:
`NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).addTransportType(TRANSPORT_ETHERNET).build()`. Transports
in a request are alternatives, so either matches. The builder's defaults keep `NOT_VPN`, `NOT_RESTRICTED` and
`TRUSTED`; `INTERNET` is not asked for, so a Wi-Fi without an internet uplink still counts, since the LAN is what the
admin interface needs. Everything used is available from API 21. The callback's `onAvailable`/`onLost` update the
link's set of networks as today and write "LAN network available" and "LAN network lost" through the logger handed to
`register`, which `FrameService` points at `core.log`, so the lines are in `GET /api/v1/logs`. A repeated
`onAvailable` for a known network (Android 5 after a score change) logs nothing.

*Alternative:* keep the one unfiltered callback and filter in `onAvailable` with `getNetworkCapabilities`. Rejected:
below API 26 `onAvailable` is not guaranteed to be followed by `onCapabilitiesChanged`, and `getNetworkCapabilities`
can return `null` when it races with a loss, which would drop or keep a network by chance. The framework's matching
of a request has neither problem.

*Alternative:* `NET_CAPABILITY_INTERNET` instead of transports. Rejected: mobile data has it, which is the bug.

### 2. Idempotent registration with a fresh start value

`register(connectivity, log)` first unregisters a callback it registered before, clears the set of networks, sets
`connected` from a snapshot, and then registers the new callback; the listener hears of the snapshot only if it
changes `connected`. `unregister(connectivity)` removes the callback. The link lives as long as the process, the
service does not: without the clearing, a network lost while no callback was registered would stay in the set, and a
second registration would leave two callbacks (Android 11 and later limit an app to 100 requests).

The snapshot replaces today's `activeNetworkInfo?.isConnected`. It goes through `connectivity.allNetworks` and counts
a network whose `connectionKind(getNetworkCapabilities(network))` is `WIFI` or `ETHERNET` and whose
`getNetworkInfo(network)?.isConnected` is true; the second check is needed because `allNetworks` also lists networks
that are still coming up (Robolectric's default has a disconnected Wi-Fi). If that throws, the fallback is
`activeNetworkInfo` with type `TYPE_WIFI` or `TYPE_ETHERNET` and `isConnected`. A VPN on top of the Wi-Fi is a network
of kind `VPN` and does not count, but the Wi-Fi under it does. For this, `connectionKind` moves from a private member of
`AndroidPlatform` to an internal top-level function in the same file, shared by the address display and the link.

### 3. The server rebind stays on the unfiltered callback, with the same timing

`FrameService.watchNetwork` keeps its unfiltered callback for the rebind and its "Network available/lost" lines, and
no longer calls `link.available`/`link.lost`; it calls `link.register(connectivity) { core.log.i(TAG, it) }` instead,
and `onDestroy` unregisters both callbacks. The rebind still follows every network, mobile data included, because
`onNetworkChanged` also restarts the sources and the updater and drops cached addresses.

The check "new network, not one reported again" moves with it into a small internal class next to the link (add
reports a network not known yet, remove one that was known). Its instance stays per process in `FrameApp`, as the set
is today: a service restarted by `START_STICKY` gets `onAvailable` for the network it is on, and a set in the service
would take that for a new network and rebind, dropping every open connection, which today does not happen. A stale
entry there only matters if the same network id comes back, and Android gives a new connection a new id. The
existing test "A network reported available again without a loss is not new" moves to that class unchanged.

### 4. A device with only mobile data counts as never connected

With the filter, mobile data alone, or a connection of kind `OTHER` (Bluetooth or USB tethering), is no network for
the display schedule. While such a device's display is off by the frame, it asks for a reconnect every minute and wakes
the display dark every ten minutes, all night. That is what a Wi-Fi-only frame does today when it has no Wi-Fi at
all, so it adds no new state, and the admin interface is not reachable over mobile data anyway (`admin-access` ›
Reachability on the LAN, › Connections only on local addresses). No frame in use is such a device; the API 21 emulator
is (no Wi-Fi radio), which matters for emulator checks of the schedule, and that emulator freezes while its display
is off anyway.

*Alternative:* count mobile data on a device without Wi-Fi hardware. Rejected: it brings back a transport rule for a
case without users, and on such a device a dark wake cannot make anything worse than an unreachable frame.

### 5. Android 16: the opt-out property now, a guard before targetSdk 37

The `<activity>` element of `DisplayActivity`, not `<application>`, gets
`<property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" android:value="true"/>`, which
makes Android 16 keep honouring `screenOrientation` and `requestedOrientation` on large screens for an app with
targetSdk 36. Below Android 12 the element is ignored, as the one on `FrameService` already is on the frames. A comment
at `targetSdk = 36` in `android/build.gradle.kts` says the opt-out ends with 37 and points to the guard.

`ManifestTest` gets two tests on the merged manifest from its `mergedManifest()` helper: the property with value
`true` on the `DisplayActivity` element, and `targetSdkVersion` of `<uses-sdk>` at most 36. The second one fails with a
message saying that before targetSdk 37 the render size has to come from the real window instead of
`settings.orientation`, or the content has to be rotated on large screens. Nothing else would force that work:
Renovate does not change targetSdk, and Lint's `OldTargetApi` is a warning, not an error.

*Alternatives:* render for the real window size now (cross-module: the core would need the actual orientation from the
activity, while no frame in use is affected); lower targetSdk to 35 (undoes the targetSdk 36 work). Both rejected by
the owner on 22 Nov 2026.

### 6. DST: the behaviour stays, KDoc and tests say what it is

The class KDoc of `DisplaySchedule` says that a time in the spring gap moves forward by the gap's length, not to the
gap's end, and that this holds for the on, off and dim times. `onPeriod`'s KDoc says when it is `null`: the on time
lies in the gap and moves to or past the off time, and the day then has no on period. For the dim time the same shift
can put it at or after the end of the period, and `dimAt` then gives no dim period that day, which its existing check
`dimStart >= end` already does.

Tests: in `DisplayScheduleTest`, a weekend window 02:30 to 03:15 with the default weekdays gives, at
2026-03-29T03:05 in Europe/Berlin, `Period(false, 2026-03-28T03:15, 2026-03-30T07:00)` (Berlin times): Sunday's period
is missing, the off period runs from Saturday to Monday. In `DimScheduleTest`, a weekend window 01:00 to 03:20 with the
dim time 02:30 has no dim period on that Sunday (the dim time becomes 03:30, after the end), while the on period still
ends at 03:20; the existing test "Dim time in the DST gap moves to after the gap" is renamed to say "by the gap's
length".

### 7. `kiosk-host` names both cases in which the display comes on by itself

The scenario "Off by the schedule" names the dark wake while the network is missing and the end of device
administration, each with its `display-schedule` reference; see the delta. Both switch the display on without anyone
asking, so naming only the dark wake would leave the second contradiction. The installation dialog and a system screen
from the device menu also keep the display on, but someone starts them, so they are not "by itself".

### 8. `FakeDisplayPower` follows Android's order

`FakeDisplayPower.deactivateLikeAndroid(deliver: () -> Unit)` fires `availabilityChanged()` while `available` is still
true, runs `deliver`, and then sets `available = false` without a second event. The tests pass `scope::runCurrent`, so
the controller handles the event before the value changes, as on Android. A single synchronous method would not do
that: `trySend` only queues the event, and the controller would read `available == false` at the next `runCurrent`,
passing for the wrong reason.

"Deactivated at night" asserts right after the call that the display is still off (the stale event arrived and
changed nothing), and on after `minutes(1)`, the spec's deadline rather than `TICK_MS`; state and switch calls are
checked as today. "A display off by hand stays off when device administration ends" uses it as well and checks after
`minutes(1)` that the display is still off. `setAvailable` stays for activation, for the tests that start without
device administration, and for `TestServerMain`. A KDoc at `TICK_MS` says that the tick also catches the end of
device administration, and that replacing it by deadlines needs a delayed recheck after an availability event.

### 9. The pairing rule in one place

A new `PairingShown.kt` in `:android`, after the pattern of `Corners.kt`: `pairingShownOnFrame(localOnly, hintVisible,
showingEmpty, noClients) = !localOnly && (hintVisible || (showingEmpty && noClients))`, and `pairingInEmptyState(
localOnly, hintVisible, noClients)` for `updateEmptyPairing`. A small `PairingShownSync(set: (Boolean) -> Unit)` passes
a value on only when it differs from the last one, starting from `null`, so the first value of each attach always goes
out. `DisplayActivity.syncPairingShown()` replaces the three copies and also runs from the collector that combines
`localOnly` with the server state; `onStop` goes through the same sync with `false`; `attach` makes a new sync. The
usable screen stays where it is (`PairingManager.setScreenUsable`, fed by `FrameCore`), so the pure function only adds
`!localOnly`. As a side effect fewer calls from the main thread take the `PairingManager` lock.

## Risks / Trade-offs

- [The filtered callback does not match on API 21 or 23] → With a Wi-Fi at the start, `connected` stays at the
  snapshot's true and a real loss goes unseen (a silent return of finding 46); without one it stays false and the
  frame asks for the network all night. Mitigation: Robolectric at SDK 23 and 28 (21 if it loads), the "LAN network
  available" line checked on both frames right after the update, one frame first. It is no crash, so safe mode does
  not catch it; the updater does not depend on `NetworkLink`, so a fix can follow through it.
- [Robolectric does not dispatch network callbacks by matching] → The unit tests pin the request (transports, no
  cellular) and the snapshot; that Android keeps mobile data away from the filtered callback is the framework's part.
  The API 37 emulator, whose `eth0` is a cellular network next to its virtual Wi-Fi, is the end-to-end check.
- [A device with only mobile data wakes dark every ten minutes at night] → Accepted (decision 4); no frame in use is
  such a device.
- [Registration not idempotent] → Doubled callbacks or stale networks after a service restart. Covered by the
  register, re-register and unregister tests.
- [The rebind changes by accident] → The rebind's set stays per process and keeps today's test (decision 3); the
  emulator check of Wi-Fi off and on looks for exactly one "Network available" and rebind.
- [The Android 16 opt-out ends with targetSdk 37] → The guard test fails at the bump.
- [The opt-out is only checked on an emulator] → The Android 16 tablet is paused; an API 37 emulator with a tablet
  display (smallest width 600 dp or more) shows the difference between 0.13.0 and this build.
- [The night test needs a dropped Wi-Fi on a frame] → Not needed: the emulator reproduces it, and finding 46's night
  test is closed. On the frames only the log line and a night without a false dark wake are checked, both read-only.

## Migration Plan

No stored data changes, so no migration. The release after 0.13.0 goes out through the updater, with the owner's
consent for each frame: the Frameo first, since the Lenovo had unrelated timeouts recently, or the Lenovo watched
closely. Right after the update, `GET /api/v1/logs` has "LAN network available"; after one night with Wi-Fi up there
is no "Network lost while the display is off" and no dark wake. Rollback is a patch release through the updater; an
older APK by `adb install` only with the owner's consent.

## Emulator note (group 6)

The API 37 emulator's `eth0` is a cellular network; images from Android 11 on also have a virtual Wi-Fi
(`AndroidWifi` on `wlan0`). Task 6.1 checks that first (`adb shell cmd wifi status`, `dumpsys connectivity`); if the
image has no Wi-Fi, the end-to-end check cannot run there and the result says so. Wi-Fi goes off with
`adb shell svc wifi disable` (or `cmd wifi set-wifi-enabled disabled`) while the display is off by the schedule and back
with `svc wifi enable`; device administration is activated on the emulator only (`dpm set-active-admin`). Turning the
emulator's Wi-Fi off is no device change in the sense of the project rules. The API 21 emulator has no Wi-Fi radio and
freezes while its display is off, so it gives only the negative check with the display on.
