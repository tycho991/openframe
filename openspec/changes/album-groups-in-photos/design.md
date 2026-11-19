# Design

## Context

See proposal.md for the motivation. Today (main at 0.10.0):

- `PhotosView.svelte` holds one `PhotoPages` (`lib/photos.ts`: cursor paging, 200 per page, signed URLs renewed after
  50 minutes) and one `VirtualGrid`. The grid positions tiles absolutely and computes the visible range with
  `gridLayout` (`lib/grid.ts`) from `window.scrollY` minus its own `top`. It measures `top` only from its own
  `ResizeObserver` and its own window `scroll`/`resize` listeners. That holds for one grid whose top never moves; with
  several grids, folding a heading or a grid above growing moves a grid without resizing it, so its `top` goes stale
  until the next scroll event, and `focusPhoto` scrolls to a wrong place.
- `gridLayout` reports `nearEnd` for any grid whose clamped row range reaches its end, also for a grid that lies
  entirely above or below the viewport, or one of height 0.
- `GET /photos` filters with `album` (+ `subalbums=false` for only the album itself) and `visible`; `total` is the
  count of the filtered list. `album=""` matches nothing today (`AlbumPaths.inBranch` needs a non-null album). The
  web client's `query()` (`lib/api/client.ts`) drops parameters whose value is `""`, so the client could not send an
  empty album anyway. The contract check of the tests validates response bodies, not query parameters.
- `GET /albums` lists every album with a photo in its branch, ancestors included, from the lazy `Library.albums` in
  `core/.../library/PhotoIndex.kt`, with `count` (branch, regardless of visibility), `hidden`, `overridesParent` and
  `cover`. Photos without an album appear in no entry. `Library.isVisible` reads `albums[...]` for the album's state,
  so it cannot be called while `albums` is being built.
- The client's `albums()` and `setAlbumHidden()` return only the `albums` array of the answer.
- `AlbumsView.svelte` renders the tree from `albumTree()` with a fold button, cover, count, exception badge and the
  "Show in slideshow" switch with a one-at-a-time `busy` lock and a rollback on error; `PATCH /albums` answers with the
  whole list and changes the library version. The web UI polls the versions every 10 s (`STATE_POLL_MS`);
  `refreshState()` in `session.svelte.ts` reads them at once.
- `PhotosView` decides "can delete" by looking the selected IDs up in the loaded items and treats an ID it does not
  find as local. It reloads the loaded pages on a library version change and on a settings version change (display
  URLs depend on fill mode and orientation), and renews stale pages every 60 s.
- Uploads go to "No album" unless the user picks one (`UploadPanel.svelte`).

## Goals / Non-Goals

**Goals:**

- One page shows the tree of headings and, below each open heading, a paged virtual grid of the album's own photos.
- A folded album costs no photo request; an open album costs a request only when its grid comes near the screen.
- The frame sees at most two photo list requests at a time from one overview, also after a reload with many albums
  open; a large open album costs no more than the flat grid does today.
- Counts on the headings follow the visibility filter without one request per album.
- Every grid shows the right rows after any layout change, with one measurement for the whole page.

**Non-Goals:**

- Virtualizing the list of headings itself. Folded headings are one row each; a few hundred albums are fine.
- Cover images on the headings (the user chose count and switch only). The API keeps `cover` for other clients.
- Moving photos between albums, renaming albums, actions on headings other than the switch.
- An "expand all / collapse all" control.

## Decisions

### 1. One paged grid per open album with photos of its own

Each open heading whose album has photos of its own that match the filter gets its own `PhotoPages` with
`{ album: path, subalbums: false, visible, sort }`, or `{ noalbum: true, visible, sort }` for the heading for photos
without an album, and its own `VirtualGrid`. Sub-albums are separate headings, so their photos never appear twice.

The own count comes from the album list without a request: the album's count for the filter minus the counts for the
filter of its direct children (every folder on a path is an album, so the direct children are all in the list). An
album whose own count is 0 gets no grid and no request. The pages of an album are dropped when it is folded and
loaded from the start when it is opened again; this bounds memory to the open albums.

Alternatives: one flat list sorted by album then date, cut into groups on the client (needs a new sort order in the
API and loads photos of folded albums); grouping only the loaded pages (counts and group boundaries would be wrong
until everything is loaded); one virtual list of headings and tile rows together (a larger rewrite of `VirtualGrid`
for a list of headings that does not need virtualizing).

### 2. One measurement in `PhotosView`, grids without listeners

`VirtualGrid` becomes controlled: it takes `top` (its offset in the page), `width`, `scrollY` and `viewport` as
props and has no `ResizeObserver` and no window listeners of its own. `PhotosView` owns the measurement:

- One window `scroll` listener (passive, read in one `requestAnimationFrame` per frame) sets `scrollY`; one `resize`
  listener sets `viewport`. Offsets in the page do not change while scrolling, so scrolling measures nothing.
