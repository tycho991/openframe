# Tasks

## 1. Display schedule in `:core` (KDoc and tests, no production behaviour change)

- [ ] 1.1 DST per design decision 6: correct the class KDoc of `DisplaySchedule` (a time in the spring gap moves
  forward by the gap's length, not to the gap's end, for the on, off and dim times) and the KDoc of `onPeriod` (`null`
  when the on time lies in the gap and moves to or past the off time; the day then has no on period); new test in
  `DisplayScheduleTest` next to "On time in the gap of the spring DST change": weekend window 02:30 to 03:15, default
  weekdays, `periodAt` at 2026-03-29T03:05 Berlin gives `Period(false, 2026-03-28T03:15, 2026-03-30T07:00)` (Berlin);
  new test in `DimScheduleTest`: weekend window 01:00 to 03:20 with dim time 02:30 has no dim period on 2026-03-29
  (`dimAt` at 03:10 and 03:19 Berlin is `null`) while `periodAt` still ends that day's on period at 03:20; rename "Dim
  time in the DST gap moves to after the gap" to say it moves by the gap's length. Verify:
  `./gradlew :core:test --tests '*DisplayScheduleTest*' --tests '*DimScheduleTest*'` is green, all earlier cases
  unchanged (`display-schedule` › Periods of the schedule, › Dim periods)
- [ ] 1.2 Android's order per design decision 8: `FakeDisplayPower.deactivateLikeAndroid(deliver: () -> Unit)` (event
  while `available` is still true, then `deliver()`, then `available = false` without a second event); "Deactivated at
  night" calls it with `scope::runCurrent`, asserts the display is still off right after it, on after `minutes(1)`,
  and keeps its state, `minutes(60)` and switch-call checks; "A display off by hand stays off when device
  administration ends" calls it and asserts the display is still off after `minutes(1)`; `setAvailable` stays for the
  other callers; KDoc at `DisplayController.TICK_MS` (the tick also catches the end of device administration; deadlines
  instead of the tick need a delayed recheck after an availability event). Verify: `DisplayControllerTest` is green;
  two counter-checks run locally and not committed: with `TICK_MS = 120_000` "Deactivated at night" fails, and with
  `power.setAvailable(false); scope.runCurrent()` in place of `deactivateLikeAndroid` its "still off" assertion
  fails (`display-schedule` ›
  Losing device administration › Deactivated at night)
- [ ] 1.3 KDoc of `NetworkLink` in `Platform.kt`: in `:android` the network is a Wi-Fi or Ethernet connection, not
  mobile data or a VPN (design decision 1). Verify: `./gradlew :core:check` ends with exit 0, `checkNoAndroidImports`
  and the API 21 check included

## 2. Only the LAN counts as the network in `:android`

- [ ] 2.1 Move `connectionKind(capabilities)` from a private member of `AndroidPlatform` to an internal top-level
  function in `AndroidPlatform.kt` (design decision 2). Verify: the existing address tests in `:android` and `:core`
  stay green unchanged
- [ ] 2.2 `AndroidNetworkLink` per design decisions 1 and 2: internal `lanRequest()` (Wi-Fi or Ethernet transport,
  `NET_CAPABILITY_NOT_VPN` explicit, no `INTERNET`); `register(connectivity, log)` that unregisters an earlier
  callback, clears the set, registers a freshly created callback and then applies the snapshot only while no callback
  of this registration has arrived (listener only on a change); every callback sets `connected =
  networks.isNotEmpty()` and logs "LAN network available" and "LAN network lost" only when the set changes;
  `unregister(connectivity)` sets the field to `null` and does nothing when it is `null`; the snapshot from
  `allNetworks` with `connectionKind` `WIFI`/`ETHERNET` (`null` capabilities not counted) and
  `getNetworkInfo(network)?.isConnected`, falling back to `activeNetworkInfo` with `TYPE_WIFI`/`TYPE_ETHERNET`, also
  used in the constructor in place of today's start value; KDoc of the class and of `connected` corrected. Verify: new Robolectric tests in
  `AndroidNetworkLinkTest` are green: at SDK 28 the request has `TRANSPORT_WIFI` and `TRANSPORT_ETHERNET`, not
  `TRANSPORT_CELLULAR`, has `NOT_VPN` and not `INTERNET`; the snapshot is false in Robolectric's default (Wi-Fi
  disconnected, mobile data connected and active; true before this change), true with a connected Wi-Fi network added
  through `shadowOf(connectivityManager)`, false with only a VPN; these snapshot cases also at SDK 23, and at 21 if
  Robolectric 4.17 loads it; `register` leaves exactly one callback in `shadowOf(cm).networkCallbacks`, a second
  `register` replaces it with a different callback object, `unregister` removes it, and "unregister twice does not
  throw" (also at SDK 28, where a second unregister of the same callback would); a callback delivered before the
  snapshot is applied wins over a snapshot that says otherwise; the existing test "A lost and a regained network reach
  the listener" drives the registered callback instead of `link.available`/`link.lost`, with the same assertions;
  `onAvailable(wifi)` then `onLost(wifi)` through the registered
  callback give `connected == false` with one listener event and both log lines; after `unregister` and `register`
  with an empty snapshot a network reported before is gone from the set (`display-schedule` › Asking for the network
  while the display is off › Mobile data stays on, › Mobile data only)
