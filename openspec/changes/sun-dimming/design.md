# Design

## Context

See proposal.md for the motivation. What is already in place shapes the approach:

- `DisplayController` evaluates every 30 s (`TICK_MS`) and at once on events. `BrightnessPolicy.decide` is pure and
  returns a target and an `edge`. The controller writes only when the edge changes, so a change on the device holds
  until the next edge (design decision 4 of `add-display-brightness`).
- `DisplaySchedule.dimAt` and `nightAfterDim` give the fixed dim period and the night after it. `BrightnessOverride`
  holds a client's level while the dim stretch with the same start lasts.
- `StoredBrightnessSnapshot` (a `JsonStore`) keeps Android's value and automatic flag from the start of dimming in mode
  `system`, across restarts.
- `BrightnessScale` maps 1 to 100 % linearly onto raw 1 to 255. On the Lenovo 1 % is raw 3. The reference frames run
  Android 5.0.1 and 6.0.1, whose system setting is linear in backlight terms.
- `place-search` provides `GET /places` and the schema `PlaceResult` (name, region, country, countryCode, latitude,
  longitude). This change is implemented after it.
- Settings load with `ignoreUnknownKeys = true`. `SettingsPatch` validates field by field with paths such as
  `displaySchedule.weekdays.dim`.

## Goals / Non-Goals

**Goals:**

- The brightness is a pure function of the instant, the settings and the stored snapshot, tested with `TestClock`
  across a winter and a summer day, polar days, DST and restarts mid-fade.
- Mode `fixed` without dim times, and every frame that never chose `sun`, behave as today except for the 30-minute fade.
- `:android` does not change: it still reads and writes raw values.

**Non-Goals:**

- On and off times by the sun.
- Configurable elevation limits, an offset, or a configurable fade length.
- Weather or the light sensor in the curve. Android's automatic brightness stays a mode of its own.
- A select for the dim mode in ha-openframe (its own change after the release).

## Decisions

### 1. Solar elevation by the NOAA formulas, crossings by search

`SolarPosition.elevation(instant, latitude, longitude): Double` uses the NOAA solar calculator equations: Julian
century, geometric mean longitude and anomaly, equation of centre, apparent longitude, obliquity, declination,
equation of time, hour angle. It has no atmospheric refraction term. The limits already include the standard
refraction and solar radius (−0.833°), as official sunrise and sunset do.

Crossings (sunrise and sunset at −0.833°, civil dawn and dusk at −6°) are found by stepping through the local day in
10-minute steps and bisecting each sign change to 1 second. One code path covers every latitude: a day without a
crossing simply has none. That is spec `display-schedule` › Brightness by the sun › Sun does not set, and the
polar night is covered the same way.

- *Alternative:* the closed-form hour angle for sunrise. It is faster but needs special cases for polar days and nights
  and a second formula per limit. Stepping costs about 150 elevation evaluations per day, which is microseconds.

Tests compare against values from the NOAA calculator for three places (51° N 10° E, 0° 0°, 70° N 25° E) on solstices
and equinoxes, within 1 minute.

### 2. One curve, a function of elevation

In mode `sun`:

```
f(e) = clamp((-0.833 - e) / (-0.833 - (-6)), 0, 1)       e = elevation now
raw  = round(exp(ln(dayRaw) + f * (ln(nightRaw) - ln(dayRaw))))
```

`f = 0` is day, `f = 1` is night, and in between is a fade in the evening (elevation falling) or the morning (rising).
Interpolating the logarithm of the raw value gives equal ratios in equal times (spec › Smooth fades). With raw values
of at least 1, the logarithm is always defined.

In mode `fixed` the same interpolation runs on time: `f = (now - dimStart) / 30 min`, clamped. It ends at the end of
the dim period if that comes first.

Because the target is a function of the instant, a restart mid-fade lands on the right value without any stored
progress.

### 3. Phases as edges

`BrightnessPolicy.decide` returns a phase together with its start, which serves as the edge:

```
sun:    DAY -> EVENING_FADE -> NIGHT -> MORNING_FADE -> DAY
        (-0.833 down)  (-6 down)  (-6 up)     (-0.833 up)
fixed:  BEFORE -> FADE -> DIMMED -> NIGHT_AFTER_DIM -> (next on period)
                (dim)  (dim+30m)  (end of period)
```

The edge is `(phase, phaseStart)`. Outside a fade phase the controller writes only when the edge changes, as today.
In a fade phase it writes whenever the target's raw value differs from the value it wrote last.

The dim period (for `dimmed`, the override and the snapshot) is `EVENING_FADE..MORNING_FADE` in mode `sun`, starting
at the sunset crossing, and `FADE..NIGHT_AFTER_DIM` in mode `fixed`, as `dimAt`/`nightAfterDim` give it today.
`BrightnessOverride.start` keeps comparing against that start. So a client's level holds until sunrise in mode `sun`,
also across the schedule's next on time (owner's confirmation).

Sun phases are found around `now` from the crossings of decision 1: the last and the next crossing of −0.833° and of
−6°.

