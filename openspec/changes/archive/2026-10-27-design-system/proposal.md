# Proposal

## Why

The web UI has grown one component at a time: a header with a row of links, one column of content and a single media
query that only switches to dark colours. It does not use the width of a tablet or a desktop, has no navigation that
fits a thumb on a phone, and its look is not shared with the frame, whose texts use the system font and colours of
their own. The user wants both surfaces to be modern and to adapt to the size and orientation of the screen, and the
next changes put a full admin interface on the frame itself (see Impact). They need a common design language first,
so that the frame does not get a third look.

## What Changes

- **A shared design system for the web UI and the frame.** One set of design tokens (colours of a light and a dark
  theme, accent, radii, spacing and type scale), the typeface Inter and one icon set (Material Symbols). The web UI is
  the source of the tokens; the frame mirrors the values it uses in its resources, and `./gradlew check` fails when
  they differ.
- **Inter everywhere, served locally.** The web UI ships Inter as a local web font, the frame ships static Inter
  weights in the APK. No font or icon is loaded from a foreign server. The licences of font and icons ship with them.
- **The frame's existing texts switch to Inter and the token colours:** the hint bar, the empty state, the clock
  (with tabular figures, so the time does not jitter) and the photo caption. Text over a photo or over the hint bar's
  translucent panel stays white with its shadow, which no token colour can match on a bright photo. The device menu
  keeps its system dialog until the frame's own admin interface replaces it.
- **Responsive web UI with three width classes.** Below 600 px a bottom navigation bar with icons and labels; from
  600 px a navigation rail; from 1200 px a side navigation with labels. Detail views open as a full-screen layer on a
  phone, as a sheet on a tablet and as a panel next to the list on a wide screen. Rotating a device switches the
  layout without losing the open view, the scroll position or unsaved input.
- **Modern look and operation.** Cards and grouped sections, consistent icons, touch targets of at least 44 × 44 px,
  visible keyboard focus, contrast after WCAG 2.2 AA in both themes, short motion that respects reduced motion, and
  the safe areas of phones with a notch.
- **Theme choice.** The web UI follows the system's light or dark scheme unless the user chooses one; the choice
  applies per browser, like the language. Language and theme move from the header into an "appearance" menu. The
  frame stays dark.

### Out of scope

- The admin interface on the frame (gear icon, settings, sources, devices, photos and albums on the device, input
  through the phone instead of an on-screen keyboard). It follows in changes of its own, see Impact.
- New functions in the web UI. Every view keeps its content and behaviour; only layout, look and navigation change.
- Changes to the API, to the frame's settings or to the slideshow itself (photos, transitions, clock position and
  size stay as they are).
- Brand work beyond the existing accent colour (a logo, a new name).

## Capabilities

### New Capabilities

- `visual-design`: the design language shared by the web UI and the frame: design tokens and their single source,
  the typeface Inter, the icon set, colour themes, contrast, motion, and shipping font and icons locally with their
  licences.

### Modified Capabilities

- `web-admin`: the supported widths become three width classes with their navigation and detail views; rotation
  keeps the state; touch targets, keyboard focus and safe areas; the theme choice per browser next to the language.

## Impact

- `webadmin/`: a token stylesheet and the reworked `app.css`, an app shell in `App.svelte` with the three
  navigations and an appearance menu, a theme store next to `i18n.svelte.ts`, an icon module, every component moved
  to tokens and icons (hard-coded colours today in `QrCode`, `ConfirmDialog`, `PhotoDetail`, `SectionEditor`,
  `VirtualGrid`, `UploadPanel`, `SourcesView`, `SourceCard`), `PhotoDetail` as layer, sheet or panel, `en.json`
  and `de.json`, unit tests and the Playwright tests (`layout.spec.ts` at 360, 800 and 1280 px, rotation, theme, touch targets).
  Inter as a `woff2` file under `src/`, the licence texts under `public/licenses/`, which becomes an input of
  `buildWebadmin`.
- `:android`: static Inter weights in `assets/fonts/` with their licence, a small helper that applies them (XML
  `fontFamily` cannot reference assets before API 26), colours and dimensions in `res/values/` named after the
  tokens, `DisplayActivity`, `ClockOverlay` and `CaptionOverlay` using them.
- Build: a Gradle check in the style of `checkNoAndroidImports` that compares the mirrored token values with the web
  UI's token stylesheet, wired into `check`.
- `:core`: none. `StaticAssets` already serves `woff2` and `svg`, and its CSP allows `font-src 'self'`.
- `docs/DEVICE_TEST.md`: the hint bar, the empty state, the clock and the caption with Inter on the Lenovo
  (1920×1200), the Frameo (1280×800) and the Galaxy Tab S7, landscape and portrait, nothing clipped (Inter runs wider
  than Roboto); the web UI on a phone and on the Tab S7 in both orientations.
- `nested-albums`, `photo-crop`, `display-schedule` and `photo-captions` were merged before this change. It moves
  their web parts (albums, the section editor, the schedule in the settings) and the frame's caption onto the design
  system without changing what they do; their specs stay as they are, since this change adds requirements and
  modifies only "Supported browsers".
- Follow-up changes on the same design system: the frame's settings with a gear icon in the hint bar and the empty
  state (replacing the device menu, which the `display-schedule` spec names, so that change adapts it), then
  sources and paired devices on the frame, then photos and albums on the frame. Free text there is entered on a
  phone through a QR code (the existing pairing link with a target), never with an on-screen keyboard.
