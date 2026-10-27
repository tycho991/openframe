# Design

## Context

See proposal.md for the motivation. What is in place today:

- **Web UI.** `app.css` defines about a dozen custom properties (`--bg`, `--surface`, `--accent`, …) in `:root`
  and a second set in `@media (prefers-color-scheme: dark)`. `App.svelte` renders a sticky header with brand, a
  link row and the language `<select>`, and one `<main>` capped at 1400 px. Its only layout breakpoint is 760 px.
  Eight components still carry hard-coded colours, and two refer to a `--warning` property that nothing defines. `PhotoDetail` and `ConfirmDialog` are modal `<dialog>`s opened
  with `showModal()`. Buttons are 40 px high. `index.html` has no `viewport-fit=cover`.
- **Serving.** `StaticAssets` already maps `woff2`, `svg` and `txt`, and the CSP allows `font-src 'self'` but no
  inline script (`script-src 'self'`). Hashed files under `assets/` are cached for a year, everything else is
  `no-cache`. `buildWebadmin` in `android/build.gradle.kts` lists `src/` and a few files as inputs; a `public/`
  folder would not be covered.
- **Frame.** `themes.xml` defines `frame_*` colours (black background, white text, accent `#5FB39B`, a dark
  translucent panel, a text shadow) and text styles with `fontFamily` `sans-serif`, `sans-serif-medium` and, for the
  clock, `sans-serif-light`. No Kotlin code sets a `Typeface`. `ClockOverlay` sets text sizes in code from
  `sizePercent`; `CaptionOverlay` (from `photo-captions`) shows the caption over the photo in the `FrameText.Caption`
  styles. The frame's accent equals the web UI's dark accent today; nothing keeps them equal.
- **Constraints.** Framework only on Android, minSdk 21: XML `fontFamily` cannot point at a bundled font before API
  26, and variable font axes cannot be set before API 26. Browsers: the two latest majors of Chrome, Firefox and
  Safari. The web UI must not load anything from a foreign server.

## Goals / Non-Goals

**Goals:**

- One token file that both the web UI and the frame follow, with a build check instead of discipline.
- Layout switches by CSS wherever possible, so that a change of width never rebuilds the DOM and loses state.
- Every rule in the specs checked by a test: unit tests for tokens and contrast, Playwright for widths, rotation,
  focus, touch targets, theme and reduced motion, `DEVICE_TEST.md` only for what needs real hardware.

**Non-Goals:**

- A component library or a design-token build tool. The web UI has 18 components; plain CSS and a few small
  Svelte components are enough.
- Changing the frame's layout. The hint bar, the empty state and the clock keep their structure; only typeface,
  token colours and figures change.
- Icons on the frame. The frame's current views have no icon; the first one, the gear, comes with the frame's
  settings change. This change only fixes the set and its format.

## Decisions

### 1. `tokens.css` is the single source, with `light-dark()`

A new `webadmin/src/styles/tokens.css` holds every token as a custom property on `:root`. Colours are written once
with both themes: `--surface: light-dark(#ffffff, #1f1f1d);`. `:root` sets `color-scheme: light dark`;
`:root[data-theme="light"]` and `[data-theme="dark"]` narrow it to one scheme, which is all a theme choice needs.
Spacing, radii, type sizes and motion durations are plain values (`--space-3: 12px`, `--radius-m: 12px`,
`--text-body: 16px`, `--motion-medium: 250ms`). `app.css` keeps only base element styles and uses the tokens.

- *Alternative:* two blocks, `:root` and `@media (prefers-color-scheme: dark)`, plus a third for the manual dark
  choice, as today's pattern would grow into. Every dark colour would be written twice, and the check (decision 3)
  would need to know which block wins.
- *Alternative:* a JSON file and a generator such as Style Dictionary that writes CSS and Android XML. A new tool
  in two builds for about 30 values; the check gives the same safety.
- `light-dark()` is in Chrome 123, Firefox 120 and Safari 17.5, inside the supported range.

Token names follow roles, not colours: `bg`, `surface`, `surface-2`, `text`, `muted`, `outline` (borders of
controls, at least 3:1), `divider` (decorative lines, no contrast rule), `accent`, `on-accent`, `danger`, `warning`
(the notices of the sources, which refer to an undefined `--warning` today), `focus`, `scrim`. Today's `--border` splits into `outline` and `divider`: `#dedad2` on white is about 1.3:1 and passes as a
divider, not as the outline of an input.

