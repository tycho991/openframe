# Tasks

## 1. Zoom range in `:core` and the shared cases

- [x] 1.1 `Presentation.zoomRange` returns `z_min..MAX_ZOOM` per design decision 1, KDoc of `zoomRange`, `Presentation` and `Crop` updated (no "one original pixel per screen pixel" limit any more). Verify: `./gradlew :core:test` fails only in the cases of `presentation-cases.json` that rely on the old limit
- [x] 1.2 `api/fixtures/presentation-cases.json` per design decision 5: the cases above the old limit get the stored zoom and its window, new cases for `photo-library` › Largest zoom › Photo of 12 megapixels, › Photo of about the screen's size, › Photo of 48 megapixels (output size 1920×1200 at most, part with more original pixels than the screen), and `slideshow-display` › Section across screen formats › Switch to landscape and › Switch to portrait (now zoom 2 in portrait); the `description` names this change next to `photo-crop`. Verify: `./gradlew :core:test` is green
- [x] 1.3 API test with `TestFrame`: `PATCH /photos/{id}` with zoom 2 on the 400×300 test photo (the test frame's screen is 1024×600, so the photo is already enlarged at zoom 1), then `GET /photos` reports `presentation.zoom` 2 and the display URL renders the part's 200×117 original pixels. Verify: `./gradlew :core:test` is green with the contract check on
- [x] 1.4 Run `./gradlew check` with `:android` (an Android unit test used zoom 4 for the 1:1 window and now uses `sharpZoom`). Verify: green

## 2. Section editor in the web UI

- [x] 2.1 `presentation.ts`: `zoomRange` as in 1.1, `sharpZoom`, `enlargement(photo, screen, presentation)` and `enlargedPercent` per design decision 2, comments updated. Verify: `npm test` runs the changed fixture table green, plus unit tests of `enlargement` for 1600×1200 at zoom 2 (2.4) and 8000×6000 at zoom 4 (0.96)
- [x] 2.2 Warning icon from Material Symbols Rounded in `lib/icons.ts` (same source and style as the others), `section.enlarged` in `en.json` and `de.json`, and the status line in `SectionEditor.svelte` per design decision 3; the hint text `section.hint` mentions that zooming in further enlarges the photo. Verify: a server-rendered component test (`SectionEditor.test.ts`, like the other component tests) for `web-admin` › Warning for an enlarged section › Zoom into a phone photo (about 42 %), › Sharp again and › Small photo without a section (about 67 %), and `npm run lint` and `npm run typecheck` are green
- [x] 2.3 The zoom-in and zoom-out buttons stop at `1/c` per design decision 4, through `zoomStep` in `presentation.ts` (the component tests render on the server, so clicks are tested on the function). Verify: unit tests for `web-admin` › Zoom buttons stop at the sharp limit › Stop before enlarging (1.8 → about 2.08 → about 2.6, and back down), that a wheel or pinch step crosses the limit, and that there is no stop when `1/c` lies outside the range
- [x] 2.4 e2e in `webadmin/e2e/section.spec.ts`: zoom a photo of about the screen's size beyond the old limit, see the warning, save, and find `presentation.zoom` above 1 in `GET /photos`; also with the phone viewport of `web-admin` › Section editor on the phone. Verify: `npm run e2e` is green with its own `OPENFRAME_E2E_PORT`

## 3. Emulator

- [x] 3.1 On the API 37 emulator (2560×1600) with the debug build: a synthetic test photo of 3200×2400 pixels (sharp up to zoom 1.25) zoomed with the editor's buttons in the frame's web interface (1 → 1.25 → 1.5625 → 1.95) and saved. Verify: the editor showed the warning with about 64 %, and a screenshot of the frame shows the same enlarged part filling the screen

## 4. Devices (each persistent device change only with the owner's consent at that moment)

- [x] 4.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references: a phone photo zoomed to about 50 % and 25 % of the screen's resolution on the Frameo and the Lenovo, judged for how blurred it looks (`photo-library` › Largest zoom › Photo of about the screen's size), and the warning in the editor on a phone (`web-admin` › Warning for an enlarged section › Zoom into a phone photo). Verify: the rows exist with their references
- [ ] 4.2 Run the rows of 4.1 after the release and remove the test sections again. Verify: results entered, or a finding for anything that differs

## Workflow follow-up

- Propose the matching change in `ha-openframe` (its `frontend/presentation.js` copy of `zoomRange` and the warning in the card's editor).
- Archive the change after the device rows of 4.1 are done.
