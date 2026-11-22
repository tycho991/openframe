# Proposal

## Why

A review of the Android platform code before the public release found six issues that a re-check against main
after 0.13.0 confirmed. Only one has a real effect: the frame counts any network as "the network", mobile data
included, so on a device with a SIM a Wi-Fi loss at night is hidden by mobile data, and neither the reconnect requests
nor the dark wake of `display-schedule` start. The frame then stays unreachable from the LAN until the morning, which
is exactly what `display-off-reachability` was meant to prevent.

The other five are smaller and touch the same code: Android 16 ignores the frame's orientation on large screens for
apps with targetSdk 36; a KDoc about the DST gap says the opposite of what the code does; the `kiosk-host` spec
contradicts the dark wake; the display tests' fake reports the end of device administration in a different order than
Android; and the rule for "the frame shows pairing" is copied three times in `DisplayActivity` and ignores local-only
mode. One change ships them in one release.

## What Changes

- While the display is off, only a Wi-Fi or Ethernet connection SHALL count as the network the frame asks for and
  wakes the display dark for; mobile data, a VPN or another kind of connection SHALL NOT. A device with only mobile
  data is treated like a device without a network, as a Wi-Fi-only device without Wi-Fi is today.
- The server's rebind on a network change stays as it is: it still follows every network, mobile data included.
- The `kiosk-host` scenario "Off by the schedule" SHALL name the two cases in which the frame switches the display on
  by itself before the next on time: a dark wake while the network is missing, and the end of device administration.
- While local-only mode is on, the frame SHALL NOT create a pairing code for the empty state or the hint, since neither
  shows one then. The rule lives in one pure function with tests.
- The display activity opts out of Android 16's orientation and resizability restrictions on large screens through a
  manifest property. A test fails when targetSdk is raised to 37, where the opt-out ends, so that rendering for the
  real window size comes before that bump. targetSdk stays 36.
- The DST gap behaviour stays (an on or dim time in the gap moves by the gap's length); the KDoc is corrected and tests
  pin the case in which a day's on period or dim period drops out.
- `FakeDisplayPower` reports the end of device administration in Android's order, and the tests measure against the
  spec's minute instead of the controller's 30-second tick.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `display-schedule`: "Asking for the network while the display is off" says which connections count as the network,
  with scenarios for mobile data that stays on and for a device with mobile data only.
- `kiosk-host`: the scenario "Off by the schedule" of "Keep the display awake" names the dark wake and the end of
  device administration as exceptions.
- `admin-access`: "Code only while shown on the frame" excludes local-only mode, where neither the empty state nor the
  hint shows a code.

## Impact

- `:android`: `AndroidNetworkLink` (a callback of its own filtered to Wi-Fi and Ethernet, start value by transport,
  idempotent registration), `FrameService.watchNetwork` and `onDestroy` (the unfiltered callback only for the rebind),
  `AndroidPlatform.connectionKind` (shared as a top-level function), `DisplayActivity` (one `syncPairingShown`), a new
  `PairingShown.kt`, `AndroidManifest.xml` (a `<property>` on `DisplayActivity`), a comment at `targetSdk` in
  `android/build.gradle.kts`; Robolectric tests in `AndroidNetworkLinkTest`, `ManifestTest` and a new
  `PairingShownTest`.
- `:core`: KDoc only in `Platform.kt` (`NetworkLink`), `DisplaySchedule.kt` and at `DisplayController.TICK_MS`; tests
  in `DisplayScheduleTest`, `DimScheduleTest`, `DisplayControllerTest` and the test fake `FakeDisplayPower`.
- No API, web UI, stored data or dependency changes.
- Rows in the private device checklist; the Android 16 tablet rows stay optional while that device is paused.
