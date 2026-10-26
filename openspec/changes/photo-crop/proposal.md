# Proposal

## Why

The frame either fills the screen and crops every photo around its center, or shows every photo whole with borders. A portrait photo on a landscape frame thus loses more than half of its height in `fill`, often the heads, and a panorama loses half of its width, while `fit` gives every slightly different photo thin borders. The user can neither choose the part of a photo that the frame shows nor zoom in on a detail. `pictureframe` had a crop editor; openframe left it out of its first change on purpose, as a change of its own.

## What Changes

- **A section per photo.** A photo can carry a section: a focus point (`x`, `y` as fractions of the photo) and a zoom. Zoom 1 fills the screen, below 1 the photo gets black borders down to fully visible, above 1 it shows a detail. The section is chosen by hand in the web UI and is kept when the frame's format or screen changes.
- **Fill mode `auto`.** Besides `fill` and `fit`, a third fill mode crops a photo at most 25 % in its long direction and shows the rest with black borders. Photos whose aspect ratio is close to the screen's fill it; portrait photos on a landscape frame and panoramas lose a quarter and get borders for the rest. The focus point of the automatic presentation is the center of the photo. `auto` is the default for new frames; frames that already have stored settings keep their fill mode.
- **The frame shows the section sharply.** The display size is rendered from the section of the original that the screen shows, at screen resolution, so a zoomed detail is as sharp as the original allows. The zoom is limited to one original pixel per screen pixel and at most 4 times.
- **No face detection.** The automatic presentation uses only the aspect ratios; there is no face or object detection.
- **API.** `PATCH /photos/{photoId}` accepts `crop` (`{x, y, zoom}` or `null` to remove it) besides `hidden`, and both become optional. `Photo` gains `crop` and `presentation` (the part of the photo the frame shows, so the web UI can preview it without repeating the rules). `GET /photos/{photoId}/image` gains the size `full`, the uncropped photo for the editor. All of it is additive for clients.
- **Web UI.** The photo detail view shows the photo as the frame shows it and offers "Choose section": an editor with the uncropped photo, a frame of the screen's aspect ratio that can be dragged and zoomed (pinch, buttons), and "Automatic" to remove the section. The settings offer the fill mode `auto`.

### Out of scope

- Face or object detection and any other automatic choice of the focus point.
- A test of the 25 % on the devices with real photos judged by the user. The threshold stays one constant that can be changed later.
- Importing the crops of `pictureframe`.
- Blurred or coloured borders instead of black ones, and animated sections (Ken Burns).
- A section per orientation of the frame: one section serves both formats.
- Editing the original file: the section changes only the rendered sizes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `slideshow-display`: the fill mode gains `auto` and the section per photo; how a section, a fill mode and black borders are shown.
- `photo-library`: the section as part of a photo's state, kept or dropped when the file changes; the display size rendered from the section, the uncropped size `full`; the section and presentation in the photo list; setting and removing a section through the API.
- `frame-settings`: the fill mode `auto` and its default.
- `web-admin`: the detail view as the frame shows the photo, the section editor, and `auto` in the settings.

## Impact

- `api/openapi.yaml` first: `PhotoPatch` with optional `hidden` and `crop`, `Photo.crop` and `Photo.presentation`, the image size `full`, `FillMode` with `auto`; then the Kotlin DTOs and `npm run gen:api`.
- `:core`: `PhotoEntry` with the section, kept in `LocalPhotoSource` and `RemoteSource` across a new version like `hidden`; a pure presentation function next to `RenderSizes`; `RenderCache` with the section in the cache key and the size `full`; `MediaEngine.render` with a source rectangle; `PhotoLibrary` and `LibraryRoutes` for the patch; `FillMode.AUTO` and its default in `Settings`; the JVM fake of `MediaEngine`.
- `:android`: `AndroidMediaEngine.render` decodes only the needed part of the original (`BitmapRegionDecoder`, falling back to a sampled full decode); `SlideRenderer` shows the rendered image centered on black, `BitmapFit` reduces to scaling down.
- `webadmin/`: `PhotoDetail`, a new section editor, `SettingsView`, the API client, i18n (`en.json`, `de.json`), unit and e2e tests.
- `docs/DEVICE_TEST.md`: sharpness of a zoomed detail, memory with a 48 MP photo zoomed in on the weakest frame.
- Overlaps with `nested-albums` and `multi-source-share-links`, which change `PhotoEntry`, `StoredIndex`, `LocalPhotoSource` and `RemoteSource` as well; this change is implemented after both (see design.md).
