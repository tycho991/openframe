# Design

## Context

The zoom model comes from the archived change `photo-crop` (design decision 1): cover scale `c = max(S_w/W, S_h/H)`,
fit scale `f = min(S_w/W, S_h/H)`, a zoom `z` means the scale `s = c·z`, and the range is `z_min = f/c` to
`z_max = max(1, min(4, 1/c))`. `Presentation.of` in `:core` and its copy `presentationOf` in
`webadmin/src/lib/presentation.ts` clamp a stored zoom into this range, and both are tested against
`api/fixtures/presentation-cases.json` (read by `core/build.gradle.kts` for the Kotlin test and by
`presentation.test.ts`). The output size of the display image is `w·W·min(s, 1)` × `h·H·min(s, 1)`, so it never has
more pixels than the window of the original, and `SlideRenderer` shows it with `FIT_CENTER`, which enlarges it to the
screen. Enlarging therefore already happens today for a photo smaller than the screen at zoom 1. `SectionEditor.svelte`
zooms by `change(0, 0, factor)`, with the buttons using the factor 1.25 and the wheel and two fingers a continuous one.
See proposal.md for why.

## Goals / Non-Goals

**Goals:**
- One constant decides the largest zoom, in `:core` and the web UI alike.
- The editor tells the user, while zooming, when the frame will enlarge the part and by how much.

**Non-Goals:**
- Better upscaling on the frame (sharpening, a different filter). The frame keeps the bilinear filtering of
  `FIT_CENTER`.
- A warning in the photo list, the detail view or the API. The editor is where the choice is made; the detail view
  already shows the part as the frame does.
- A minimum resolution or a block on saving.
- The editor of the Home Assistant card (`ha-openframe`); it gets its own change.

## Decisions

### 1. `z_max = MAX_ZOOM` for every photo

`zoomRange` returns `z_min..MAX_ZOOM` with `MAX_ZOOM = 4`, in `Presentation.kt` and `presentation.ts`. `z_min ≤ 1`
always holds, so the range is never empty. `Presentation.of` needs no other change: the output size stays capped by
`min(s, 1)`, so a zoom above `1/c` renders fewer pixels than the screen, and the display enlarges them. Memory use only
drops with a larger zoom, because the decoded region gets smaller.

*Alternative:* a limit relative to the sharp zoom, for example `2/c`. Rejected: for a small photo `2/c` can be below
1, so it would still prevent zooming in, which is what the user asked for; 4 is also what the API already validates.

### 2. The enlargement factor is `s = c·z`, the share of the resolution is `1/s`

A part is enlarged when `s > 1`, that is, when one original pixel covers more than one screen pixel. The scale is the
same in both directions, so the share of the screen's resolution the part has is `1/s` along each edge; the editor
shows it rounded to whole percent ("about 42 %"). It is a linear share, not one of the area, because that matches what
the eye sees as sharpness and because "DPI" is a linear measure too. `presentation.ts` gets `enlargement(photo, screen,
presentation)` returning `s`, and `enlargedPercent(s)` the rounded share or `null` while the part is sharp; the editor
already computes the same product for its own scale.

The warning appears for `s > 1 + 0.005`, so rounding in the stored zoom (four decimals) cannot show it at the exact
sharp limit that the zoom buttons stop at. A visible threshold such as 10 % was considered and rejected: the user
asked for a warning whenever the screen's resolution is not reached, and the percentage already tells how much it
matters.

### 3. The warning is a live status line in the editor

Below the viewport, next to the zoom buttons, a line with a warning icon from the existing icon set and the text from
`section.enlarged` ("The frame enlarges this part: it has about {percent} % of the screen's resolution and may look
blurred."), in `role="status"` so a screen reader announces it once when it appears, not on every finger movement. It
follows `shown`, so it also covers "Automatic" and a section that was saved before this change. It does not disable
"Save".

*Alternative:* colouring the viewport's border. Rejected as the only signal: it carries no number and no text for a
screen reader.

### 4. The zoom buttons stop once at `1/c`

`change` gets an optional stop, computed by `zoomStep` in `presentation.ts`: the zoom-in button computes
`next = zoom · 1.25`, and if `zoom < 1/c − ε` and `next > 1/c`, it zooms to exactly `1/c` instead. The zoom-out button does the same from above, so that the sharp limit
can be reached from both sides. Wheel and two fingers pass no stop, so a pinch does not stick. If `1/c` lies outside
`z_min..MAX_ZOOM` (a photo smaller than the screen, or one so large that `1/c > 4`), there is no stop.

*Alternative:* a magnetic snap for all inputs. Rejected: it makes a slow pinch jump, and the buttons are enough to hit
the limit exactly.

### 5. Fixture cases change with the limit

Cases in `presentation-cases.json` whose section zoom lies above the old limit get new expected windows (the stored
zoom instead of the clamped one), and their `scenario` names the new spec scenarios. New cases: the 1600×1200 photo
at zoom 2 on 1920×1200, the 4000×3000 photo at zoom 3, and the portrait/landscape pair of "Section across screen
formats". The Kotlin and the Vitest test read the same file, so both copies change together.

## Risks / Trade-offs

- [Sections stored before this change look different] A zoom above the old limit could only come from the API (the
  Home Assistant card clamps like the old web UI) or from the other orientation, so few photos change. → Named in the
  release notes through the `feat:` commit; no migration, because the stored value is what the user or client chose.
- [Enlarged parts look soft on the Frameo's 1024×600 screen] That is the trade-off the user asked for. → The warning
  with its percentage; a device row records how 50 % and 25 % look on both frames.
- [The Home Assistant card offers a smaller range than the frame] → It stays consistent with itself and only sets
  zooms the frame accepts; the follow-up change in `ha-openframe` closes the gap.

## Migration Plan

None: no stored data changes. A rollback to the previous release shows high zooms clamped again.
