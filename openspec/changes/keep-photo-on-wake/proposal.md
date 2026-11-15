# Proposal

## Why

When the display goes on, the frame shows the next photo at once (`display-schedule` › Slideshow rests while the
display is off). The owner switches the display off and on from the Home Assistant card and expects to see the photo
that was on screen before. Instead, every off and on skips a photo. The slideshow should pause while the display is
off and pick up where it stopped.

## What Changes

- When the display goes on, the photo that was on screen when it went off SHALL stay, and the interval SHALL start
  over. This replaces the rule that the next photo appears at once. It applies to every way the display goes off and
  on: the display schedule, the manual switch in the web interface, `PUT /display` (and so Home Assistant), the power
  button, and the end of a wake period.
- If that photo was deleted or hidden while the display was off, the next visible photo SHALL appear at once, as
  today for a deleted photo.
- If the fill mode or the orientation changed while the display was off, the kept photo SHALL appear in the new
  presentation. Today a presentation change while the display is off is dropped. Until now that did not matter
  because a new photo came anyway, but it matters now that the photo stays.
- A user's pause keeps working as before: a paused slideshow stays paused, with the same photo.
- `next` and `previous` while the display is off keep answering 409 `display_off`. The scenario "Next photo at night"
  changes from "moves on by one photo" to "the same photo stays".

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `display-schedule`: "Slideshow rests while the display is off". The photo on screen stays when the display goes
  on, and the interval starts over. A photo deleted or hidden while off is replaced by the next one. A presentation
  change while off applies to the kept photo.
- `slideshow-display`: "Operating the slideshow while the display is off". The scenario "Next photo at night" now
  ends with the same photo on screen.

## Impact

- `core/.../slideshow/SlideshowController.kt`: `wake()` and the handling of `Command.Presentation` while resting.
- `core/src/test/.../slideshow/SlideshowControllerTest.kt`: the tests "Morning" and "A deleted current photo is
  replaced only when resting ends", and new tests for a hidden photo and a presentation change while off.
- No change to the API contract, the web interface, the Android display code or Home Assistant. The integration
  reports the photo on screen, so its card keeps the photo too.
- Device check on both frames: the same photo after off and on by hand and after a night by the schedule.
