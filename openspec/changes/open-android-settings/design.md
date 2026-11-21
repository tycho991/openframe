# Design

## Context

`DisplayActivity.openDeviceMenu()` builds the device menu as a list of label/action pairs, opened by a 3-second press
in a corner. Two entries already start system screens (device administration, "Modify system settings"); before each
they call `health.plannedExit(reason, ADMIN_SCREEN_VALID_MS)` (10 minutes). See proposal.md for why.

What the code on main (0.10.0) does today, and what this design relies on:

- **The Frameo's firmware** ends openframe whenever another app's activity starts, through Android's error path
  (`Killing … user request after error`), and does so again on every subpage of the settings. Android then treats the
  process as bad, so the watchdog alarm cannot always start it (`FrameService.scheduleWatchdog`). Device tests put the
  Frameo into safe mode after opening a few settings pages. The Lenovo (Android 5.0.1), the emulators and newer
  devices keep openframe's process alive with the settings in front.
- **A planned record covers one end only.** `RunJournal.onProcessStart` writes a new `Stored` without `planned`, so a
  second end under the same system screen is `DIED`, an early end; the third puts the frame into safe mode.
- **The record is never withdrawn** when the process survives. For 10 minutes a real Java crash then counts as planned,
  because `classify` checks `planned` before `javaCrash`.
- **Unplanned ends bring the display back.** After a `DIED` end `FrameApp.start` schedules the display after 5 s
  (`restartDelayAfter`); after a Java crash `CrashHandler` does the same, unconditionally. Either would pull the
  slideshow over the settings.
- **The display under another app.** `FLAG_KEEP_SCREEN_ON` is only on openframe's window; `AndroidDisplayPower.turnOn`
  holds the screen for 5 s. A screen-off by the system's timeout while `applied == true` is recorded by
  `DisplayController.screenChanged` as "switched off by hand" until the next switching time. The installation dialog
  already solves the same problem with `DisplayController.hold`/`release` and `DisplayPower.keepOn`.
- **Package visibility.** targetSdk is 36 and the manifest has no `<queries>`; from API 30 `resolveActivity` may return
  `null` for another package's activity.
- **HOME.** `BootReceiver.logHome` warns when openframe is not the HOME app, called only at core start. An app cannot
  make itself the HOME app (`addPreferredActivity` is signature-protected).
- **No device detection exists.** `PlatformInfo` offers `model`, `androidVersion` and app data; nothing in `:core` or
  `:android` tells firmware apart.

## Goals / Non-Goals

**Goals:**
- One entry that reaches every part of Android's settings on devices that keep openframe alive with another activity
  in front, with no change to the API or the web UI.
- The entry is never offered on a device whose firmware ends openframe when another activity starts (owner decision,
  19 Nov 2026), from the first menu opening on, not only after a first end.
- While a system screen from the device menu is in front: process ends without a Java crash are not counted however
  many there are, no slideshow is pulled over the screen, and the display does not go dark under it at once.
- Real Java crashes keep counting, and a planned record does not outlive the screen.

**Non-Goals:**
- Making Android's settings usable on the Frameo. The owner excluded it, so this change drops the review findings that
  concern only that case: the 40-second return of the server after several firmware ends in a row (the spec keeps
  `Server independent of the display › App ended by the firmware` for one end), the process marked bad after repeated
  firmware ends so that the watchdog cannot start it, and the HOME preference that Android's error path may clear for a
  crashing third-party HOME app. Decision 3 still keeps a device that the firmware check misses out of safe mode, but
  the spec promises no server return time there.
