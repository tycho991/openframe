# Proposal

## Why

The photo overview is one flat grid; to see one album the user picks it in a dropdown, and to hide an album from the
slideshow they switch to a separate Albums section. With a library of many albums this means switching back and forth
between two sections and a filter. Grouping the overview by album, with collapsible headings that also carry the
album's slideshow switch, puts photos and albums in one place.

## What Changes

- The photo overview groups the photos under collapsible album headings, nested as the album tree with indentation.
  Photos that lie directly in an album come before the headings of its sub-albums. Photos without an album (directly
  in the photo folder of a source) get a heading of their own at the end.
- On the first visit every heading is collapsed; the browser remembers which albums the user opened or closed. A
  collapsed album loads no photos.
- Each heading shows the album's name, the number of its photos (with its sub-albums) that match the visibility
  filter, and the "Show in slideshow" switch with the exception mark the Albums section has today.
- **BREAKING** (interface): the album dropdown and "only this album" are removed from the photo overview. The
  visibility filter and the sort order stay and apply within every album.
- **BREAKING** (interface): the Albums section and its navigation entry are removed; a bookmark to `/albums` opens the
  photo overview.
- API, backwards compatible: `GET /albums` additionally delivers per album the number of visible photos in its branch,
  and the counts of the photos without an album; `GET /photos` accepts an empty `album` for the photos without an album.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `web-admin`: "Photo overview" is replaced by a photo overview grouped by collapsible album headings with count and
  slideshow switch; "Albums" moves its switch onto those headings and drops the separate section; the quarantine
  requirement loses its album-list cover clause.
- `photo-library`: the photo list through the API can select the photos without an album; a new requirement describes
  the album list through the API, including the visible count, the counts without an album and the cover.

## Impact

- `api/openapi.yaml`: `Album.visibleCount`, `AlbumList.withoutAlbum`, the `album` parameter of `listPhotos`
  (empty value). Kotlin DTOs and `PhotoIndex` in `:core`, generated types in `webadmin/`.
- `webadmin/`: `PhotosView.svelte` (grouped layout, one paged grid per open album), `VirtualGrid.svelte` (several grids
  on one page), `lib/albums.ts`, router and navigation in `App.svelte`, `AlbumsView.svelte` removed, `de.json`/`en.json`.
- e2e tests that use the Albums section or the album filter (`admin`, `layout`, `photos`, `sources`, `targets`).
- Other clients of `GET /albums` (for example the Home Assistant integration) keep working: fields are only added.
