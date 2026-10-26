# Design

## Context

See proposal.md for the motivation. What is already in place shapes the approach:

- `FrameService` holds a `PARTIAL_WAKE_LOCK` and a Wi-Fi lock for as long as it runs, not only while the screen is on. The CPU stays awake when the display is off, and so do the core's coroutines. pictureframe needed `AlarmManager` and an extra wake lock for the off period. openframe needs neither.
- `DisplayActivity` sets `FLAG_KEEP_SCREEN_ON`. The power button switches the display off anyway, and so does `DevicePolicyManager.lockNow()`.
- `Clock` has only `now()` and no time zone. `TestClock` controls it in tests.
- `SourceScheduler`/`SyncSchedule` set the pattern for timing: pure arithmetic in an `object`, plus one coroutine that waits for a deadline or a wake signal (`Channel.CONFLATED`).
- Settings are validated field by field in `SettingsPatch`, with paths like `clock.position`.
- The devices are the Lenovo (API 21), the Frameo (API 23, starts with its clock in 2021, ends the app as soon as another activity starts) and the Galaxy Tab S7 (API 36). `targetSdk` is 36.
- pictureframe is a device administrator on the Lenovo and has a night schedule of its own.

## Goals / Non-Goals

**Goals:**

- The whole decision logic in `:core`, tested on the JVM with `TestClock` and a fake `DisplayPower`, including midnight, the weekend boundary, DST, clock jumps and the waking timer.
- `:android` stays thin: switch off, switch on, report screen events, availability and touches.
- Never fight the user. The frame switches only at the edges of its own decisions and does not hold a level. A display that someone switched off with the power button is not switched back on.

**Non-Goals:**

- Showing the slideshow over a lock screen. Like Autostart, this change assumes that no screen lock is set up.
- Exact alarms. One minute of accuracy is enough, and the CPU is awake anyway.
- Keeping the manual switch or a waking period across a restart.

## Decisions

### 1. Timing in the core, not with `AlarmManager`

A coroutine in `DisplayController` re-evaluates every 30 s, and at once on every event: a settings change, a screen event, a touch, an availability change, `PUT /display`. On each evaluation it reads the wall clock and the zone afresh. That alone covers a clock that is set late (Frameo), time zone changes and DST, with no `TIME_SET`/`TIMEZONE_CHANGED` receivers.

- *Alternative:* `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)` as in pictureframe. On API 31+ that needs `SCHEDULE_EXACT_ALARM`. It also has to be re-armed after boot, after a clock change and after a schedule change. And it cannot be tested in `:core`. The partial wake lock that is already held makes it unnecessary.
- *Doze:* on API 23+ the system ignores wake locks only when the device is not charging. A frame runs on its charger, so this does not apply. A frame on battery could switch late, and that is accepted (see Risks).

### 2. Pure schedule arithmetic

`DisplaySchedule` is an `object` with pure functions:

- `periodAt(at: ZonedDateTime, settings): Period`, where `Period(on: Boolean, start: Instant, end: Instant)`.
- `nextChange(...)`.

The rules:

- The period of a day starts at the on time of that day's window: Monday to Friday use `weekdays`, Saturday and Sunday use `weekend`. It ends at the off time. If the off time is not after the on time, it ends on the following day.
- Equal times are the exception: the period covers the whole calendar day, 00:00 to 00:00, and does not reach into the next day.
- To find the state at a moment, the arithmetic looks at the period of today and the one of yesterday. Yesterday's period counts when it crosses midnight.
- Local times are resolved with `ZonedDateTime.of(date, time, zone)`. A time in the DST gap therefore moves to after the gap, and a time in the overlap takes the earlier offset.

The period's identity, its `start`, is what the manual switch is tied to (decision 4).

### 3. A time zone and a monotonic time on `Clock`

`Clock` gains two members:

- `fun zone(): ZoneId = ZoneId.systemDefault()`. On Android, desugared `ZoneId.systemDefault()` follows `TimeZone.getDefault()`, which the system updates in the process when the zone changes.
- `fun monotonicMillis(): Long = System.nanoTime() / 1_000_000`, for the waking timer. Wall clock jumps must neither end it at once nor stretch it without end.

`TestClock` gets a settable zone and a monotonic time of its own, which `advance()` moves along.

### 4. State machine in `DisplayController`

The inputs:

- the schedule settings
- `available`: device administration is active
- the screen state `screenOn`, as the platform reports it
- `override: Override?`, holding the manual value and the period at the time it was set (`null` without an active schedule)
- `wakeUntil: Long?`, a monotonic time

The desired state is computed in this order:

1. Not `available`: the frame decides nothing (`cause = none`). If the display was off by the frame's own decision when availability ended, it is switched on once.
2. `override` set, and its period is still the current one (or there is no schedule): the override decides (`manual`).
3. Schedule active, and the period says off: if `wakeUntil` lies in the future, the display stays on (`wake`); otherwise it is off (`schedule`).
4. Schedule active, and the period says on: on (`schedule`).
5. No active schedule: the frame decides nothing (`none`).

The controller acts only when the desired state changes: it calls `power.turnOff()` or `power.turnOn()`. It does not keep correcting a level. Screen events from the platform are matched against the desired state:

- `SCREEN_ON` while the desired state is off counts as waking. A manual off ends, and during an off period of the schedule `wakeUntil = now + minutes` is set.
- `SCREEN_OFF` while the desired state is on counts as switching off by hand (the power button, or pictureframe's schedule). It becomes an override `off` until the next switching time, so the frame does not switch the display back on.
- Events that match the desired state are the frame's own and change nothing.

Further events:

- A touch (`userActivity()`, throttled to once per second) moves `wakeUntil` forward while it is set.
- At start, and when the schedule is switched on or changed, the controller checks whether the period says off while the screen is on. If so, it sets `wakeUntil` (the grace period) instead of switching off at once.

`GET /display` reports `screenOn` as `on`, together with `cause` and `nextChange`. `nextChange` is when the display actually changes next by itself: the end of the current period or of waking (`wakeUntil` converted to the wall clock), whichever comes first; where the state that follows is the same as the current one (a manual off by day runs into the night, waking lasts past the end of the off period), it is the end of the period after it; `null` when there is nothing to switch.

The decision logic is a pure function `DisplayPolicy.decide(inputs, now) -> Decision`, the same split as between `SyncSchedule` and `SourceScheduler`. The controller only collects the inputs and carries out the decision.

### 5. Platform interface `DisplayPower`

`DisplayPower` lives in `core/.../platform/`:

- `val available: Boolean`
- `val screenOn: Boolean`
- `fun turnOff(): Boolean`
- `fun turnOn()`
- `fun setListener(listener: Listener)`, with the callbacks `screenChanged(on)` and `availabilityChanged()`

`FakeDisplayPower` in `core/src/test/.../testing/` records the calls and lets a test send events. `FrameCore` takes a `DisplayPower`, with a default that is never available (`DisplayPower.None`) so that existing tests remain unaffected.

### 6. Android: device administration and switching

- `DisplayAdminReceiver : android.app.admin.DeviceAdminReceiver` is declared in the manifest with `BIND_DEVICE_ADMIN`. `res/xml/device_admin.xml` lists only `<force-lock/>`, which is not among the policies that Android 10+ refuses for plain device administrators. `onEnabled`/`onDisabled` report the availability change.
- `AndroidDisplayPower`:
  - `available` is `dpm.isAdminActive(component)`.
  - `turnOff` calls `dpm.lockNow()` and catches a `SecurityException` as "not available".
  - `turnOn` takes a wake lock `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE` for 5 s. Then `FLAG_KEEP_SCREEN_ON` of the display holds the screen on.
  - `screenOn` is `PowerManager.isInteractive`, or `isScreenOn` on API 21 and below.
- `FrameService` registers `SCREEN_ON`/`SCREEN_OFF` at runtime (they cannot be declared in the manifest) and passes them on.
- `DisplayActivity` reports touches in `dispatchTouchEvent`. The device menu gains an entry that does one of two things:
  - if device administration is inactive, it starts `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` with an explanation;
  - if it is active, it calls `dpm.removeActiveAdmin(component)`.
- Alternative for devices on which the system dialog is a problem: `adb shell dpm set-active-admin io.github.tycho991.openframe/.android.DisplayAdminReceiver`, documented in the README.

*Rejected alternatives:*

- Root (`input keyevent 223`) is ruled out by the user's choice and by kiosk-host › Supported devices.
- Releasing `FLAG_KEEP_SCREEN_ON` and letting the screen timeout act depends on `stay_on_while_plugged_in`. On the Lenovo that setting is 7, so the display would never go off.
- `WRITE_SETTINGS` for the timeout needs a special permission and is a change to system settings.

### 7. Settings and validation

`Settings.displaySchedule: DisplayScheduleSettings`, with two `DisplayWindow(on, off)` and `wakeMinutes`. The name keeps it apart from the arithmetic `DisplaySchedule` of decision 2. The defaults are in spec `display-schedule` › Schedule settings.

- The patch is partial at every level, like `clock`.
- Times are validated as `^([01]\d|2[0-3]):[0-5]\d$`. Error paths look like `displaySchedule.weekend.on`.
- The check of availability happens in `SettingsRepository.patch` through an injected `scheduleAllowed: () -> Boolean`. Only a patch that sets `enabled` to `true` is rejected (`displaySchedule.enabled`). A stored `enabled: true` stays stored after the device administrator is lost.
- Older settings files have no `displaySchedule` and get the defaults (frame-settings › Settings model with defaults).

### 8. API

```yaml
GET /display  -> 200 DisplayState
PUT /display  {on: boolean} -> 200 DisplayState | 400 | 409 (not available) | 403 (foreign origin)
DisplayState: {available: boolean, on: boolean, cause: schedule|manual|wake|none, nextChange: date-time|null}
```

- `PUT` and not `POST`, because setting the same value twice is idempotent.
- `/display` and not part of `/settings`, because the state is not a setting and does not survive a restart.
- Both routes require a token, and `RouteAccessTest` checks that.
- The web UI polls `GET /display` every 30 s while the settings are open (web-admin › Current state). A `/state` version for it is not worth it.

### 9. Resting slideshow

`SlideshowController` gains `setResting(Boolean)`, a command of its own that is separate from the user's pause:

- While resting, the timer is off and `show()` is not called. Library changes are queued, and a deleted current photo is replaced only when the display comes on.
- When resting ends, `show(+1)` runs and the timer is re-armed, unless the user's pause is on.
- `FrameCore` drives it from `display.state.map { !it.on }`. It follows the real screen state, so the slideshow also rests while the display is off by the power button.

## Risks / Trade-offs

- **[Risk] Waking with `ACQUIRE_CAUSES_WAKEUP` is restricted on new Android versions.** It is deprecated from API 33 on, and might be ignored on the Tab S7 (API 36) or the API 37 emulator. → Test early on the API 37 emulator (task 5.2). The fallback is a wake lock without that flag together with `setTurnScreenOn(true)` on the display (API 27+), and the `TURN_SCREEN_ON` permission if the platform asks for it. The specs stay the same either way.
- **[Risk] The Frameo ends openframe when the system dialog of device administration opens.** → The watchdog brings the service back within 40 s. After the confirmation, the Home button brings the display back. The `adb` alternative is documented. Device test on the Frameo.
- **[Risk] On the Lenovo, pictureframe also switches the screen.** → openframe reads a foreign `SCREEN_OFF` as switching off by hand and does not fight it. The device test notes pictureframe's schedule as it is, and pictureframe's settings are not changed without asking.
- **[Risk] Activating device administration is a persistent change on the device, and uninstalling requires deactivating first.** → On the devices only with the user's consent (CLAUDE.md). The README and the device menu say how to deactivate.
- **[Trade-off] On battery, Doze can delay switching beyond the minute.** → Accepted. Frames run on a charger, and the spec's guarantee is checked on a charger.
- **[Trade-off] The manual switch and waking are lost on a restart.** → The grace period after the start keeps a restart at night from being abrupt. A manual off by day is lost after a crash. That is rare, and the next switching time sets things right.
- **[Trade-off] A screen lock is not supported.** → This is the same assumption as kiosk-host › Autostart. The README notes it next to device administration.

## Migration Plan

- Additive. Without `displaySchedule` in `settings.json` the defaults apply, and the schedule is off. Nothing changes until a user activates device administration and switches the schedule on.
- Rollback: an older APK ignores the unknown field when it reads `settings.json` (`JsonStore` reads with `ignoreUnknownKeys = true`) and keeps the display on. Device administration stays active until it is deactivated in the system settings.

## Open Questions

- Whether `lockNow()` on the Frameo's firmware switches the backlight really off, or only shows the lock screen. This is answered by the device test. If it does not, the Frameo is documented as "without real off", and the precondition still holds.
