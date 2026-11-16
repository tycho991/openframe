# Design

## Context

See proposal.md for the motivation. Today:

- `PhotosView.svelte` holds one `PhotoPages` (cursor paging, 200 per page, signed URLs renewed after 50 minutes)
  and one `VirtualGrid`. The grid positions tiles absolutely and computes the visible range from `window.scrollY` minus
  its own top (`gridLayout` in `lib/grid.ts`), so it already works when it does not start at the top of the page.
- `GET /photos` filters with `album` (+ `subalbums=false` for only the album itself) and `visible`; `total` is the
  count of the filtered list. `album=""` matches nothing today, because a photo without an album has `album: null`.
- `GET /albums` lists every album with a photo in its branch (ancestors included, from `PhotoIndex.albums`), with
  `count` (branch, regardless of visibility), `hidden`, `overridesParent` and `cover`. Photos without an album appear in
  no entry.
- `AlbumsView.svelte` renders the tree from `albumTree()` with a fold button, cover, count, exception badge and the
  "Show in slideshow" switch; `PATCH /albums` answers with the whole list.

## Goals / Non-Goals

**Goals:**

- One page shows the tree of headings and, below each open heading, a paged virtual grid of the album's own photos.
- A folded album costs no photo request; a large open album costs no more than the flat grid does today.
- Counts on the headings follow the visibility filter without one request per album.

**Non-Goals:**

- Virtualizing the list of headings itself. Folded headings are one row each; a few hundred albums are fine.
- Cover images on the headings (the user chose count and switch only). The API keeps `cover` for other clients.
- Moving photos between albums, renaming albums, actions on headings other than the switch.
- An "expand all / collapse all" control.

## Decisions

### 1. One paged grid per open album, loaded with `album` + `subalbums=false`

Each open heading whose album has photos of its own gets its own `PhotoPages` with
`{ album: path, subalbums: false, visible, sort }` and its own `VirtualGrid`. Sub-albums are separate headings, so
their photos never appear twice. The pages of an album are dropped when it is folded and loaded from the start when it
is opened again; this bounds memory to the open albums.

Alternatives: one flat list sorted by album then date, cut into groups on the client (needs a new sort order in the
API and loads photos of folded albums); grouping only the loaded pages (counts and group boundaries would be wrong
until everything is loaded).

### 2. `VirtualGrid` loads more only while it is on screen

With several grids stacked, `gridLayout` reports `nearEnd` for a grid that lies entirely above or below the viewport
(the clamped row range hits its end). The grid gets an extra check: it calls `onloadmore` only when its box intersects
the viewport (plus the overscan rows). Its height stays the height of the loaded rows, as today; a grid that loads
more grows downward, which only moves content below the viewport.

### 3. API: visible counts in `GET /albums`, photos without an album as empty `album`

- `Album.visibleCount`: photos of the branch that are visible (`PhotoLibrary.isVisible`, so album and photo state
  both count). Hidden count for the filter "only hidden" is `count - visibleCount`. Computed in the same pass over the
  photos in `PhotoIndex.albums`.
- `AlbumList.withoutAlbum: { count, visibleCount }` for the photos with `album: null`, from all sources together.
- `GET /photos?album=` (empty) with `subalbums=false` returns the photos with `album: null`; with sub-albums it
  matches every photo, the same as no `album`. Real album paths are never empty (upload drops empty levels), so the
  value is free. Only additive: older clients see extra fields and an empty value that used to return nothing.

Alternative: one `GET /photos?limit=1` per album for the `total` (N requests on every library change); a new
`/albums/counts` endpoint (a second list to keep in step with the first).

Order: `api/openapi.yaml` first, then the Kotlin DTOs and `PhotoIndex`, then `npm run gen:api`.

### 4. Headings from `albumTree()`; photos without an album last

`lib/albums.ts` already builds the tree in the frame's order (case-insensitive). A new pure function turns tree,
open set and filter into the rows to render: heading rows (path, depth, count for the filter, open, album) and
grid slots. With a visibility filter an album whose filtered count is 0 is left out with its branch. The heading for
photos without an album comes after all albums, as in the preview the user approved, and is left out when its count
for the filter is 0.

### 5. Open state in `localStorage`

Key `openframe.photos.openAlbums`, a JSON array of open paths; the photos without an album use a reserved key that is
no album path (an empty string). Every access is wrapped in `try/catch`; on failure the overview starts folded and
keeps the state in memory only. Paths that no longer exist are dropped when the album list arrives. The storage is per
origin, so per frame address.

### 6. Switch on the heading

The switch, its one-at-a-time `busy` lock and the rollback on error move from `AlbumsView` into the heading
component. The `PATCH /albums` answer replaces the album list. The library version changes with it, so the open
grids reload through the existing version poll; no extra reload is triggered by the switch.

### 7. Selection, detail and changes across grids

Selection stays one set of photo IDs for the whole page; "can delete" looks the IDs up in the loaded items of all
open grids. The detail view and `focusPhoto` look up the grid that holds the photo. A library version change reloads
the album list and the loaded pages of every open album; the 60-second stale-URL refresh runs over all open albums.

### 8. Route `/albums` goes away

`albums` leaves `ROUTES` and the navigation; `MOVED` maps `albums` to `photos`, as it does for `nextcloud`.
`AlbumsView.svelte` and the `albums.*` strings it alone uses are removed; `nav.albums`, `photos.filterAlbum`,
`photos.allAlbums`, `photos.onlyThisAlbum` go from `de.json` and `en.json`.

## Risks / Trade-offs

- [Many albums open at once, each with its first page] → each open album loads 200 photos at most until its grid is
  scrolled near its end; the first page of a folded-then-opened album is one request.
- [Content jumps when an upper grid reloads after a library change with fewer photos] → acceptable; the same happens
  today with the flat grid. The focused tile keeps its focus through `focusPhoto`.
- [Clients with strict schema checks of `GET /albums`] → the fields are additive; the Home Assistant integration
  reads the answer as plain dictionaries and takes only the fields it knows.
- [Users who relied on the flat "all photos by date" view] → the user decided to drop it; noted in the release notes.

## Migration Plan

No data migration. The open state starts empty (all folded) for every browser. A rollback restores the Albums section
and the filter; the added API fields are harmless to the old interface.