- [ ] 2.3 Rebind per design decision 3: a small internal class for "new network, not one reported again", one instance
  per process in `FrameApp`; `FrameService.watchNetwork` keeps the unfiltered callback for the rebind and its
  "Network available/lost" lines through that class, no longer calls `link.available`/`link.lost`, and calls
  `link.register(connectivity) { core.log.i(TAG, it) }`; `onDestroy` unregisters both callbacks; the KDoc of
  `watchNetwork` says which callback does what. Verify: the test "A network reported available again without a loss
  is not new" passes against the new class, without its last line `assertTrue(link.connected)`, which belongs to the
  link; a Robolectric test with a pinned `@Config(sdk = [28])` that installs a light core through
  `FrameApp.coreFactory` (`TestCore`, since the callbacks log through `core.log`) creates and destroys `FrameService`
  twice and finds exactly two callbacks registered while it runs and none after `onDestroy` (`kiosk-host` › Network is optional
  › Same network reported again)

## 3. Android 16 orientation opt-out

- [ ] 3.1 Per design decision 5: `<property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"
  android:value="true"/>` in the `<activity>` element of `DisplayActivity` with a comment naming the targetSdk 37 end;
  a comment at `targetSdk = 36` in `android/build.gradle.kts` pointing to the guard test. Verify: two new tests in
  `ManifestTest` on `mergedManifest()` are green: the property with value `true` on the `DisplayActivity` element, and
  `<uses-sdk>` `targetSdkVersion` at most 36 with a failure message naming the render-size work; counter-checks run
  locally and not committed: without the property the first fails, with targetSdk 37 the second fails
  (`slideshow-display` › Orientation of the frame › Switch to portrait)

## 4. Pairing rule in one place

- [ ] 4.1 `PairingShown.kt` per design decision 9: `pairingShownOnFrame`, `pairingInEmptyState` and
  `PairingShownSync`. Verify: `PairingShownTest` (JUnit) is green: the truth tables of both functions over clients,
  hint, empty state and local-only mode; the invariant over all combinations that `pairingShownOnFrame` is true
  whenever the empty state or the hint shows code and QR code, and always false in local-only mode; `PairingShownSync`
  sends the first value, not a repeat, and the next `true` after a `false`
- [ ] 4.2 `DisplayActivity.syncPairingShown()` replaces the three copies (pairing collector, `render`,
  `setHintVisible`), also runs from the collector that combines `localOnly` with the server state, `onStop` sends
  `false` through the sync, `attach` makes a new sync, and `updateEmptyPairing` uses `pairingInEmptyState`. Verify: a
  Robolectric test next to `UpdateOnDisplayTest` with `TestCore` is green: an empty library without paired devices
  has a code in `core.pairing.display`; after `setLocalOnly(true)` it has neither code nor URL and the empty state
  shows none (before this change the code stayed); after `setLocalOnly(false)` a code again (`admin-access` › Code
  only while shown on the frame › Local-only mode)

## 5. Checks

- [ ] 5.1 `./gradlew check` (formatting, tests, API 21 check, Android Lint with `NewApi` as an error). Verify: the
  command ends with exit 0