### 2. The frame mirrors tokens by name: `ds_<token>`

`android/src/main/res/values/design_tokens.xml` holds the values the frame uses, named `ds_` plus the token name
with `_` for `-`: `<color name="ds_accent">` is `--accent`, `<dimen name="ds_space_3">` is `--space-3`. Colours take
the token's dark value, because the frame is always dark. CSS `px` maps to `dp` and type sizes map to `sp`. Values
that exist only on the frame stay `frame_*` (black background behind photos, the panel over a photo, the text
shadow) and are not checked. `themes.xml` and the layout refer to `ds_*` and `frame_*` only.

Text over a photo or over the panel (clock, caption, hint bar) keeps `frame_text` and `frame_text_muted`, white and
80 % white with the shadow. The panel (`#B3101010`) over a white photo is about `#575757`, on which `ds_muted`
(`#a19d95`) reaches only about 2.7:1 and 80 % white about 5.3:1. The empty state on the black background uses
`ds_text` and `ds_muted`, the pairing code `ds_accent`.

### 3. `checkDesignTokens` in the style of `checkNoAndroidImports`

A Gradle task in `android/build.gradle.kts` reads `tokens.css` and `design_tokens.xml` and fails with the name of
each token whose value differs or that the CSS does not define. It parses with regular expressions, since both
files are written by hand in a fixed shape: one declaration per line, colours as `#rrggbb` or `#aarrggbb`. It runs
in `check` and before `preBuild`, like the import check before `compileKotlin`. `tokens.css` becomes an input of
`buildWebadmin` through `src/`, so a token change also rebuilds the web UI in the APK.

- *Alternative:* a JVM unit test in `:android`. Tests read the working directory, which differs between Gradle and
  the IDE, and a test is easier to skip than a check that the build depends on.

### 4. Inter 4.1, subset once, committed

Inter comes from its official release (SIL Open Font License 1.1). It is subset once with fonttools
(`pyftsubset`): Latin, Latin-1 and Latin Extended-A, the general punctuation block, €, the arrows, the minus sign
and the check mark, with the layout features `kern`, `liga`, `calt`, `tnum` and `case`. Latin Extended-A costs about
10 KB per file and keeps the place names and album titles of the frame's caption (Łódź, Plzeň, İstanbul) in one
typeface. The subset files and the command, in a `README.md` next to them, are committed; neither build needs
Python.

- **Web:** one variable file `InterVariable.woff2` (weights 300 to 700) under `webadmin/src/assets/fonts/`,
  imported from `tokens.css` with `@font-face`, `font-display: swap` and the stack
  `Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`. Vite puts it under `assets/` with a hash, so it
  is cached for a year. It keeps the optical size axis, so large headings get Inter's display forms. Expected size
  about 65 KB.
- **Frame:** static `Inter-Light.ttf` (clock), `Inter-Regular.ttf`, `Inter-Medium.ttf` and `Inter-SemiBold.ttf`
  under `android/src/main/assets/fonts/`, together about 300 KB.
- *Alternative:* the full files (about 350 KB for the variable woff2, 400 KB per static TTF). Twice the APK and
  page weight for scripts the UI never shows.
- *Alternative:* the system font. That is what we have, and it is Roboto on the frame and San Francisco or Segoe
  elsewhere, so the two surfaces never look alike.

### 5. `FrameType` assigns typefaces by role on the frame

A small `object FrameType` in `:android` loads the four typefaces from assets once (`Typeface.createFromAsset`,
cached, because every call creates a new native typeface) and offers `light`, `regular`, `medium` and `semibold`.
`DisplayActivity`, `ClockOverlay` and `CaptionOverlay` set them on their views after inflating, by role: titles
semibold, body and caption regular, code medium, the clock time light. The XML styles keep their `fontFamily` as
the fallback if an asset fails to load. The clock and the pairing code get `setFontFeatureSettings("tnum")`,
available since API 21.

- *Alternative:* `TextView` subclasses with a custom weight attribute. Worth it once the frame has its own admin
  views with many texts; for the dozen text views of today the explicit assignment is simpler. The frame's settings
  change may introduce them.

### 6. Material Symbols Rounded as inline SVG

