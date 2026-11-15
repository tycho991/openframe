# Tasks

## 1. Slideshow controller in `:core`

- [ ] 1.1 `SlideshowController.wake()` per design decisions 1 and 2: keep the current photo if the playlist still holds it (prefetch upcoming, full interval), `show(+1)` if it was deleted or hidden (also while paused), the empty state and the first start as today. Verify: tests for › Slideshow rests while the display is off › Morning (photo 0 after the night, photo 1 after exactly one interval), › Off and on by hand, › Paused before the night, and › Photo gone while the display is off › Photo deleted at night, › Photo hidden at night, › All photos gone at night
- [ ] 1.2 Remember a presentation change while resting and render the kept photo again on wake per design decision 3, also while paused. Verify: tests for › Presentation changed while the display is off › Orientation changed at night (a new display file for the same photo after wake, nothing rendered while resting), and that the test "Nothing is rendered ahead while resting" still passes
- [ ] 1.3 Adjust the existing tests whose expectations change ("Morning", "A deleted current photo is replaced only when resting ends") and check the API test for › Operating the slideshow while the display is off › Next photo at night: 409 `display_off`, then the same photo after the display comes on. Verify: `./gradlew :core:test` is green
- [ ] 1.4 Run `./gradlew check`. Verify: green

## 2. Emulator

- [ ] 2.1 On the API 21 emulator with the debug build: note the photo ID from `GET /slideshow`, `PUT /display {"on":false}`, wait longer than the interval, `PUT /display {"on":true}`. Verify: `GET /slideshow` returns the same photo ID, the screenshot shows that photo, and the photo changes after one full interval

## 3. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 3.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references: display off and on by hand (the Home Assistant card or `PUT /display`) on the Lenovo and the Frameo (`display-schedule` › Slideshow rests while the display is off › Off and on by hand), and the morning after a night off by the schedule on both frames (› Morning). Verify: the rows exist with their references
- [ ] 3.2 Run the rows of 3.1 after the release. Verify: results entered, and a finding in `../openframe-private/FINDINGS.md` for anything that differs

## Workflow follow-up

- Archive the change after the device rows of 3.1 are done.