- [ ] 5.2 `openspec validate android-platform-fixes`. Verify: exit 0 and no warnings

## 6. Emulator (debug build, see the emulator note in design.md)

- [ ] 6.1 API 37 emulator, end to end for the network: check that it has a virtual Wi-Fi next to the cellular `eth0`;
  activate device administration there, set a schedule that puts the display off now, then turn its Wi-Fi off with
  `svc wifi disable` while the display is off; after 12 minutes turn it back on. First with the 0.13.0 release build,
  then with the debug build of this change, switched with `adb install -r` (same certificate, data kept). Verify: with 0.13.0 there is no "Dark wake for the network" within the
  12 minutes (the finding reproduced; at most one "Network lost while the display is off" if mobile data came up only
  after the loss); with this build the log has "LAN network available" right after the start, "LAN network
  lost" and "Network lost while the display is off: asking for a reconnect" within a minute of the Wi-Fi going off,
  "Dark wake for the network" after ten minutes, and after `svc wifi enable` "LAN network available", the dark wake
  ended and exactly one "Network available" with one rebind; `GET /info` answers afterwards (`display-schedule` › Asking
  for the network while the display is off › Mobile data stays on, › Network back; › Dark wake for the network › Still
  no network after ten minutes)
- [ ] 6.2 API 21 emulator (no Wi-Fi radio, mobile data only), display on: start the debug build and read the log.
  Verify: "Network available" appears for the mobile data network and the server answers through `adb forward`, but no
  "LAN network available" line appears (`display-schedule` › Asking for the network while the display is off › Mobile
  data only)
- [ ] 6.3 Emulator with a tablet display (smallest width of 600 dp or more, e.g. 1920×1200 at 240 dpi), the device
  held in landscape, first on an API 36 image (what the opt-out property is defined for), then on API 37: switch the
  orientation setting to `portrait` and back, first with 0.13.0, then with this build (`adb install -r`, same
  certificate, data kept). Verify: on API 36 screenshots show 0.13.0 staying in landscape with the portrait setting
  (the finding reproduced) and this build turning to portrait and back without a restart; the API 37 result is
  recorded, and if API 37 ignores the opt-out for targetSdk 36 the main session enters a finding in the private
  findings list instead of failing this task (`slideshow-display` › Orientation of the frame › Switch to portrait)
- [ ] 6.4 API 37 emulator, pairing smoke test: an empty frame without paired devices, the hint opened, then a device
  paired, then local-only mode switched on in the device menu with the library emptied and all pairings revoked.
  Verify: screenshots show code and QR code in the empty state, the code in the hint, neither after pairing, and
  neither in local-only mode (`admin-access` › Code only while shown on the frame › Local-only mode)

## 7. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 7.1 The main session adds rows to the private device checklist, each with its spec reference: right after the
  update on the Lenovo (Android 5.0.1) and the Frameo (Android 6.0.1), "LAN network available" in `GET /api/v1/logs`
  within seconds (› Asking for the network while the display is off); one night with Wi-Fi up without "Network lost
  while the display is off" and without a dark wake; the orientation switch on the Frameo as a regression check (›
  Orientation of the frame › Switch to portrait); optional while the Android 16 tablet is paused: Wi-Fi dropped at
  night with mobile data on (› Mobile data stays on) and portrait on that tablet; the open row for `display-schedule` ›
  Losing device administration › Deactivated at night stays and may be run with this release, only with the owner's
  consent and with device administration activated again afterwards. It also adds two entries to the private findings
  list: "mobile data masked a Wi-Fi loss (follow-up to finding 46)" and "Android 16 opt-out expires with targetSdk 37;
  render-size work deferred". Verify: the rows and both findings exist with their references
- [ ] 7.2 After the release, update through the updater with the owner's consent, the Frameo first (or the Lenovo
  watched closely), the second frame after one night without anything unusual, and run the rows of 7.1. Verify:
  results are entered, anything that differs gets a finding in `../openframe-private/FINDINGS.md`, and the optional
  rows are marked as waiting for the tablet

## Workflow follow-up

- Archive the change after the device rows of 7.2 are done; the optional tablet rows do not block it.