Icons are Material Symbols Rounded (Apache License 2.0), weight 400, grade 0, optical size 24, outlined, filled
for the current section in the navigation. Only the icons in use are copied as path data into
`webadmin/src/lib/icons.ts` and drawn by an `Icon.svelte` component as inline SVG with `currentColor` and
`aria-hidden="true"`; the button around it carries the accessible name. On the frame the same path data becomes
`VectorDrawable` XML (native since API 21), from the frame's settings change on.

- *Alternative:* the Material Symbols icon font. Even subset it shows the ligature text until the font has
  loaded, and an icon font is announced by screen readers unless every use hides it.
- *Alternative:* another set such as Lucide (ISC). Fine on the web, but Material Symbols matches the Android
  framework's own controls on the frame.

### 7. Width classes by CSS, one DOM

Three classes: compact below 600 px, medium from 600 px, expanded from 1200 px. The shell has one `<nav>` with one
list of links; CSS places it as the bottom bar (compact), the rail (medium) or the side navigation (expanded). The
section views are the same elements in every class; only CSS grid areas change. Because nothing is created or
destroyed on resize, scroll position, focus and unsaved input stay by themselves.

```
 compact (< 600)        medium (600 - 1199)        expanded (>= 1200)
+----------------+     +----+---------------+     +--------+---------------+--------+
| top bar        |     |rail| top bar       |     | side   | section       | detail |
+----------------+     |    +---------------+     | nav    |               | panel  |
| section        |     |    | section       |     | (brand |               | (only  |
|                |     |    |               |     |  ...   |               |  when  |
+----------------+     |    |               |     | appear-|               |  open) |
| bottom nav     |     |    |               |     |  ance) |               |        |
+----------------+     +----+---------------+     +--------+---------------+--------+
```

The top bar shows the frame's name and the appearance button (language, theme, licences); in the expanded class
both move into the side navigation. Forms (settings, sources, devices) use a centred column of at most 720 px;
the photo grid uses the full width as today (`grid.ts` computes the columns from the width already).

A small `viewport.svelte.ts` exposes the width class from `matchMedia` for the few places that need it in script:
the photo detail (decision 8) and nothing else so far.

- *Alternative:* `{#if}` blocks per class in `App.svelte`. Simple to write, but every crossing of a breakpoint
  would destroy and rebuild the section, which is exactly the state loss the spec forbids.
- *Alternative:* container queries per component. Useful later; the shell decision is about the viewport, and
  media queries are enough for three classes.

### 8. Photo detail: modal below 1200 px, a panel above

A modal `<dialog>` makes everything else inert, which is right for the layer and the sheet but not for the panel,
where the grid must stay usable. The selected photo stays state of `PhotosView`. Below 1200 px `PhotoDetail`
renders inside a `<dialog>` opened with `showModal()`, styled as full-screen layer (compact) or bottom sheet up to
90 % height (medium). From 1200 px it renders as an `<aside>` in the shell's detail area. When the width class
changes while a photo is open, the component switches form and keeps the photo; the grid is untouched, so the
scroll position stays.

Focus: the detail remembers the photo ID of its opener. On close it asks `VirtualGrid` to bring that tile into view
and focuses it, because the original tile element may have been recycled while scrolling. `ConfirmDialog` stays
modal in every class.

### 9. Theme store like the language store

`theme.svelte.ts` mirrors `i18n.svelte.ts`: `auto`, `light` or `dark`, stored under its own `localStorage` key, with
memory as the fallback when storage fails. It sets `data-theme` on `<html>` (none for `auto`) and updates
`<meta name="theme-color">`. `main.ts` applies the stored choice before `mount()`; an inline script in
`index.html` would be earlier, but the CSP forbids it, and before mounting the page is empty, so at most the
background colour can flash. Language and theme sit together in the appearance menu, which replaces the `<select>`
in the header.

### 10. Touch targets, focus, safe areas, motion

- **Touch targets.** A `--target: 44px` token. Buttons, links styled as buttons, `<select>` and inputs get
  `min-height: var(--target)`; icon buttons are 44 × 44; checkboxes and switches sit in a label row that is the
  target. Text links inside sentences become buttons where they trigger an action ("Show" in the skipped files).
- **Focus.** `:focus-visible` with a 2 px ring in `--focus` and a 2 px offset, checked at 3:1 against `bg` and
  `surface`.
