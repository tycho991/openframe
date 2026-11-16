# Tasks

## 1. Display sizes in `:core`

- [ ] 1.1 `DisplayImages.current(entry)` and `render(entry, priority)` per design decision 1, implemented in `CachedDisplayImages` (`cache.peek(entry, DISPLAY)`, `cache.get(entry, DISPLAY, priority)`) and in `FakeImages` of `SlideshowControllerTest` (a file per presentation variant, renders that complete on demand). Verify: `./gradlew :core:test` is green with the existing tests unchanged

## 2. Slideshow controller in `:core`

- [ ] 2.1 The timer as a deadline per design decision 3: `armTimer()` stores the time of the next change and can be re-armed for the time left. Verify: the existing interval, pause and wake tests stay green, and a new test shows that re-arming after half an interval changes the photo after the other half
- [ ] 2.2 `refreshShown()` and `Command.Rendered` per design decision 2, called after `emit` in `show`, from `onLibrary` when the photo on screen changed its presentation, from `Command.Presentation` in place of `showCurrentAgain()`, and on wake after a presentation change. Verify: tests for `slideshow-display` › Current part of the photo on screen › Section of the photo on screen (a new file for the same photo, the next photo after the interval that was running), › Back with previous (an older file first, then the current one), › Section removed on screen, › Next photo before the switch (the rendered file is not shown for the next photo), and that the tests of › Orientation of the frame and › Presentation changed while the display is off still pass
- [ ] 2.3 A failed render of the current part keeps the older part on screen and logs it. Verify: a test with a failing render in `FakeImages`
- [ ] 2.4 API test for `photo-library` › Section of a photo › Set a section with the photo on screen: `PATCH /photos/{id}` with a section, then the slideshow state shows the photo with a new display file. Verify: `./gradlew :core:test` is green
- [ ] 2.5 Run `./gradlew check`. Verify: green

## 3. Emulator

- [ ] 3.1 On the API 37 emulator with the debug build: set a section on the photo on screen through the web interface, then step forward and back with `POST /slideshow` `next` and `previous` after changing the section of the previous photo. Verify: screenshots show the new section within a few seconds in both cases, and the photo changes after the interval that was running

## 4. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 4.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references and finding 90: a section saved from the Home Assistant card for the photo on screen of the Lenovo (`slideshow-display` › Current part of the photo on screen › Section of the photo on screen), and the photo brought back with "previous" after its section changed (› Back with previous). Verify: the rows exist with their references
- [ ] 4.2 Run the rows of 4.1 after the release, and remove the test sections again. Verify: results entered, finding 90 closed or a finding for anything that differs

## Workflow follow-up

- Archive the change after the device rows of 4.1 are done.
