# Design

## Context

The APK declares no `android:icon` in `AndroidManifest.xml` and has no `mipmap` or `drawable` resources, so Android
uses its default icon. The app's main activity is both `LAUNCHER` and `HOME`. The reference frames run Android 5.0.1
(Lenovo, x86) and 6.0.1 (Frameo), so adaptive icons (Android 8.0) never appear on them; the paused Galaxy Tab S7 and the
emulator show them. The web UI's `index.html` has no icon link. `public/` is copied to the root of the build and served
by `StaticAssets` with `Cache-Control: no-cache` (only `assets/` is immutable), and the CSP allows `img-src 'self'`. The
header's brand mark in `App.svelte` is a 22 px rounded square with a 3 px accent border. The design tokens live in
`webadmin/src/styles/tokens.css`; the frame mirrors the ones it uses as `ds_*` resources, and `checkDesignTokens` in
`android/build.gradle.kts` fails `./gradlew check` when a mirrored value differs. The icon set of the UI is Material
Symbols Rounded (Apache 2.0). See proposal.md for why.

## Goals / Non-Goals

**Goals:**
- One drawing as the source of every icon file, so that the frame, the web UI and the README cannot drift apart.
- No new build tool and no new Gradle or npm dependency.

**Non-Goals:**
- A wordmark or a logo with lettering. The frame's name stays plain text.
- A light variant of the icon. The dark tile reads on both themes.
- A web app manifest. iOS and Android's Chrome take the home-screen icon from `apple-touch-icon` without one.

## Decisions

### 1. The motive: an open frame with a sun

A rounded square drawn as one thick stroke in the dark theme's accent (`#5fb39b`), open at its top right corner, and a
warm sun (`#e0a050`) in that open corner, on a tile in the dark theme's background (`#151514`). The open corner keeps
the form distinct from a plain photo or app placeholder; the sun gives it a second colour that stays visible as its
own dot at 16 px. It continues the current brand mark of the web UI, so the header changes only slightly. The drawing
is made by hand as SVG path data, not taken from an icon set, so it is the project's own work under MIT and needs no
notice.

The owner chose this motive on 2026-11-16 from drafts at 16, 48 and 160 px (draft "B5"). Its geometry in the 108×108
view box:

- tile: `<rect width="108" height="108" rx="24">` in `#151514`
- frame: `M56 24H38a14 14 0 0 0-14 14v32a14 14 0 0 0 14 14h32a14 14 0 0 0 14-14V52`, stroke `#5fb39b`, width 12,
  round caps and joins, no fill
- sun: `<circle cx="78" cy="30" r="9">` in `#e0a050`

*Alternatives shown to the owner:* the frame alone (the gap nearly closes at 16 px); a closed frame with a dot (reads
as a stop button); the sun in the accent colour or in off-white (less distinct at 16 px); the sun inside the frame
(reads as a loading symbol); the frame moved to the lower left with the sun outside it; a bolder stroke. Rejected
before drafting: a landscape glyph (mountains and sun), the generic "photo" symbol of every icon set, whose details
vanish at 16 px, and a letter mark, which says little about a picture frame and depends on a typeface's licence.

### 1a. The sun's colour is a token of its own

The sun's colour gets its own token `--icon-sun` in `tokens.css` (`#e0a050` in both themes, because the tile is always
dark), mirrored on the frame as `ds_icon_sun` and covered by `checkDesignTokens`. It has today's value of `--warning`
in the dark theme but is not that token: a change of the warning colour must not change the icon, and the icon must
not mean "warning".

### 2. Source and copies

The source is `webadmin/public/favicon.svg`: a 108×108 view box (Android's adaptive icon grid), the tile as a rounded
rectangle, the frame as one `<path>`, the sun as one `<circle>`. The other files derive from it:

- `android/src/main/res/drawable/ic_launcher_frame.xml`: a vector drawable with the frame's path data and the sun
  (as a path), in `@color/ds_accent` and `@color/ds_icon_sun`. `ds_bg` for the tile and `ds_icon_sun` are added to
  `design_tokens.xml` so that `checkDesignTokens` covers them.
- `android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`: an adaptive icon with `ds_bg` as background and the frame
  with the sun as foreground, scaled by 0.76 around the centre so that both lie in the 66 dp safe zone (checked in
  the draft with a round mask). The same drawable is its monochrome layer for themed icons from Android 13 on.
- A legacy launcher icon for Android 5 to 7 (decision 3).
- `webadmin/public/apple-touch-icon.png`: 180×180 px, rendered once with Inkscape by `scripts/render-icon.sh` and
  committed. Its corners are filled with the tile's colour, because iOS expects an opaque square and applies its own
  mask.

A new Gradle task `checkAppIcon`, next to `checkDesignTokens` and run by `check`, compares the vector drawables with
`favicon.svg`: the frame's path data and stroke width, the tile and the sun as the paths of the SVG's `<rect>` and
`<circle>`, and their colour resources. A Vitest test checks that the colours in `favicon.svg` equal `--bg` and
`--accent` of the dark theme and `--icon-sun` in `tokens.css`, that the frame and the sun reach 3:1 against the tile,
and that `apple-touch-icon.png` is 180×180. The PNG's content is not compared; the script is the documented way to
update it.

*Alternative:* render all PNGs in the build. Rejected: it needs a rasteriser in Gradle or npm (a new dependency with
supply-chain cost) for files that change perhaps once.

### 3. Vector launcher icon on Android 5 to 7

`android:icon` points to `@mipmap/ic_launcher`. For API 26 and newer it resolves to the adaptive icon in
`mipmap-anydpi-v26`. Below, it resolves to `mipmap-anydpi/ic_launcher.xml`, a vector drawable with the tile and the
frame (no density-specific PNGs): `VectorDrawable` is part of the framework since API 21, and the app list, the
settings and the HOME chooser load the icon through the app's own resources, so they draw it at their density.
`checkAppIcon` compares its path data too. If Lint or a device shows that a reference frame does not draw it, the
fallback is PNGs per density rendered by the same script, and the device row records it.

*Alternative:* PNGs for `mdpi` to `xxxhdpi` from the start. Rejected for now: five more binary files that the check of
decision 2 cannot compare, for a risk that the device rows settle cheaply.

### 4. Web UI

`index.html` gets `<link rel="icon" href="/favicon.svg" type="image/svg+xml">` and
`<link rel="apple-touch-icon" href="/apple-touch-icon.png">`. The header's `.logo` span becomes an `<img>` of
`favicon.svg` with `alt=""` (the frame's name next to it is the accessible text), 24 px. Both files are under `public/`,
so they keep their names, are served with `no-cache` and revalidate by ETag after an update. The README shows the
SVG from the repository at 96 px above its title.

## Risks / Trade-offs

- [The motive changes later] → Only `favicon.svg` and the path data in two drawables change; `checkAppIcon` and the
  Vitest test catch a copy that was forgotten, and the PNG is regenerated with the script.
- [A vector launcher icon is not drawn on Android 5 or 6] → Device rows on both reference frames before the release;
  fallback per decision 3.
- [Chrome on Android picks a lower-resolution icon for a home-screen bookmark without a manifest] → A device row on a
  phone; a manifest is a small follow-up if the result is blurred.
- [The PNG drifts from the SVG] → Only one PNG; its script lives next to the check, and the README's contribution notes
  name it.

## Migration Plan

None. The icon appears after the update; launchers may cache the old default icon until they reload the app list.