- **Safe areas.** `viewport-fit=cover` in `index.html`; the bottom bar adds `env(safe-area-inset-bottom)`, the top
  bar `env(safe-area-inset-top)`, and in landscape the shell pads left and right by the insets.
- **Motion.** `--motion-short: 150ms`, `--motion-medium: 250ms` and one easing curve. A
  `@media (prefers-reduced-motion: reduce)` block sets both durations to `0ms`, so every transition that uses
  the tokens stops moving; components do not write durations of their own. On the frame, Android's animator
  duration scale already applies to framework animators.

### 11. Contrast as a unit test

`tokens.test.ts` reads `tokens.css`, resolves each `light-dark()` pair and computes the WCAG contrast for a fixed
list of pairs in both themes: `text`, `muted`, `danger` and `warning` on `bg`, `surface` and `surface-2` (4.5:1);
`on-accent` on `accent` (4.5:1); `outline` and `focus` on `bg` and `surface` (3:1). For the frame it composites the
`frame_panel` colour over white, the worst photo, and checks `frame_text` and `frame_text_muted` on the result
(decision 2), and `ds_text`, `ds_muted` and `ds_accent` on `frame_background`. A failure names the pair and the
ratio.

### 12. Licences

`webadmin/public/licenses/` holds `Inter-OFL.txt` and `MaterialSymbols-LICENSE.txt`. Vite copies `public/` into
`dist/` unchanged, so the frame serves them at `/licenses/…` (`txt` is mapped). `public/` becomes an input of
`buildWebadmin`. The appearance menu links to them. The APK carries the same two files under `assets/licenses/`.

### 13. Tests

- Unit (Vitest): token parsing and contrast (decision 11), theme store (choice, storage failure, `data-theme`),
  width class store.
- Playwright, Chromium, Firefox and WebKit as today: `layout.spec.ts` grows to 360, 800 and 1280 px (navigation
  form and current section by role and position, no horizontal scrolling in all five sections, including Sources,
  which the current test misses); rotation by `setViewportSize` with an open detail and with unsaved input; a
  touch-target audit that measures every control or its label at 360 px; Escape on the detail returns focus to the
  tile; `emulateMedia({ colorScheme })` with and without a stored choice; `emulateMedia({ reducedMotion })` and the
  computed transition duration of the detail; `document.fonts.check("16px Inter")`; the existing offline test
  keeps guarding against foreign requests, now including the font.
- Android: a Robolectric test that `FrameType` loads all four typefaces from assets, and `ClockOverlayTest`
  extended by the tabular figures.
- `DEVICE_TEST.md`: Inter and nothing clipped on the three frames, landscape and portrait; the web UI on a phone
  (home indicator, landscape) and on the Tab S7 rotated with an open photo detail.

## Risks / Trade-offs

- [Inter runs wider than Roboto; the hint bar at 1024 × 600 may overflow] → Emulator screenshots at 1024 × 600
  and 1920 × 1200 as in the earlier changes, then the devices. If the row overflows, the name and address column
  ellipsizes before the code or the QR code shrinks.
- [Custom fonts on old `Typeface.createFromAsset` can fail on some vendors' Android 5 builds] → Fallback to the
  styles' `fontFamily`, a log line, and the Robolectric test; the Lenovo row in `DEVICE_TEST.md` is the real proof.
- [`light-dark()` in an older Safari would drop the colour declarations] → Outside the supported range. Documented
  in the "Supported browsers" requirement, which this change keeps.
- [Width-dependent form of the detail means two DOM shapes for one component] → The photo state lives in
  `PhotosView`, the component is stateless apart from focus, and the rotation test covers the switch both ways.
- [The four changes merged before this one put albums, the section editor, the schedule and captions into
  `AlbumsView`, `SectionEditor`, `SettingsView`, `PhotoDetail` and the frame] → Their behaviour stays; their
  Playwright tests (`section.spec.ts`, `captions.spec.ts`, …) keep their assertions and change only where a
  selector depended on the old layout.
- [Regular expressions in `checkDesignTokens` miss a declaration written in another shape] → The check also fails
  when a `ds_*` resource has no matching token, so a missed declaration shows up as a missing token, not as silence.

## Migration Plan

Nothing is stored or migrated. Browsers that chose a language keep it; the theme starts as `auto`. Rollback is a
revert of the web UI and the frame's resources; the API is unchanged, so an older web UI works with a newer frame
and the other way round.
