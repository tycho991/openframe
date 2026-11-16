# Tasks

## 1. API contract and core

- [ ] 1.1 In `api/openapi.yaml` add `Album.visibleCount` (required integer), `AlbumList.withoutAlbum` (required object
  with `count` and `visibleCount`) and describe the empty `album` value of `listPhotos`; verify `npm run lint` in
  `webadmin/` (Redocly) passes
- [ ] 1.2 Compute the visible count per album and the counts without an album in the same pass in
  `PhotoIndex.albums`, add the fields to `AlbumDto`/`AlbumListDto`; verify with new cases in `AlbumTreeTest` or
  `LibraryApiTest` for the scenarios "Counts of a branch" and "Photos without an album" (photo-library, "Album list
  through the API"), including photos hidden by the album and by themselves
- [ ] 1.3 Make `GET /photos?album=` with `subalbums=false` return the photos with `album: null` and with sub-albums all
  photos; verify with a `LibraryApiTest` case through `TestHttp(..., checkContract = true)` for "Photos without an
  album" (photo-library, "Photo list through the API") and that `PhotoListPerformanceTest` still passes
- [ ] 1.4 Run `npm run gen:api` and verify `npm run typecheck` passes and `./gradlew :core:check` is green

## 2. Album rows and open state in the web UI

- [ ] 2.1 Add a pure function in `lib/albums.ts` that turns album list, `withoutAlbum`, open set and visibility filter
  into heading rows (path, title, depth, filtered count, open, album) and grid slots, own photos before sub-album
  headings, the heading without an album last, albums with a filtered count of 0 left out; verify with
  `albums.test.ts` cases for nesting, ordering, the filter and the empty library
- [ ] 2.2 Add the open-state store (`openframe.photos.openAlbums` in `localStorage`, every access in `try/catch`,
  unknown paths dropped); verify with unit tests for first visit (all folded), a stored state, a throwing storage and
  pruned paths

## 3. Grouped photo overview

- [ ] 3.1 Let `VirtualGrid` call `onloadmore` only while its box intersects the viewport; verify with a `grid.test.ts`
  or component test that a grid entirely above or below the viewport does not ask for more
- [ ] 3.2 Rebuild `PhotosView.svelte`: headings with fold button, name, count and switch (busy lock and rollback moved
  from `AlbumsView`), one `PhotoPages` + `VirtualGrid` per open album with `subalbums=false`, pages dropped on fold,
  album dropdown and "only this album" removed, visibility filter and sort applied to every open album; verify in the
  browser against the dev server with nested albums and photos without an album
- [ ] 3.3 Keep selection, batch actions, delete confirmation, detail view, `focusPhoto`, library version reload and
  stale-URL refresh working across all open grids; verify with the existing photo e2e tests adapted to open an album
  first
- [ ] 3.4 Add the strings for the heading of photos without an album, the fold labels and the count to `de.json` and
  `en.json`, and remove `photos.filterAlbum`, `photos.allAlbums`, `photos.onlyThisAlbum` and the `albums.*` strings
  no longer used; verify `npm test` (string completeness) and `npm run lint` pass

## 4. Remove the Albums section

- [ ] 4.1 Remove `albums` from `ROUTES` and the navigation in `App.svelte`, map it in `MOVED` to `photos`, delete
  `AlbumsView.svelte` and `nav.albums`; verify with a router unit test that `/albums` opens the photo overview
  ("Old bookmark")

## 5. End-to-end tests

- [ ] 5.1 Rewrite the e2e tests that used the Albums section or the album filter (`admin`, `layout`, `photos`,
  `sources`, `targets`) to use the headings; add e2e cases for "First visit" (no photo request while folded),
  "Remembered state" across a reload, "Count follows the filter", "Album without matching photos", "Nested albums",
  "Photos without an album" and the switch scenarios of "Albums"; verify `npm run e2e` passes with its own
  `OPENFRAME_E2E_PORT`
- [ ] 5.2 Verify the touch target and keyboard checks (`targets.spec.ts`, fold buttons and switches reachable by Tab
  and operable with Enter/Space) and the layouts at 400, 800 and 1280 px pass

## 6. Integration

- [ ] 6.1 Run `./gradlew check` and in `webadmin/` `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e`; all
  green
- [ ] 6.2 Open the web UI against a frame with a library of about 100 photos in nested albums and check folding,
  counts with each visibility filter, the switch and scrolling a large album; note the result in the pull request
