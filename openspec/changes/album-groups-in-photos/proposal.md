# Proposal

## Why

The photo overview is one flat grid; to see one album the user picks it in a dropdown, and to hide an album from the
slideshow they switch to a separate Albums section. With a library of many albums this means switching back and forth
between two sections and a filter. Grouping the overview by album, with collapsible headings that also carry the
album's slideshow switch, puts photos and albums in one place.

## What Changes

- The photo overview groups the photos under collapsible album headings, nested as the album tree with indentation.
  Photos that lie directly in an album come before the headings of its sub-albums. Photos without an album (directly
  in the photo folder of a source) get a heading of their own at the end, without a switch.
- On the first visit every heading is collapsed; the browser remembers which albums the user opened or closed. A
  collapsed album loads no photos. After an upload the heading of the target album opens, so new photos are seen.
- Each heading shows the album's name, the number of its photos (with its sub-albums) that match the visibility
  filter, and the "Show in slideshow" switch with the exception mark the Albums section has today. After a switch the
  open grids follow at once; with a filter set, the switched heading stays in place and keeps the focus.
- The overview loads an open album's photos only near the screen, at most two photo lists at a time and with a
  smaller first page, so a reload with many open albums does not flood the frame. One measurement for the whole page
  keeps every grid's rows right when headings above it fold or grids above it grow.
- **BREAKING** (interface): the album dropdown and "only this album" are removed from the photo overview. The
  visibility filter and the sort order stay and apply within every album.
- **BREAKING** (interface): the Albums section and its navigation entry are removed; a bookmark to `/albums` opens the
  photo overview.
- API, backwards compatible: `GET /albums` additionally delivers per album the number of visible photos in its branch
  (`visibleCount`) and the counts of the photos without an album (`noAlbum`); `GET /photos` accepts `noalbum=true`
  for the photos without an album.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `web-admin`: "Photo overview" is replaced by a photo overview grouped by collapsible album headings with count and
  slideshow switch, with requirements for the layout of stacked grids, the requests to the frame, the switch, narrow
  screens and keyboard, and uploads; "Albums" moves its switch onto those headings and drops the separate section;
  "Photos from Nextcloud in the overview" keeps Delete away from selected Nextcloud photos in folded albums; the
  quarantine requirement loses its album-list cover clause.
- `photo-library`: new requirements for the album list through the API (visible count, cover) and for the photos
  without an album (counts in the album list, selection in the photo list).

## Impact

- `api/openapi.yaml`: `Album.visibleCount`, `AlbumList.noAlbum` (new schema `AlbumCounts`), the `noalbum` parameter of
  `listPhotos`, the `Album.cover` description. Kotlin DTOs in `LibraryRoutes.kt`, `Library` and `Album` in
  `PhotoIndex.kt`, `LibraryApiTest`, `AlbumTreeTest`, `PhotoListPerformanceTest`; generated types in `webadmin/`.
- `webadmin/`: `PhotosView.svelte` (grouped layout, one measurement, one paged grid per open album),
  `VirtualGrid.svelte` (controlled by props, no own listeners), `lib/grid.ts` (loading only near the screen),
  `lib/photos.ts` (shared request queue, first page size), `lib/albums.ts` (rows, own counts), `lib/api/client.ts`
  (`PhotoQuery.noalbum`, album list with `noAlbum`), `UploadPanel.svelte` (target album to the overview), router and
  navigation in `App.svelte` and `lib/router.svelte.ts`, `AlbumsView.svelte` removed, `de.json`/`en.json`.
- e2e tests that use the Albums section or the album filter (`admin`, `layout`, `photos`, `sources`, `targets`) and
  those that click or wait for tiles, which now start folded (`captions`, `detail`, `layout`, `photos`, `quarantine`,
  `section`, `sources`, `targets`, `update`); `pairing` relies on the unchanged empty-library hint.
- Other clients of `GET /albums` and `GET /photos` (for example the Home Assistant integration) keep working: fields
  and a parameter are only added.
- The private device checklist: the row for the Albums page is replaced by one for the grouped overview.
