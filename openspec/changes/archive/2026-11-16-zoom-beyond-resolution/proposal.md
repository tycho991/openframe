# Proposal

## Why

In the section editor most photos can only be zoomed out, not in. `Presentation.zoomRange` caps the zoom at one
original pixel per screen pixel (`max(1, min(4, 1/cover))`), so a photo that has roughly the screen's resolution,
which is typical for photos sent from a phone or a messenger, has a largest zoom of about 1: it can show more of
itself with borders, but no detail. The user wants to zoom into such photos anyway and accepts that the frame then
enlarges the part. The editor should say when that happens, so that the choice is a conscious one.

## What Changes

- The largest zoom of a section SHALL be 4 for every photo, whatever its resolution. Above the zoom at which one
  original pixel covers one screen pixel, the frame enlarges the part to the screen, as it already does today for a
  photo smaller than the screen at zoom 1.
- The section editor SHALL show a warning while the part it shows would be enlarged on the frame, naming how much of
  the screen's resolution the part still has (for example "70 %"). The warning SHALL NOT block saving.
- The editor's zoom buttons SHALL stop at the largest zoom that is still sharp before they zoom past it, so that the
  sharp limit is easy to hit.
- A stored zoom that was clamped to the old limit is now shown as stored. This affects sections set through the API
  above the old limit and sections whose zoom was limited only in the other orientation.

The API contract does not change: `crop.zoom` already accepts values up to 4, and `presentation.zoom` already reports
the zoom the frame uses.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-library`: "Largest zoom": the limit becomes 4 for every photo, and a part beyond one original pixel per screen
  pixel is enlarged instead of being clamped.
- `slideshow-display`: "Section across screen formats": its scenario relied on the old limit; the range a new screen
  allows now only narrows at the lower end (whole photo visible).
- `web-admin`: new requirement "Warning for an enlarged section" in the section editor, and new requirement "Zoom
  buttons stop at the sharp limit".

## Impact

- `:core`: `Presentation.zoomRange` (upper end always `MAX_ZOOM`), a helper for the enlargement factor if the tests need
  it; `RenderCache`, `MediaEngine` and the display are unchanged, because the display size is already capped at the
  window's original pixels and the frame shows it with `FIT_CENTER`.
- `webadmin/`: `lib/presentation.ts` (same change as `:core`, plus the enlargement factor), `SectionEditor.svelte`
  (warning, stop of the zoom buttons), `en.json` and `de.json`.
- `api/fixtures/presentation-cases.json`: cases with a zoom above the old limit change their expected window; new
  cases for small photos zoomed in.
- `ha-openframe`: the card's editor has its own copy of `zoomRange` in `frontend/presentation.js`. It keeps working
  (it can only set fewer zooms than the frame now allows) and needs a follow-up change there to allow zooming in and
  show the warning.
- Device rows in `../openframe-private/DEVICE_TEST.md` for how an enlarged part looks on the Frameo and the Lenovo.
