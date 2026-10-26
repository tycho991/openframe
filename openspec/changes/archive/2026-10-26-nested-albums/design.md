# Design

## Context

The album is computed in one place, `PhotoEntry.album` (the part of `path` before the first `/`), and `path` is relative to the folder of the photo's source for local and remote photos alike, so one change there reaches every source. Hiding is a `Set<String>` of names, `StoredIndex.hiddenAlbums`, read by `Library.isVisible`, which the display uses through `Library.visible`. `GET /albums` groups the photos by `album`; `PATCH /albums/{albumName}` sets one name; `GET /photos?album=` compares for equality. The router splits the request path at `/` before matching (`Route.split`), so a path parameter can never hold a `/`. The upload sanitizes the album as one level (`sanitizeAlbum`, `/` is one of the forbidden characters). `JsonStore` reads with `ignoreUnknownKeys = true`. Proposal and specs say what changes; this document says how.

## Goals / Non-Goals

**Goals:**
- One rule for the album of every source, in one place.
- Visibility of a photo stays a cheap lookup: the slideshow and the photo list call it for every photo of a 10,000 photo library.
- An update keeps every photo's visibility, and going back to the previous build keeps at least the top-level hidden albums.

**Non-Goals:**
- No change to the display or to `:android`; they read `Library.visible` as before.
- No album entity of its own (no IDs, no empty albums): an album exists as long as a photo lies in it or below it.

## Decisions

### 1. The album is the folder path of the photo

`PhotoEntry.album` becomes the part of `path` before the last `/` (`null` without one). Ancestors are derived from it (`a/b/c` → `a`, `a/b`). Paths compare case-sensitively, as names do today, so `Holiday` and `holiday` stay two albums. Album paths from different sources merge because they are plain strings.

*Alternative:* a separate album tree per source (`source:path`). Rejected: the user wants the same folder from two sources in one album, and the flat albums already merge.

### 2. Stored state: an album's own setting, only where it differs

`StoredIndex` gets `albumStates: Map<String, Boolean>` (path → hidden). The invariant: an entry exists only where the album's state differs from what it would inherit (a top-level album inherits "visible"). `setAlbumHidden(path, hidden)` removes every entry below `path`, then stores `path → hidden` if that differs from the parent's effective state and removes the entry otherwise. This gives both rules of the spec at once: switching resets the branch, and switching a sub-album back to its parent's state removes the exception. "Overrides parent" in the API is simply "has an entry and has a parent".

*Alternative:* a three-valued state (hidden / shown / inherit) set explicitly, with a separate "reset" call. Rejected: with the reset of the branch on every switch, an explicit "inherit" adds a call and a state that the user cannot tell apart from the switch.

### 3. Effective state computed once per library state

`Library` computes lazily, once per state, a map of every album path that occurs (with its ancestors) to its effective hidden state, walking paths in sorted order so a parent is resolved before its children. `isVisible(photo)` is then `!photo.hidden && !hiddenEffective[photo.album]`, as cheap as the set lookup it replaces. The same pass yields the album list (count and newest photo per branch: each photo is added to its album and its ancestors, O(photos × depth)).

*Alternative:* walk the ancestors on every `isVisible` call. Rejected: it is called per photo for every list and slideshow update; the map costs one pass per change.

### 4. Migration and rollback

Loading a `StoredIndex` without `albumStates` turns every name in `hiddenAlbums` into `name → true`. Old names are top-level paths, so the invariant holds, and every photo in a deeper folder inherits what its old album had (spec "Album states kept across the update"). Saving keeps writing `hiddenAlbums` as the set of paths stored as hidden; an older build ignores `albumStates` (`ignoreUnknownKeys`) and still hides the top-level albums, while exceptions below them are lost there. Because an older build can add a parent to those paths and save without `albumStates`, the states are normalized at every load: an entry equal to what the album inherits is dropped, so the invariant of decision 2 holds again. Settings of albums whose folders are gone are kept, as hidden names are today, so an album that is emptied and filled again keeps its state.

*Alternative:* drop `hiddenAlbums`. Rejected: going back one build would show every hidden album.

### 5. API

Written in `api/openapi.yaml` first.
- `PATCH /albums` with `{name, hidden}` replaces `PATCH /albums/{albumName}`, because the router cannot carry `/` in a path parameter and `%2F` is decoded differently by proxies. It answers with the whole `AlbumList`, because switching an album changes its sub-albums too. An unknown album is 404, as today.
- `Album`: `name` is the full path; `count` and `cover` cover the branch; `hidden` is the effective state; new `overridesParent`. The list is sorted by path (lowercase), which is tree order; the web UI builds the tree from the names.
- `GET /photos`: `album` matches the album and everything below it; `subalbums=false` matches only the album itself. `Photo.album` is the full path.
- Upload: the `album` field is a path. Each level is sanitized like today's album name (forbidden characters removed, leading dots trimmed, at most 64 characters); empty levels, `.` and `..` are dropped; a result longer than 255 characters is 400 `invalid_album` with the field `album`. The upload creates the folders.

*Alternative:* keep `/albums/{albumName}` and let the router accept an encoded `/`. Rejected: it would need a special case in the router and contract check for one route, and the response would still have to change.

### 6. Web UI

`AlbumsView` builds a tree from the sorted paths: one row per album with cover, last path segment as title, count and switch, indented under its parent; top-level albums are open, deeper levels are collapsed until opened. A row with `overridesParent` shows an "exception" badge. A switch calls `PATCH /albums` and replaces the whole list with the answer. The album filter in `PhotosView` and the album choice in `UploadPanel` list the full paths (`holiday / 2024`), indented; the filter gets a checkbox "only this album"; "new album" in the upload gets an optional parent album. `PhotoDetail` shows the path.

## Risks / Trade-offs

- [Switching a parent removes exceptions without asking] → chosen by the user; the result is visible at once, because every switch below changes in the same answer.
- [Deep or many folders over USB make a long album list] → the tree is collapsed below the first level; the album list is one pass over the photos per change; a device row checks the albums page with a large library on the weakest frame.
- [Overlap with `multi-source-share-links`] → that change moves "Sync of a folder" from `nextcloud-source` to `remote-sources` and says there that the first folder is the album. `multi-source-share-links` was archived first, so this change's delta modifies "Sync of a folder" in `remote-sources`, keeping its scenarios and adding the nested one.
- [A rollback loses exceptions] → accepted; top-level hidden albums survive (decision 4).

## Migration Plan

1. At the first start the index is loaded with `hiddenAlbums` turned into album states (decision 4); albums and visibility are recomputed from the paths. Nothing is asked of the user, and no photo ID changes, since IDs come from the path and not from the album.
2. The web UI ships in the same build, so the breaking `PATCH /albums` has no older client.
3. Going back to the previous build keeps top-level hidden albums and drops exceptions.
