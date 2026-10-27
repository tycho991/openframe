# Tasks

## 1. Font, icons and licences

- [x] 1.1 Inter 4.1 from its official release, subset with `pyftsubset` per design decision 4 (Latin, Latin-1, Latin Extended-A, general punctuation, €, arrows, minus, check mark; features `kern`, `liga`, `calt`, `tnum`, `case`) into `webadmin/src/assets/fonts/InterVariable.woff2` and the four static TTFs in `android/src/main/assets/fonts/`; the exact commands in `android/src/main/assets/fonts/README.md` and `webadmin/src/assets/fonts/README.md`; verify: rerunning the documented commands reproduces byte-identical files, the woff2 is at most 150 KB and the four TTFs together at most 400 KB, and `ttx -t GSUB` lists `tnum` in each file
- [x] 1.2 Licence texts: `Inter-OFL.txt` and `MaterialSymbols-LICENSE.txt` in `webadmin/public/licenses/` and `android/src/main/assets/licenses/`; `public/` added to the inputs of `buildWebadmin`; verify: after `npm run build` both files are in `webadmin/dist/licenses/`, and a change to one of them makes `./gradlew :android:buildWebadmin` run again instead of reporting up to date

## 2. Tokens in the web UI

- [x] 2.1 `webadmin/src/styles/tokens.css` per design decision 1: colour roles with `light-dark()` (with `outline` and `divider` replacing `border`), spacing, radii, type scale, `--target`, motion durations and easing, `@font-face` for Inter with the fallback stack, `color-scheme` on `:root` and narrowed by `data-theme`; `app.css` imports it and keeps only base element styles using the tokens; verify: `npm run lint` and `npm run typecheck` pass, and `npm run build` emits the font under `dist/assets/` with a hash
- [x] 2.2 `src/lib/tokens.ts` that parses `tokens.css` (custom properties and `light-dark()` pairs) and `tokens.test.ts` with the contrast pairs of design decision 11 in both themes; verify: `npm test` passes, and lowering `--muted` in the dark theme to `#6b6862` for a trial run makes the test fail with the pair and its ratio (then reverted)
- [ ] 2.3 Reduced motion: the `prefers-reduced-motion: reduce` block that sets the motion tokens to `0ms`; verify: covered by the Playwright test in 6.4

## 3. The frame on the tokens

