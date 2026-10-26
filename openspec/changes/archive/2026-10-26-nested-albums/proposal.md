# Proposal

## Why

An album is only the first folder below the photo folder, so `holiday/2024/may/beach.jpg` is in the album `holiday` and the folder structure a user already keeps on Nextcloud, a share or the USB folder is flattened into one level. A user who adds a folder tree wants to see that tree as albums and sub-albums, and wants to hide a whole branch while still showing a part of it ("holiday" off, "holiday/2024" on).

## What Changes

- **Albums are folder paths.** A photo's album is the full folder path below the folder of its source (`holiday/2024/may`), not the first part of it. Photos directly in that folder still have no album. Every folder on the way is an album too (`holiday`, `holiday/2024`), even when it holds no photo itself. Folders of the same path in different sources are one album, at every depth.
- **Sub-albums inherit visibility, and can be an exception.** An album without a setting of its own takes the state of its parent; a top-level album is visible by default. A sub-album can be shown inside a hidden album and hidden inside a visible one.
- **Switching an album resets everything below it.** Turning an album's switch makes it the setting for its whole branch: the exceptions of all its sub-albums are removed.
- **Counts, covers and the filter include sub-albums.** An album's count and cover cover its whole branch; filtering the photo list by an album shows its sub-albums too, with an option for only the album itself.
- **Upload into a sub-album.** An upload can name an album path of several levels; each level is sanitized as today, and the whole path has a length limit instead of the one-level rule.
- **API.** **BREAKING**: `PATCH /albums/{albumName}` becomes `PATCH /albums` with the album in the body, because an album name now contains `/`; it answers with the whole album list, since the change can reset sub-albums. `Album` gains `overridesParent`; `GET /photos` gains `subalbums`. The web UI is the only client.
- **Web UI.** The albums page becomes a tree with the switch showing the effective state and a mark on exceptions; the album filter and the upload's album choice show paths.
- **Existing frames keep their state.** A hidden album of today stays hidden, and since it now covers its sub-albums by inheritance, no photo changes its visibility after the update.

### Out of scope

- Choosing a folder in the web UI instead of typing it (a matter of the source settings, see `multi-source-share-links`).
- Making the folder chosen for a source an album of its own; albums start below it, as today.
- Renaming, moving or creating empty albums, and album names that differ from folder names.
- Different album trees per source: paths from all sources are merged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-library`: albums from folder paths at every depth, inherited visibility with exceptions and the reset on a change, upload into an album path, and the album filter of the photo list.
- `web-admin`: the albums page as a tree with exceptions, the album filter with sub-albums, and the upload into a sub-album.
- `remote-sources`: the album of a synced photo follows the folder path as in `photo-library` instead of the first folder.

## Impact

- `api/openapi.yaml` first: `PATCH /albums` replaces `PATCH /albums/{albumName}`, `Album.overridesParent`, `subalbums` on `GET /photos`, the album field of the upload; then the Kotlin DTOs and `npm run gen:api`.
- `:core`: `PhotoEntry.album`, the album states in `StoredIndex`/`Library`/`LibraryEdit` with a migration of `hiddenAlbums`, `Library.isVisible`, `PhotoLibrary.setAlbumHidden` and `sanitizeAlbum`, the album and photo routes in `LibraryRoutes`. The display is unaffected: it reads `Library.visible` as before.
- `webadmin/`: `AlbumsView`, the filter in `PhotosView`, `UploadPanel`, `PhotoDetail`, the API client, i18n (`en.json`, `de.json`), unit and e2e tests.
- `docs/DEVICE_TEST.md`: a folder tree copied over USB, and the album page with a large library on the weakest frame.
- Overlaps with `multi-source-share-links`, which moved the requirement "Sync of a folder" from `nextcloud-source` to `remote-sources` and was archived first, so this change's delta is on `remote-sources` (see design.md).
