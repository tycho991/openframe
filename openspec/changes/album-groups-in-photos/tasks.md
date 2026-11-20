# Tasks

## 1. API contract and core

- [x] 1.1 In `api/openapi.yaml` add `Album.visibleCount` (required integer), the schema `AlbumCounts` (`count`,
  `visibleCount`, both required, `additionalProperties: false`), `AlbumList.noAlbum` (required `AlbumCounts`) and the
  boolean query parameter `noalbum` of `listPhotos` (`true`: only photos without an album; `noalbum=true` together
  with `album` is 400; `noalbum=false` changes nothing; no effect of `subalbums`); describe `Album.cover` as the newest photo that is not quarantined; verify `npm run lint`
  in `webadmin/` (Redocly) passes
- [x] 1.2 Build one private lazy summary in `Library` (`PhotoIndex.kt`) in one pass over the photos, from which
  `albums` and a new `noAlbum` read: effective album state from `AlbumPaths.isHidden(albumStates, …)` cached per
  path, never `isVisible`; add `visibleCount` to `Album` and the fields to `AlbumDto`/`AlbumListDto` in
  `LibraryRoutes.kt`; verify with new `AlbumTreeTest` cases for "Counts of a branch", "Hidden by the album and by the
  photo", "Quarantined photo" (photo-library, "Album list through the API") and "Counts without an album"
  (photo-library, "Photos without an album in the API"), plus a check that `visibleCount` equals the number of photos
  of the branch for which `isVisible` is true
- [x] 1.3 Handle `noalbum` in `GET /photos` (`booleanParam`, `true` keeps only `album: null`, `noalbum=true` with
  `album` is `400 bad_request`, `noalbum=false` changes nothing); verify with `LibraryApiTest` cases through
  `TestFrame` for "List the photos without an album" (with a photo of a second source, a hidden one with
  `visible=false`, and paging), "Without an album and an album at once" (`noalbum=true&album=holiday` is 400), and
  that `noalbum=false` with and without `album` answers as the same request without `noalbum`
- [x] 1.4 Add to `PhotoListPerformanceTest` a `GET /api/v1/albums` case at 10,000 photos in four levels with the same
  budget as the pages; if signing the covers of thousands of albums misses it, note the
  measured time in design.md before changing the budget; verify the test passes
- [x] 1.5 Run `npm run gen:api` and verify `npm run typecheck` passes and `./gradlew :core:check` is green

## 2. Client, album rows and open state in the web UI

- [x] 2.1 In `lib/api/client.ts` add `noalbum?: boolean` to `PhotoQuery`, and let `albums()` and `setAlbumHidden()`
  return the whole album list (`albums` and `noAlbum`); adapt the callers; verify with `client.test.ts` cases that
  `{ noalbum: true, visible: false }` sends `?noalbum=true&visible=false` and that both album calls keep `noAlbum`
