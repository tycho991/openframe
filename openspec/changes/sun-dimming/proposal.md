# Proposal

## Why

The display schedule dims at one fixed time per window, and it does so in one jump. A fixed time does not follow the
seasons: at 51° N, 10° E the sun sets at about 16:16 in December and at 21:39 in June, so a dim time that suits winter
evenings is wrong in summer, and the other way round. In the morning nothing dims at all: a frame that goes on at
07:00 in December is at full brightness more than an hour before sunrise. The owner wants dimming that follows
sunset and sunrise, and gradual transitions between brightness levels instead of jumps.

## What Changes

- **Dim mode** in the display schedule (owner's decision): `fixed` (default, today's dim time per window) or `sun`.
  The dim times stay stored in mode `sun`, so switching back loses nothing. `dimLevel` is the night level in both
  modes.
- **Place of the frame** in the display schedule: name, region, country and coordinates, chosen in the web UI through
  the offline place search of the change `place-search`. Mode `sun` needs a place.
- **Brightness by the sun** (mode `sun`): from sunset (sun at −0.833°, the official sunset) to the end of civil dusk
  (−6°) the display fades from the day brightness to the dim level. In the morning it fades back from −6° to sunrise.
  In between it stays at the dim level. The day brightness is the stored brightness: the level in mode `manual`, and
  in modes `auto` and `system` Android's value when the evening fade began.
- **Fade at the fixed dim time** (owner's decision): 30 minutes from the dim time to the dim level, cut off at the off
  time. Not configurable.
- **Smooth fades.** Steps at most every 5 seconds, with equal ratios per time (perceptual, logarithmic), so that the
  end near the low levels does not look like a drop.
- **Changes on the device during a fade** (owner's decision): the fade pauses until the next phase (day, evening
  fade, night, morning fade, or the end of the fixed fade).
- **A brightness from a client** during the sun's dim period (sunset to sunrise) holds until sunrise. This includes
  Home Assistant's display light. Outside it, a brightness becomes the stored level with mode `manual`, as today.
  Light-sensor automations in Home Assistant keep working.
- **`GET /display`** reports today's sunrise and sunset at the frame's place in mode `sun`. `dimmed` is true from the
  start of a fade.
- **Web UI**: a choice of dim mode, the place search, today's sunrise and sunset, and the GeoNames attribution next to
  the place. The README explains the sun mode, notes that in summer it often acts after the off time, and advises
  light-sensor automations to send only larger changes.

Not breaking: settings without the new fields load with mode `fixed` and no place. A client that does not know the
fields keeps working.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `display-schedule`: dim mode, place of the frame, brightness by the sun, its dim period and day brightness, the
  30-minute fade at a fixed dim time, smooth fades, sun times in the display state, and changes of mode or place
  during a dim period.
- `display-brightness`: a change on the device pauses a running fade until the next phase.
- `web-admin`: dim mode and place search in the schedule settings, and the place data attribution next to the frame's
  place.

## Impact

- Depends on `place-search` (`GET /places`, place data v2). Implement after it is merged.
- `:core`: a solar position calculation (NOAA, pure, no dependency), `DisplaySchedule`/`BrightnessPolicy` (curve,
  phases, fades), `DisplayController` (5-second evaluation during fades, last written value, snapshot also for the
  fade start), `Settings`/`SettingsPatch` (dim mode, place, validation).
- `api/openapi.yaml`: `DisplaySchedule.dimMode`, `DisplaySchedule.place`, `BrightnessState.sun`, the patch schema;
  then `npm run gen:api`.
- `webadmin/`: the schedule section of `SettingsView`, a place search component, `en.json`/`de.json`.
- README: dimming, privacy (the place is stored on the frame only), Home Assistant notes.
- `ha-openframe`: unaffected. A select for the dim mode follows as its own change in that repository after the
  release.
- Device checklist in `../openframe-private/`: fade rows on both frames, and the existing dim rows updated for the
  fade.