- [ ] 3.1 `android/src/main/res/values/design_tokens.xml` with the `ds_*` colours (dark values) and dimensions the frame uses, `themes.xml`, `activity_display.xml` and `caption_lines.xml` referring only to `ds_*` and the frame-only `frame_*` values (background, panel, shadow, text over photos and the panel) per design decision 2; verify: `./gradlew :android:lint` without new warnings, and the emulator screenshots of 3.4 show the same colours as before except the empty state's texts in `ds_text` and `ds_muted` (the accent is unchanged)
- [x] 3.2 `checkDesignTokens` in `android/build.gradle.kts` per design decision 3, wired into `check` and before `preBuild`; verify: `./gradlew :android:checkDesignTokens` passes; with a trial change of `ds_accent` it fails and names `accent`, and with a trial `ds_unknown` it fails and names the missing token (both reverted)
- [x] 3.3 `FrameType` per design decision 5 (four typefaces from assets, cached, fallback to the style's `fontFamily` with a log line); `DisplayActivity` assigns them to the hint bar (name, address, code label, code, pause button), the empty state and the QR captions, `ClockOverlay` to time and date, `CaptionOverlay` to the caption line and the place; `setFontFeatureSettings("tnum")` on the clock time and both codes; verify: a Robolectric test loads all four typefaces from the assets, `ClockOverlayTest` checks the tabular figures on the time, and `./gradlew :android:check` passes
- [ ] 3.4 Emulator screenshots at 1024 × 600 (160 dpi) and 1920 × 1200 (240 dpi), landscape and portrait, German and English, of slideshow with clock and caption, hint, locked hint and empty state, replacing those in `docs/screenshots/`; verify: in every screenshot the texts are in Inter, nothing is clipped or ellipsized except per the hint bar rule in design.md (Risks), and the QR codes still decode

## 4. App shell

- [x] 4.1 `src/lib/icons.ts` with the path data of the Material Symbols Rounded icons in use (navigation, close, back, appearance, and those the components need in group 5) and `Icon.svelte` per design decision 6; verify: unit test that every icon name used in the components exists in `icons.ts`, and `npm run lint` passes
- [x] 4.2 `src/lib/viewport.svelte.ts` (width class from `matchMedia` at 600 and 1200 px) and `src/lib/theme.svelte.ts` (`auto`/`light`/`dark`, own `localStorage` key, memory fallback, `data-theme` on `<html>`, `<meta name="theme-color">`), applied in `main.ts` before `mount()`; verify: unit tests for the width classes at 599/600/1199/1200 px, for storing and reading the theme, for a throwing `localStorage`, and for `data-theme` being absent with `auto`
- [x] 4.3 `App.svelte` per design decision 7: one `<nav>` placed as bottom bar, rail or side navigation by CSS, top bar with the frame's name and the appearance button, the side navigation with both in the expanded class, the appearance menu with language, theme and links to `/licenses/`; `index.html` with `viewport-fit=cover` and the safe-area paddings of design decision 10; new keys in `en.json` and `de.json`; verify: `npm run typecheck` passes, and the i18n test that both files have the same keys passes
- [x] 4.4 Playwright in `layout.spec.ts`: at 390, 800 and 1280 px the navigation is at the bottom, in a rail and in a side navigation with labels (by bounding box), the current section has `aria-current`; the theme follows `emulateMedia({ colorScheme })` without a choice, and a chosen light theme survives a reload under a dark system scheme; the licence file answers with its text; `document.fonts.check("16px Inter")` is true; verify: `npm run e2e` passes in Chromium, Firefox and WebKit

## 5. Components on the tokens

- [x] 5.1 Move every component to tokens and icons: no hard-coded colours left (today in `QrCode`, `ConfirmDialog`, `PhotoDetail`, `SectionEditor`, `VirtualGrid`, `UploadPanel`, `SourcesView`, `SourceCard`; the QR code keeps dark on light as a token pair; the `--warning` fallbacks go to the new `warning` token), cards and section headings from the shared styles, forms in a centred column of at most 720 px; verify: `grep -nE "#[0-9a-fA-F]{3,8}\b|rgb\(" webadmin/src/components/*.svelte` finds nothing, and `npm run lint` passes
- [ ] 5.2 Touch targets and focus per design decision 10: buttons, inputs and `<select>` at `--target`, icon buttons 44 × 44, label rows as targets for checkboxes and switches, the "Show" link of the skipped files as a button, the focus ring on `bg` and `surface`; verify: a Playwright audit at 360 px in all five sections (and with the upload panel, the invitation and the rename form open) finds no control whose own box and label box are both smaller than 44 × 44 px
- [ ] 5.3 Playwright: no horizontal scrolling at 360, 390, 800 and 1280 px in all five sections, including Sources, and in landscape at 844 × 390 px with the navigation not covering content; verify: `npm run e2e` passes

## 6. Photo detail by width

- [ ] 6.1 `PhotoDetail` per design decision 8: a modal `<dialog>` as full-screen layer below 600 px and as sheet from 600 px, an `<aside>` in the shell's detail area from 1200 px; the selected photo stays state of `PhotosView`, and a change of width class switches the form with the same photo; motion only through the motion tokens; verify: `npm run typecheck` passes and the tests in 6.4
- [ ] 6.2 Focus return: on close the detail asks `VirtualGrid` to scroll the opener's tile into view and focuses it; `ConfirmDialog` returns focus to its opener as well; verify: covered by 6.4
- [ ] 6.3 The existing photo tests (`photos.spec.ts`) adapted to the detail as panel at 1280 px and as layer at 390 px; verify: `npm run e2e` passes
- [ ] 6.4 Playwright for the spec scenarios: "Detail as a panel" (second photo shown in the panel, grid visible), "Detail on a phone" (same photos after closing), "Rotate a tablet" (1280 × 800 to 800 × 1280 with an open detail, then closing shows the same place), "Unsaved input" (changed name kept across a width change), "Close the photo detail" (Escape returns focus to the tile), "Reduced motion" (computed transition duration of the detail is `0s`); verify: `npm run e2e` passes in all three browsers

## 7. Integration and devices

- [ ] 7.1 Full checks: `./gradlew check`, and in `webadmin/` `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e`; `openspec validate design-system --strict`; verify: all green, the validator ends with exit 0 and no warnings
- [ ] 7.2 `docs/DEVICE_TEST.md` rows for the hardware-only checks with their spec references: Inter, colours and nothing clipped in hint bar, empty state, clock and caption on the Lenovo, the Frameo and the Galaxy Tab S7 in landscape and portrait (`visual-design` › Typeface Inter › Frame on Android 5; Tabular figures; Colour themes › Frame on a device in light mode on the Tab S7); the web UI on a phone with a home indicator in portrait and landscape (`web-admin` › Safe areas) and on the Tab S7 rotated with an open photo detail (`web-admin` › Layout follows width and orientation); verify: the rows exist with scenario references
- [ ] 7.3 Run the device rows of 7.2 after asking the user before installing the build on each device (CLAUDE.md); record the results in `DEVICE_TEST.md` and anything unexpected in `docs/FINDINGS.md`; verify: every row has a result or a stated reason why it is open

## Workflow follow-up

- Archive `design-system` after review; this syncs `visual-design` as a new main spec and the `web-admin` delta.
- Then the follow-up changes for the frame's own admin interface: settings with the gear icon (replacing the
  corner menu), sources and paired devices, photos and albums.