- [x] 2.2 Add a pure function in `lib/albums.ts` that turns album list, `noAlbum`, open set, visibility filter and a
  pinned path into heading rows (path, title, depth, count for the filter, own count as count minus the direct
  children's counts, open, album) and grid slots: own photos before sub-album headings, the heading without an album
  last and without a switch, albums with a filtered count of 0 left out unless they are the pinned path or one of its
  ancestors, no grid slot for an own count of 0; verify with `albums.test.ts` cases for nesting, ordering, own
  counts, the filter, the pinned path, a pinned `holiday/2024` under "only hidden" whose parent `holiday` without own
  photos has dropped to 0 (both stay), and the empty library
- [x] 2.3 Add the open-state store (`openframe.photos.openAlbums` in `localStorage`, `""` for photos without an
  album, every access in `try/catch`, no pruning of unknown paths, capped at 1,000 with the oldest dropped, a
  function that opens a path with its ancestors); verify with unit tests for first visit (all folded), a stored
  state, a throwing storage, an unknown path that is kept ("Source briefly unreachable") and the cap

## 3. Grid layout and requests

- [x] 3.1 Make `VirtualGrid` controlled (`top`, `width`, `scrollY`, `viewport` as props, no `ResizeObserver` and no
  window listeners of its own) and name both thresholds in `lib/grid.ts`: `nearEnd` only when the viewport extended
  by the overscan rows overlaps the grid's box, and the first page when the reserved box of `min(ownCount, 60)`
  tiles comes within one viewport height of the screen; verify with `grid.test.ts` cases for each threshold (a grid
  just beyond the overscan rows does not ask for more, one inside them does; an unloaded grid just beyond one
  viewport height is not requested, one inside it is), the reserved height, and that at 1280 × 800 px a first page
  of 60 at the top reports `nearEnd` at once (page 2 follows, which the e2e request counts allow for)
- [x] 3.2 Add the shared request queue to `lib/photos.ts`: at most two `GET /photos` at a time; it holds single page
  requests (a `reload()` or `refreshStale()` queues its pages one after another, not as one chain); priority (grids
  on screen first) decided when a slot takes a request; requests of a folded grid or of an outdated query dropped;
  a generation number per `PhotoPages`, increased by `setQuery()`, fold and `reload()`, so a late answer of an older
  generation is discarded. Derive the limit per page in `fetchPage` (no cursor: 60, otherwise 200); verify with
  `photos.test.ts` cases with a fake API that counts requests in flight and checks the `limit` of each request for
  first pages, `loadMore`, `reload()`, `refreshStale()` and `onImageError()` on the first and on a later page, that a
  folded grid's queued requests are dropped, and that two quick `setQuery()` calls whose first answer arrives last
  leave the pages of the second query

## 4. Grouped photo overview

- [x] 4.1 Rebuild `PhotosView.svelte`: count line above the headings, headings with fold button, name, count and
  switch (busy lock and rollback moved from `AlbumsView`, none on the heading without an album), one `PhotoPages`
  + `VirtualGrid` per open album with own photos (`subalbums=false`, or `noalbum` for the heading without an
  album), pages dropped on fold, album dropdown and "only this album" removed, visibility filter and sort applied to
  every open album, the empty-library hint kept; verify in the browser against the dev server with nested albums,
  an album without own photos and photos without an album
- [x] 4.2 Add the one measurement in `PhotosView`: the list's top read with one `getBoundingClientRect()` per scroll
  frame and on window resize, the grids' offsets relative to the list from one `ResizeObserver` on the list and every
  grid plus a re-measure after row changes, a grid's `top` as their sum; move `focusPhoto` there with a re-measure
  first; verify with e2e cases for "Fold a heading above an open album" and "Grid above grows" (web-admin, "Grids
  follow the layout"), a case that closes the paired banner above the overview (and one that shows an error above
  the list) and then scrolls, checking that the grid shows the thumbnails of the rows on screen, and the existing
  "Close the photo detail" case with two open albums
- [x] 4.3 After a switch, replace the album list from the answer, call `refreshState()`, and pin the switched heading
  and its ancestors while a filter is set until filter or sort change; verify with e2e cases for "Grids follow the switch" and "Switch
  with a filter" (focus on the switch afterwards)
- [x] 4.4 Keep the selection as a map from ID to source, so folding keeps it and "can delete" never offers Delete for
  a Nextcloud photo; keep batch actions, delete confirmation, detail view, library version reload, settings version
  reload, stale-URL refresh and image-error reload working across all open grids; verify with an e2e case for
  "Selection in a folded album" (web-admin, "Photos from Nextcloud in the overview") and the photo e2e tests adapted
  in 6.1
- [x] 4.5 Let `UploadPanel` pass to `onuploaded` the album paths from the answers of the successful uploads
  (`UploadResult.photos[].album`, `null` for the heading without an album), not the typed path, and nothing if every
  upload failed; open those headings with their ancestors; verify with e2e cases for "Upload on the first visit",
  "Upload into a folded sub-album", an upload into a new album typed with a leading space and dot that opens the
  cleaned album, and an upload that fails completely and opens nothing
- [x] 4.6 Headings with `role="heading"` and `aria-level` = depth + 2 holding the fold button, the switch next to the
  heading element and not inside it, a content container with a stable `id` below every heading (empty while
  folded), fold button with `aria-expanded` and `aria-controls` pointing to it, names of fold button and switch with
  the album's path, indentation capped at depth 4, long names
  wrapping; verify with the checks of 6.2
- [x] 4.7 Add the strings for the heading without an album (`photos.noAlbumHeading`), the fold labels with the path
  and the count to `de.json` and `en.json`, and remove `photos.filterAlbum`, `photos.allAlbums`,
  `photos.onlyThisAlbum`, `nav.albums` and the `albums.*` strings no longer used; verify `npm test`, `npm run lint`
  and a search of `src/` that every removed key is unused and every new key is used

## 5. Remove the Albums section

- [x] 5.1 Remove `albums` from `ROUTES` and the navigation in `App.svelte`, map it in `MOVED` to `photos`, delete
  `AlbumsView.svelte`; verify with a router unit test that `/albums` shows the photo overview and that the address
  is replaced with `/photos` ("Old bookmark")

## 6. End-to-end tests

- [x] 6.1 Add a shared `openAlbum(page, path)` helper to `e2e/helpers.ts` and adapt every e2e spec that uses the
  Albums section or the album filter (`admin`, `layout`, `photos`, `sources`, `targets`) or clicks or waits for
  tiles (`captions`, `detail`, `layout` "Fotos" check, `photos`, `quarantine`, `section`, `sources`, `targets`,
  `update`; `photos` "Large library" opens `viele` and keeps its limit of 150 thumbnails); check that `pairing`
  still finds the empty-library hint. Add e2e cases for "First visit" (no photo request while folded), "Remembered
  state" across a reload, "Count follows the filter", "Album without matching
  photos", "Nested albums", "Photos without an album", "Album without photos of its own", "Reload with many open
  albums" (requests in flight counted with `page.on("request")`), "Empty library" and the switch scenarios of
  "Albums"; verify `npm run e2e` passes with its own `OPENFRAME_E2E_PORT`
- [x] 6.2 Adapt `layout.spec.ts` and `targets.spec.ts` to audit the photo overview with open nested headings instead
  of the Albums section: no horizontal scrolling at 360, 390, 800 and 1280 px and in landscape at 844 × 390 px, an
  album five levels deep with a 60-character name at 360 px ("Deep album on a phone"), touch targets of fold buttons
  and switches at 360 px, and fold buttons and switches reachable by Tab and operable with Enter and Space ("Fold
  with the keyboard"); verify both specs pass

## 7. Integration

- [x] 7.1 Run `./gradlew check` and in `webadmin/` `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e`; all
  green
- [x] 7.2 Title the implementation's pull request `feat!: …`, so that release-please lists the removed Albums section
  and album filter as a breaking change (with `bump-minor-pre-major` still a minor step)
- [ ] 7.3 Run the row for the grouped photo overview in the private device checklist on a frame and enter the result
  there, not in this repository