- Changing the device administration and brightness entries beyond decisions 3 to 5: they stay on the Frameo.
- Direct entries for single screens (Wi-Fi, date and time); the start page reaches them.
- Bringing the slideshow back by a timer, or a PIN for the entry (the owner's choice).
- Opening the settings from the web UI or the API.
- Pointing `app-update` › Installation not allowed to the new entry: the entry is not on every device.
- Restoring a lost HOME preference: not possible for an app (see Context).

## Decisions

### 1. Telling apart a device whose firmware ends the app

The entry is hidden when either of two signals says so; `FrameCore.androidSettingsOffered` combines them and the menu
reads it each time it opens.

**(a) Firmware check, before any use.** A new `PlatformInfo.otherActivityEndsApp` (default `false`, settable in
`FakePlatform`). `AndroidPlatformInfo` sets it when the package manager knows `net.frameo.frame`, the frame app of the
Frameo firmware, also when it is disabled or uninstalled for the user: `getPackageInfo` with `GET_UNINSTALLED_PACKAGES`
(`MATCH_UNINSTALLED_PACKAGES` from API 24), read once per process. A frame that runs openframe as its kiosk usually has
that app disabled, not removed from the system image, so the check must not require it to be enabled. The manifest
declares `<queries><package android:name="net.frameo.frame"/></queries>` for Frameo firmware on API 30 and newer.
The implementation also passes `MATCH_DISABLED_COMPONENTS` (`GET_DISABLED_COMPONENTS` below API 24), so that a disabled
frame app is found on every Android version, whatever the package manager does with disabled packages.

**(b) Learned signal, for firmware the check does not know.** The run journal sets `systemScreenEndsApp` at a start
that finds a valid system-screen record (decision 3), no Java crash, and comes no later than
`SYSTEM_SCREEN_END_WINDOW_MS` (60 s) after the record was made or renewed. The journal cannot tell when the previous
run ended (`lastElapsedMs` is written once a minute), only when the next one started. The firmware ends the app right
after the other activity starts, and the watchdog brings the process back within about 20 s, so the window holds that
end and little else. The flag is kept
across starts, device restarts included, and cleared by a new installation or a lost `runs.json`. `HealthState` carries
it; the device administration and brightness entries feed it as well.

*Why both:* (b) alone would offer the entry on the Frameo until a first firmware end, and the journal has no history of
earlier ends; that breaks the owner's rule on the first use after the update. (a) alone depends on one package name.

*Why a new installation clears (b):* a false positive (someone force-stops openframe in the app info of the settings
within a minute and starts it again at once) would otherwise hide the entry for good. On the Frameo, (a) still hides
it after an update; on a device known only through (b) the first use after an update costs one more planned end.

*Alternatives rejected:*
- `Build.MODEL == "PFF_1024"`: covers one model; the Frameo firmware runs on frames with other model names.
- An API level cut: the Lenovo (API 21) keeps the process, the Frameo (API 23) does not; no level separates them.
- `ActivityManager.getHistoricalProcessExitReasons`: API 30 and newer only, the Frameo is API 23.
- A probe that starts a harmless foreign activity to see whether the firmware reacts: that is the harm itself.

### 2. `Settings.ACTION_SETTINGS` with a cleared task, shown only when it resolves

The entry starts `Intent(Settings.ACTION_SETTINGS)` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`
(API 11): the settings run in their own task, a task left on a subpage is cleared so the start page appears each time,
and Back on the start page ends that task and returns to openframe's task behind it. The menu shows the entry only when
`resolveActivity(intent, MATCH_DEFAULT_ONLY)` is not `null` and decision 1 allows it, checked each time the menu opens.
For API 30 and newer the manifest declares `<queries><intent><action android:name="android.settings.SETTINGS"/>
</intent></queries>`, so the check sees the Settings app. An `ActivityNotFoundException` between the check and the start
withdraws the record and the hold of decisions 3 and 5 and only logs.

*Alternative:* always show the entry and show a notice when nothing resolves. Rejected: an entry that never works on a
device is noise.

### 3. A system-screen record that lasts until the slideshow is back

All three entries that open a system screen call a new `FrameCore.systemScreenOpened(reason)` before `startActivity`,
instead of `health.plannedExit` directly. It records a planned end in a slot of its own in `runs.json`, next to the
existing `planned` slot of updates and restarts, valid for `SYSTEM_SCREEN_VALID_MS` (10 minutes, today's
`ADMIN_SCREEN_VALID_MS` moved to `:core`), and starts the hold of decision 5. `plannedExit` and `cancelPlannedExit`
stay as they are: `UpdateManager.endInstall` calls `cancelPlannedExit` when an installation dialog ends, and with a
shared slot it would withdraw a system-screen record too. The journal then:

- **carries** a valid system-screen record into the new run at each start of the same boot and installation, renewed
  for 10 minutes from that start, so any number of ends under the same screen count as planned; the end itself is
  still logged as a planned end. A `REBOOTED` or `REPLACED` start drops it, and so does a start after its validity;
- **lets a Java crash win** over a system-screen record in `classify`: no firmware end is a Java crash, so it is
  `CRASHED` and early. Other planned records (update, restart) keep today's order;
- **withdraws** the record when the slideshow is back, through a new `Health.cancelSystemScreen()` that leaves the
  `planned` slot alone. `DisplayActivity.onResume` calls it through `FrameApp.health`, not through the bound core: an
  activity created afresh, as after a restart under the screen, gets `core` only in `onServiceConnected` after
  `bindService` in `onStart`, so its first `onResume` sees `core == null`. Waiting for the core there would leave the
  record renewed at every start and `CrashHandler` skipping the display restart after a real crash. Releasing the hold
  and the HOME check (decisions 5 and 6) need the core: `onResume` does them when the core is bound, otherwise `attach`
  does them once it arrives. `FrameCore.systemScreenClosed()` bundles the three for the bound case.

*Alternative:* re-arm the record only at the start after a planned end. Rejected: the same thing with a rule that is
harder to state; the renewal at each start is what keeps the second and third end planned.

*Bound:* the record lives only while process starts keep renewing it. Settings left open without an end expire after
10 minutes; an end after that is unexplained again, and its display restart (decision 4 no longer applies) brings the
slideshow back.

### 4. No display over a system screen

`CrashHandler` skips `scheduleRestart` while a valid system-screen record exists (it reads the journal, which it already
holds); the server comes back through `START_STICKY` and, below API 31, the watchdog. `FrameApp.restartDelayAfter`
needs no change: under a carried record an end without a Java crash is `PLANNED`, never `DIED`. The watchdog starts
only the service, never the display. A display start by `BootReceiver` after an update below API 29 is not suppressed:
an update is a new installation, and the update path owns that screen.

### 5. Display hold under a system screen

`systemScreenOpened` calls `display.hold(now + 10 minutes)`, the mechanism of the installation dialog: `keepOn` holds
the screen against the system's timeout, also in an off period, and a screen-off meanwhile is no manual off.
`systemScreenClosed` calls `display.release()`. The log line of the hold names its reason instead of always "the
installation dialog": `DisplayController.hold` takes it, the installation dialog passes "the installation dialog" and a
system screen "a system screen (<reason>)"; the line for a screen-off during the hold names it as well. After the 10 minutes the device's own screen timeout applies with the settings still in front; a
screen-off then counts as switched off by hand until the next switching time, and a scheduled "on" wakes only for
`turnOn`'s 5 s. Touches in the settings keep the screen on through the system's own timeout. The hold is not restored
in a process started again under the screen (only on firmware that decision 1 did not recognize).

*Alternative:* `keepOn` for as long as the settings are in front. Rejected: nothing reports the settings closing
except the display coming back, so a forgotten settings page would keep the screen lit through every off period.

### 6. Way back and the HOME app

openframe does nothing while its display is in the background (the owner's choice). Home leads to the slideshow while
openframe is the HOME app. Back on the start page ends the settings task; Android returns to the task behind it, which
is openframe's display, also when the HOME preference changed meanwhile. When the display resumes after a
system-screen record, `DisplayActivity` calls `BootReceiver.logHome`, so a HOME app changed in the settings, or lost
after an update as known on one device, shows in the log at once and not only at the next core start. The settings
themselves offer the way to choose openframe again.

### 7. Position and label

The entry stands after the brightness entry, or after device administration where there is no brightness entry (up to
Android 5.1 the permission needs no screen), and before "Unpair all devices": the system screens stay together. Label
"Android settings" / "Android-Einstellungen". Robolectric offers no SDK below 23, where the brightness entry is always
present, so the position after device administration without a brightness entry is checked on the API 21 emulator
(tasks 3.2 and 3.3) instead of in a unit test.

## Risks / Trade-offs

- [The firmware check misses a Frameo variant without `net.frameo.frame`, or another firmware with the same
  behaviour] → The learned signal hides the entry after the first end; that end and any further ones under the screen
  count as planned, so no safe mode. Below API 31 the watchdog brings the server back.
- [False positive of the learned signal after a force-stop within a minute] → Hidden until the next installation. A
  restart of Android's system process within a minute of the opening ends openframe in the same way and teaches it too;
  it happened on the API 37 emulator (see the emulator note below).
- [A native crash or a kill by the system while a system screen is in front counts as planned] → Accepted: the record
  ends when the slideshow is back, and the decoder, where native crashes happen, runs in its own process.
- [Someone changes a setting openframe relies on (HOME app, Wi-Fi, screen timeout)] → The same risk as today's adb
  access; a changed HOME app is logged on return (decision 6). The 3-second corner press stays the barrier.
- [Settings left open for hours] → The settings stay in front and the screen follows the system's timeout; the
  schedule switches off as usual through device administration. A timer can follow as its own change if missed.
- [The system-screen hold and the installation dialog's hold share `DisplayController`'s single `holdUntil`] → The end
  of either ends both: an installation dialog closing during a system-screen hold releases it, and the reverse. Low
  priority, since both are opened from the frame and rarely overlap; a second hold slot can follow if it shows.
- [Opening the settings leaves the kiosk for anyone near the frame] → Accepted by the owner; the device menu already
  allows unpairing all devices.

## Migration Plan

None for data: `runs.json` gains optional fields with defaults, and `StoreJson` ignores unknown keys, so an older APK
reads a newer file. Rollback is an older APK.

## Emulator note (tasks 3.1 to 3.3)

On the API 37 emulator image with emulator 37.2.12, every window change out of Android's settings (a subpage, the Home
button) aborted `system_server` with the graphics assertion `!rcEnc->featureInfo()->hasReadColorBufferDma` (also
`screencap`), with each of the GPU modes `swiftshader_indirect`, `swangle_indirect` and `guest`. Task 3.1 was therefore
split: on API 37 the entry (the `<queries>`), the start page with `GET /info` answering, the settings lit for 5 minutes
with a 30-second screen timeout and the settings in front 30 s after a Java crash, with the server back after 2 s; the
subpage, Home, the start page again and Back on the API 21 emulator. The debug crash broadcast reaches the app on API 21
only without `-p`.