- One `ResizeObserver` observes the list of headings and grids, every grid box and the elements above the list
  (toolbar, skipped-files notice, error). Every callback, every change of the rendered rows (after `tick()`) and
  every window resize re-measures, in one frame, `top` and `width` of every open grid with one
  `getBoundingClientRect()` each. A fold, a grid that grows, a notice that appears or a heading that wraps resizes
  one of the observed boxes, so the grids below get their new `top` before the next paint.
- `focusPhoto(id)` lives in `PhotosView`: it re-measures, finds the grid that holds the photo and asks that grid to
  scroll its tile into view and focus it.

Alternatives: per-grid scroll listeners plus a re-measure event (one `getBoundingClientRect` per grid per scroll
event, heavy on phones with many open albums); an `IntersectionObserver` per grid (tells whether a grid is on screen,
not its offset, which the row range needs).

### 3. Loading only near the screen, with a reserved height

`gridLayout` gets the grid's box and reports `nearEnd` only when the viewport plus the overscan rows overlaps it, so
a grid entirely above or below the screen never asks for more. Before its first page a grid reserves the height of
`min(ownCount, 60)` tiles at the current width, so unloaded grids do not stack at height 0 and all count as on
screen; the first page is requested when that box comes within one viewport height of the screen. After loading,
the height is that of the loaded rows, as today; a grid that grows only moves content below it.

### 4. At most two photo list requests at a time, smaller first page

All `PhotoPages` of the overview share one request queue (`lib/photos.ts`) that lets at most two `GET /photos` run
at a time: first pages, `loadMore`, reloads, the stale-URL refresh and the reload after an image error. Requests of
grids that intersect the viewport go first, the rest in page order. The first page of an album has 60 photos, every
further page 200; `reload()` keeps the sizes of the pages it reloads.

Each page makes the frame filter the library and sign the URLs of every photo on it, and a frame with a slow network
stack answers late under many parallel connections (residual risk in the README). With 20 open albums after a
reload this is at most two requests in flight instead of 20, and only for the grids near the screen. Thumbnails are
not queued: the browser loads only those of rendered tiles, as today.

### 5. API: visible counts in `GET /albums`, photos without an album as `noalbum=true`

- `Album.visibleCount`: photos of the branch that are visible by the same rule as `Library.isVisible`: not hidden
  themselves and their album not hidden; quarantine does not count, as for the visibility filter. Hidden count for
  the filter "only hidden" is `count - visibleCount`.
- `AlbumList.noAlbum: AlbumCounts { count, visibleCount }` for the photos with `album: null`, from all sources
  together. `AlbumCounts` has `additionalProperties: false` like the other schemas.
- Counting without recursion: `Library` gets one private lazy summary built in one pass over the photos, from which
  `albums` and the new `noAlbum` read. The pass looks the effective state up with
  `AlbumPaths.isHidden(albumStates, album)`, cached per album path in a local map, and never calls `isVisible`, which
  reads `albums` and would re-enter the lazy initializer. `isVisible` stays a map lookup afterwards. The core class
  `Album` gets `visibleCount`; `AlbumDto` and `AlbumListDto` in `LibraryRoutes.kt` get the new fields.
- `GET /photos?noalbum=true`: a new boolean query parameter, written like `subalbums`. With `true` the list holds only
  the photos with `album: null`; `false` or missing changes nothing. Together with `album` the answer is
  `400 bad_request`; `subalbums` has no effect with it. The client sends it as `noalbum: true` in `PhotoQuery`,
  which `query()` keeps (it drops only `undefined`, `null` and `""`).

Why not `album=""`: the client drops empty values on purpose, an empty value is easy to lose in other clients and
proxies, and "empty album with sub-albums" would need a second meaning. An explicit flag is visible in the contract
and in logs.

Alternatives for the counts: one `GET /photos?limit=1` per album for the `total` (N requests on every library
change); a new `/albums/counts` endpoint (a second list to keep in step with the first).

Order: `api/openapi.yaml` first (also `Album.cover` documented as "not quarantined"), then the Kotlin DTOs and
`Library`, then `npm run gen:api`. Since the contract check does not cover query parameters, the meaning of `noalbum`
is tested by behaviour in `LibraryApiTest` (through `TestFrame`, which checks every response body).

### 6. Headings from `albumTree()`; photos without an album last

`lib/albums.ts` already builds the tree in the frame's order (case-insensitive). A new pure function turns tree,
`noAlbum`, open set, filter and the pinned path (decision 9) into the rows to render: heading rows (path, title,
depth, count for the filter, own count, open, album) and grid slots. With a visibility filter an album whose filtered
count is 0 is left out with its branch, unless it is pinned. The heading for photos without an album comes after all
albums, as in the preview the user approved, has no switch, uses the text "No album" (`photos.noAlbumHeading`, same
wording as the detail view and the upload choice), and is left out when its count for the filter is 0.

