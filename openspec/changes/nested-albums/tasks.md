# Tasks

## 1. Contract first

- [x] 1.1 `api/openapi.yaml`: `PATCH /albums` with `{name, hidden}` answering `AlbumList` replaces `PATCH /albums/{albumName}`; `Album.overridesParent`; `name`, `count` and `cover` described as path and branch; `subalbums` (boolean, default true) on `GET /photos`; the upload's `album` field as a path of at most 255 characters with error `invalid_album`; verify: `npx @redocly/cli lint api/openapi.yaml` passes
- [x] 1.2 Run `npm run gen:api` in `webadmin/`; verify: `webadmin/src/lib/api/schema.d.ts` has the new fields and `git diff --stat` shows only generated changes there

## 2. Albums and visibility in `:core`

- [x] 2.1 `PhotoEntry.album` becomes the folder path (before the last `/`), plus a helper for the ancestors of a path; verify: unit tests for no folder, one level, three levels, and that the photo ID of `holiday/2024/x.jpg` is unchanged
- [x] 2.2 `albumStates` in `StoredIndex`, `Library` and `LibraryEdit` with the invariant of design decision 2, `setAlbumHidden` removing the branch's entries; `hiddenAlbums` still written as the hidden paths; verify: unit tests for each scenario of "Inherited album visibility" and "Changing an album resets its sub-albums" in `photo-library`
- [x] 2.3 Effective state map and album aggregates (count and newest photo per branch, every ancestor an album) computed once per `Library` state; `isVisible` uses the map; verify: unit tests for an album with photos only in a sub-album, two sources with the same path, and a timing test that `visible` of a 10,000 photo library with 4 levels stays within the time of the existing library performance test
- [x] 2.4 Migration at load: an index without `albumStates` turns `hiddenAlbums` into hidden top-level entries; verify: a test loads a stored index of the previous format with `holiday` hidden and `holiday/2024/beach.jpg`, and the photo stays invisible and `holiday/2024` has no setting of its own; a second test checks that the saved file still lists `holiday` in `hiddenAlbums`
- [x] 2.5 Upload with an album path: `sanitizeAlbum` per level, empty, `.` and `..` levels dropped, more than 255 characters is 400 `invalid_album`, folders created; verify: tests for `family/2025`, `../../x`, `a/../../x`, a path of 256 characters, and that no file is created outside the photo folder
- [x] 2.6 Local scan over a folder tree: photos in `a/b/c/` get the album `a/b/c`; verify: a `LocalPhotoSource` test with a three-level tree, and the remote sync test "Nested subfolders as sub-albums" of `remote-sources`

## 3. API routes

- [x] 3.1 `GET /albums` from the aggregates of 2.3 sorted by path, `PATCH /albums` with the whole list as answer and 404 for an unknown album, removal of `PATCH /albums/{albumName}`; verify: `TestHttp(..., checkContract = true)` tests for the list, switching a parent with an exception below, switching a sub-album back, and the unknown album; `RouteAccessTest` passes
- [x] 3.2 `GET /photos` with `album` including sub-albums and `subalbums=false`; `Photo.album` and `visible` from the new rules; verify: contract-checked tests for both filters and for `visible` of a photo in a shown sub-album of a hidden album
- [x] 3.3 Run `./gradlew check`; verify: green, including `checkNoAndroidImports` and the API 21 check

## 4. Web UI

- [x] 4.1 API client: `setAlbumHidden` calls `PATCH /albums` and returns the list; `photos` takes `subalbums`; verify: `npm run typecheck` passes
- [x] 4.2 Album tree helper (paths to a tree, last segment as title, indentation for selects) in `webadmin/src/lib/`; verify: unit tests for sorting, missing intermediate albums and names with spaces
- [x] 4.3 `AlbumsView` as a tree: switch with the effective state, "exception" badge, top level open and deeper levels collapsed, the whole list replaced after a switch; texts in `en.json` and `de.json`; verify: e2e test "Show a sub-album of a hidden album" and "Switching a parent removes exceptions" pass
- [x] 4.4 `PhotosView` filter with paths and "only this album", `UploadPanel` with album paths and a parent for a new album, `PhotoDetail` with the path; verify: e2e tests "Filter by a parent album", "Filter by the album only" and "New sub-album" pass, and the existing e2e test "Hide album" passes with the new route
- [x] 4.5 Run `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e` in `webadmin/`; verify: all green

## 5. Device and documentation

- [x] 5.1 `docs/DEVICE_TEST.md`: rows for a three-level folder tree copied over USB (photo-library › Albums from folders › Assign album) and for the albums page with the largest library on the Frameo (web-admin › Albums), each with the spec reference; verify: the rows are present and name their scenarios
- [x] 5.2 Run `openspec validate nested-albums`; verify: exit 0 without warnings

## Workflow follow-up

- Run the device rows of 5.1 on the frames, then archive the change.