### 4. Pausing on a change on the device

The controller keeps `lastWritten: RawBrightness?` in memory. In a fade phase, if `brightness.read()` differs from it
in value or automatic flag, the controller remembers that edge as paused and writes nothing more until the edge
changes. At start `lastWritten` is `null`, and the start is an edge anyway (spec `display-brightness` › Changes on the
device).

Only the frame's own writes are compared. A client's level goes through `manualBrightness` and becomes an override, so
it is not taken for a change on the device.

### 5. Day brightness and snapshot

- `manual`: `dayRaw = BrightnessScale.toRaw(level)`, without a snapshot.
- `auto` and `system`: at the start of the dim period the controller stores the snapshot, as it does today for
  `system`. It now does so for `auto` too, as the start value of the fade. `dayRaw = snapshot.value`. The end of the
  dim period restores as today: the automatic brightness for `auto`, and the snapshot's value and flag for `system`.
- If a dim period is entered without a snapshot (mode chosen at night, or a lost file), the current value is stored as
  the snapshot at the first evaluation.

The fade in mode `fixed` starts from `dayRaw` as well (spec › Day brightness). In mode `manual` that is the stored
level even if someone changed the brightness on the device by day: the start of the dim period is an edge, so the frame
applies its own value there.

### 6. Evaluation cadence

`TICK_MS` stays 30 s. While the decision is in a fade phase and not paused, the loop waits `FADE_TICK_MS = 5 s`.
Events still wake it at once. Writes happen only when the raw value changes, so a 47-minute winter fade from 80 % to
20 % writes at most about 150 times.

### 7. Settings, validation and API

```kotlin
enum class DimMode { FIXED, SUN }                     // "fixed", "sun"
data class FramePlace(val name: String, val region: String?, val country: String?,
                      val countryCode: String?, val latitude: Double, val longitude: Double)
DisplayScheduleSettings(..., dimMode = DimMode.FIXED, place: FramePlace? = null)
```

- The patch replaces `place` as a whole object, and `null` removes it. Fields are validated with paths like
  `displaySchedule.place.latitude`. The rule "mode `sun` needs a place" is checked on the settings after the patch.
  The error names `displaySchedule.dimMode` when the mode changed in that patch and `displaySchedule.place` otherwise.
- `api/openapi.yaml`: `DisplaySchedule` gets `dimMode` (`DimMode` enum) and `place` (`PlaceResult` or `null`, both
  required in responses, optional in the patch). `BrightnessState` gets `sun`: `{sunrise, sunset}` as `date-time` or
  `null` each, itself `null` in mode `fixed`. A search result can be sent as the place unchanged.
- The names are stored in the language in which the place was chosen. A later change of the frame's language does not
  rename them, which is acceptable for a label.
- The log says "Dim mode sun" or "fixed" without the place or coordinates.

### 8. Web UI

- In the schedule section, a segmented control chooses "At a fixed time" or "By the sun". "At a fixed time" shows the
  two dim time fields as today. "By the sun" shows a `PlaceSearch` combobox and, below it, today's sunrise and sunset
  from `GET /display` (`brightness.sun`) once saved, plus the GeoNames attribution component used in the caption
  settings. The dim level is shown in both modes as "Night brightness" or "Dim level".
- `PlaceSearch`: ARIA combobox with listbox, a 250 ms pause before searching, at least 2 characters, an
  `AbortController` per request so a late answer cannot overwrite a newer one, arrow keys and Enter, results as
  "name, region, country", and an empty state.
- Save stays disabled with a field error while "By the sun" has no place, mirroring the 400 of the frame.

## Risks / Trade-offs

- [Steps are visible at the lowest levels: raw 3 → 4 is a third brighter] → The logarithmic curve spends its time
  evenly across ratios. A device row in a dark room judges a fade to 1 % and to 20 %. A finer dimming below raw 1
  would need the window brightness, which is out of scope.
- [About 150 to 250 writes of `Settings.System` per evening; Android 6 persists the settings file after changes] →
  That is a few MB a day of small file writes on a device that already writes logs and caches. The device row samples
  the writes with `logcat`.
- [In summer the fade often begins after the off time, so mode `sun` seems to do nothing] → The web UI shows today's
  sunset next to the off time, and the README says it.
- [In the polar night a client's level holds until the sun rises, possibly for weeks] → This follows the dim period
  definition. The next change of the brightness settings or the dim mode resets it. No reference frame is affected.
- [A wrong time zone or clock] → The elevation uses the instant, not the local time, so only the displayed times are
  affected. A clock before 2026 counts as not set, as for the schedule.
- [The fixed dim rows on the devices expect a jump at the dim time] → They are updated in the private checklist.

## Migration Plan

Settings without `dimMode` and `place` load as `fixed` and no place, so nothing changes until a user chooses `sun`.
Older APKs ignore the new fields (`ignoreUnknownKeys`): after a rollback a frame in mode `sun` dims by its stored dim
times, if any, or not at all. The snapshot file keeps its format. Its use for mode `auto` is additive.