Above the headings the overview shows the number of photos that match the filter in the whole library: the sum of the
top-level albums' counts and the count without an album. With no photo in the library it shows the existing
`photos.empty` hint and no heading; the hint of the Albums section ("an album is a folder", `albums.empty`) goes away.

### 7. Open state in `localStorage`

Key `openframe.photos.openAlbums`, a JSON array of open paths; the photos without an album use the reserved entry
`""`, which is no album path (upload and scan drop empty levels). Every access is wrapped in `try/catch`; on failure
the overview starts folded and keeps the state in memory only. Stored paths are not pruned when they are missing from
the album list, so a remote source that is unreachable for a while does not lose its open albums; unknown paths are
ignored when rendering. The list is capped at 1,000 paths, the oldest opened dropped first. The storage is per
origin, so per frame address.

### 8. Uploads open their target

`UploadPanel`'s `onuploaded` passes the album path the photos went to (`""` without an album). `PhotosView` opens
that heading and every heading above it, stores the state and scrolls the heading into view. A new user who uploads
without choosing an album thus sees the photos at once, although the overview started folded. Photos that arrive
over USB or from a source keep the remembered state; their heading shows the new count.

### 9. Switch on the heading

The switch, its one-at-a-time `busy` lock and the rollback on error move from `AlbumsView` into the heading. After
the `PATCH /albums` answer the album list is replaced from it and `refreshState()` reads the library version at once,
so the version change reloads the open grids through the queue immediately instead of after up to 10 s.

While a visibility filter is set, the switched heading is pinned: it stays in the rows with its count (possibly 0)
and its grid until the filter or the sort order changes, so it neither vanishes under the pointer nor takes the
focus with it. Headings are keyed by path, so the switch element and its focus survive the reload.

### 10. Selection, detail and changes across grids

Selection is one map from photo ID to source for the whole page, filled when a tile is selected. "Can delete" reads
the sources from the map, so folding an album, which drops its pages, keeps its photos selected and never offers
Delete for a Nextcloud photo. Hiding and showing a selection work on IDs whose pages are gone; the batch answer
reports IDs that no longer exist.

The detail view looks up the grid that holds the photo; after a reload it takes the photo from that grid. A library
version change reloads the album list and then the loaded pages of every open album through the queue. A settings
version change reloads the pages of every open album as well, since display URLs depend on fill mode and
orientation. The 60-second stale-URL refresh and the reload after an image error run per grid through the queue.

### 11. Route `/albums` goes away

`albums` leaves `ROUTES` and the navigation; `MOVED` maps `albums` to `photos`, as it does for `nextcloud`, so the
address is replaced with `/photos` (without the entry the unknown route would also show the photos, but keep the old
address). `AlbumsView.svelte` and the `albums.*` strings that only it uses are removed; `nav.albums`,
`photos.filterAlbum`, `photos.allAlbums`, `photos.onlyThisAlbum` go from `de.json` and `en.json`. The string test
only compares the keys of both catalogs, so unused or missing keys are checked by a search of the sources.

### 12. Narrow screens and assistive technology

Headings are `role="heading"` with `aria-level` = depth + 2, so levels deeper than `h6` keep their level. Indentation
grows by 12 px per level up to depth 4 and stays there; the name wraps (`overflow-wrap: anywhere`) instead of
widening the row; count and switch stay on the heading's row or wrap below the name. The fold button has
`aria-expanded` and `aria-controls` for the album's content; the names of fold button and switch contain the album's
path as `displayPath` shows it ("holiday / 2024"). Fold buttons and switches have touch targets of 44 × 44 px.

## Risks / Trade-offs

- [Many albums open at once] → the queue (decision 4) and loading near the screen (decision 3) keep a reload with
  many open albums to two requests in flight and a first page of 60 photos per album on screen.
- [A library version change with many open albums] → every open album reloads its loaded pages, two at a time; the
  grids on screen go first, so what the user sees is fresh first.
- [Counts in the album list and the loaded pages briefly disagree] → the reserved height uses the count until the
  first page arrives; then the grid follows the page's `total`.
- [Content jumps when an upper grid reloads with fewer photos] → acceptable; the same happens today with the flat
  grid. The measurement (decision 2) keeps every grid's rows right, and `focusPhoto` re-measures before scrolling.
- [`GET /albums` with thousands of albums] → it signs one cover URL per album on every call, and the overview calls
  it on every library change. A performance case at 10,000 photos in four levels guards it.
- [Clients with strict schema checks of `GET /albums`] → the fields are additive; the Home Assistant integration
  reads the answer as plain dictionaries and takes only the fields it knows.
- [Users who relied on the flat "all photos by date" view] → the user decided to drop it; the implementation's pull
  request title marks the change as breaking, so release-please lists it in the release notes.

## Migration Plan

No data migration. The open state starts empty (all folded) for every browser. A rollback of the app restores the
Albums section and the filter; nothing is stored on the frame that an older build would misread, and the
`localStorage` key is simply unused by it.
