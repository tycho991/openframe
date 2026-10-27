# Proposal

## Why

A photo on the frame says nothing about itself: who is in it, what the occasion was or where it was taken. `pictureframe` had a comment per photo and a place name as a caption, the place name resolved over Nominatim, the only feature there that called an outside service. openframe left both out so far; this change brings them back, with the place name worked out on the frame itself.

## What Changes

- **A comment per photo.** Every photo can carry a comment of up to 500 characters, written in the web UI and stored on the frame as part of the photo's state, like `hidden`. Photos of remote sources can carry one as well; the source stays read only. The comment is kept when the file changes. Descriptions embedded in the file (EXIF, XMP, IPTC) and Nextcloud comments are not read.
- **The location of a photo.** The library reads the GPS position from the photo's EXIF data and keeps it in the index. Photos already in the index get it once, in the background, from the file on the frame. Remote photos whose original is not on the frame get it with their next download.
- **Place names without a network.** The frame turns the position into "place, country" offline, from the GeoNames dataset `cities500` (about 237,000 places) shipped in the APK. Place names come in German from a German name pack. In English and every other language they are GeoNames' own names. Country names come from the platform. The data has a base and one name pack per language, so a later UI language ships its own pack with its release. Nothing is downloaded at runtime.
- **A caption on the frame.** The frame shows the comment over the photo, optionally prefixed by the album's title (the last level of its folder path), and optionally the place name in a line below. The caption has its own corner. When it shares the corner with the clock, the two are stacked, with the clock in the corner. It steps aside while the hint is up, changes together with the photo, and is legible on bright and dark photos. The capture date is not shown.
- **Settings.** A new `caption` group: album title on or off, place on or off (both off by default), and the corner (default bottom left). The corners of the clock and of the caption share one schema.
- **API.** `PATCH /photos/{photoId}` accepts `comment` (a string, or `null` to remove it). `Photo` gains `comment` and `location` (`lat`, `lon` and `place`, the place in the frame's language). `Settings` gains `caption`. All of it is additive for clients.
- **Web UI.** The photo detail view gains a comment field and shows the place and the coordinates. It shows no map, because map tiles would come from an outside server. The settings get the caption group. The GeoNames attribution (CC BY 4.0) ships with the data and is shown in the web UI.

### Out of scope

- Comments or descriptions from the file or from Nextcloud, and writing a comment back into the file or to a source.
- Changing the place by hand. A different place can be written into the comment.
- The capture date in the caption.
- A size setting for the caption.
- Downloading name packs at runtime, and online geocoding (Nominatim or Nextcloud's place metadata).
- A map in the web UI.
- Importing the comments of `pictureframe`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-library`:
  - The GPS position as metadata, read once for photos already in the index.
  - The comment as part of a photo's state: kept when the file changes, set and removed through the API.
  - The offline place name with its languages.
  - Comment and location in the photo list.
- `slideshow-display`: the caption over the photo, made of the comment, the album title and the place, with its corner, stacking with the clock and stepping aside for the hint.
- `frame-settings`: the caption settings and their defaults.
- `web-admin`: editing the comment, the place and the coordinates in the detail view, and the attribution of the place data.

## Impact

- **`api/openapi.yaml` first:**
  - `PhotoPatch.comment`, `Photo.comment`, `Photo.location`.
  - A `Caption` object in `Settings` and `SettingsPatch`.
  - `ClockPosition` becomes a shared `Corner` schema (the wire format is unchanged).
  - Then the Kotlin DTOs and `npm run gen:api`.
- **`:core`:**
  - `PhotoEntry` with `comment`, the GPS position and the version of its metadata. The comment is kept like `hidden` in `LocalPhotoSource` and `RemoteSource`, and `RemoteSource` takes the metadata over on a download with the same ETag.
  - `MediaEngine` reads the GPS position in `probe` and through a new metadata-only read without a probe decode, implemented in the JVM fake as well.
  - A throttled background job fills in the metadata of existing photos.
  - A place lookup in its own package: a grid of points, a name pack per language, and country names from `Locale`.
  - A pure function that builds the caption from photo, settings and language, delivered to the display with the slide state.
  - `Settings` with `caption`, `PhotoLibrary` and `LibraryRoutes` for the patch.
- **`:android`:**
  - `AndroidMediaEngine` reads `ExifInterface.getLatLong`.
  - A caption overlay next to `ClockOverlay` in `activity_display.xml`, with the rules for corner, stacking and hint, and the fade with the photo.
- **Data and build:**
  - A script that builds the place base and the German name pack from GeoNames dumps.
  - The generated files and the attribution are committed under `core/src/main/resources/places/`, about 2.8 MB.
- **`webadmin/`:**
  - `PhotoDetail` with the comment field, place and coordinates.
  - `SettingsView` with the caption group.
  - The attribution, the API client, i18n (`en.json`, `de.json`), unit and e2e tests.
- **`docs/DEVICE_TEST.md`:**
  - Legibility of the caption on bright and dark photos.
  - A stacked corner with the clock at 300 % in portrait.
  - Duration of the background job on the weakest frame.
  - Memory of the place lookup on the weakest frame.
- **Ordering:** implemented after `nested-albums` (album paths), `multi-source-share-links` and `photo-crop`, which change `PhotoEntry`, `PhotoPatch`, `RemoteSource` and `PhotoDetail` as well. The caption uses the type and colours of `design-system` when that change is in.
