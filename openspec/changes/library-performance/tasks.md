# Tasks

## 1. Contract and texts (first, `api/openapi.yaml` before the code)

- [x] 1.1 `api/openapi.yaml` first: the upload description names the storage reserve instead of 100 MB, says that
  nothing is stored on any error, and that an album level that exists as a file is refused with 400 `invalid_album`
  as well; the `album` field description likewise; `version` of the schema `Photo` reads "changes with every change
  of the file and is never reused for another file at the same path"; `DELETE /photos/{photoId}` and
  `POST /photos:batch` get a 503 response (`storage_unavailable` while the photo folder is unavailable) (design
  decisions 1, 5, 8 and 9). No schema changes, so no DTO changes. Then `npm run gen:api`. Verify: `npm run lint` in
  `webadmin/` (Redocly included) is green, the generated types change only in descriptions and the new 503 answers,
  and `RouteAccessTest` stays green.
- [x] 1.2 Texts: `error.invalid_album` in `webadmin/src/lib/i18n/en.json` and `de.json` says the album path is invalid
  or too long; the README names the storage reserve (about 600 MiB on the reference frames) instead of "A download
  never leaves less than 100 MB free". Verify: `npm run lint`, `npm run typecheck` and `npm test` are green, and
  `grep -n "100 MB" README.md` finds no reserve sentence.

## 2. Counters and scaling tests (they fail before the fixes)

- [x] 2.1 A first commit of its own with the test seams only (design decision 13): `RenderCache` lists its folder
  through one injectable function, and `PhotoIndexStore` counts its `edit` calls (`internal`). Verify:
  `./gradlew :core:check` is green, and no behaviour changes.
- [ ] 2.2 The four count-based tests with N = 1,000:
  - rendering N photos and then removing their source lists the render folder at most a few times in total, counted
    over both steps (#116 already made the removal itself list once; today every rendering lists the folder), in
    `RenderCacheTest`/`SourceRegistryTest`;
  - `SlideshowControllerTest` with a cold cache of 1,000 photos: the photos asked for ahead through the fake's
    existing `prefetch` never number more than `PREFETCH_AHEAD`, and one `show()` renders at most 3 photos on demand;
    task 7.3 moves the test to `ahead()`;
  - `LocalPhotoSourceTest`: a rescan without a change makes no `edit` call;
  - `LibraryApiTest` with the contract check: a batch over N IDs raises `libraryVersion` by exactly 1.

  Verify: each of the four fails on the code of 2.1 (noted in the pull request), and passes after the groups below.

## 3. Render cache index

- [x] 3.1 `RenderCache` per design decision 3: name → (last use, size, file time set) and photo ID → names; names that
  do not parse are deleted at the start; one listing at the start with one time and size read per file, its duration
  logged; a running total; `trim` without size reads; `totalBytes()` from the total; older versions removed after a
  rendering through the index; `peekAnyDisplay` and `peek` answered from the index, the disk asked only for a hit;
  `removeAll(entries)` updates memory at once and hands the files to a delete thread of low priority. Verify:
  `RenderCacheTest` is green with new cases: rendering below the budget reads no other file's size (listing counter
  and a size-reading seam); `removeAll` of 1,000 photos among 1,100 lists nothing; a file `junk.jpg` is gone after the
  start; the existing budget and version cases stay green (`photo-library` › Limit storage space › Budget exceeded).

## 4. Removal notification and image versions

- [x] 4.1 `PhotoIndexStore` reports the entries that left the index (design decision 4): `LibraryEdit` records the IDs
  of `remove` and `skip`, the photo map is no longer writable from outside, and the listener runs after publishing,
  inside the lock. Verify: `PhotoIndexStoreTest`: the listener gets exactly the removed entries for `remove` and
  `skip`, nothing for a `put` with a new version, and nothing for an edit without a change.
- [x] 4.2 Wiring: `FrameCore` passes `cache.removeAll` and the removal of remote originals through `SourceRegistry`
  (on its scope) to the listener; `onPhotosRemoved` of `SourceRegistry`, `onRemoved` and the explicit
  `originals.removeAll` of `RemoteSource.sync`, and `cache.remove` in `PhotoLibrary.delete` go. Verify: tests with
  `TestFrame` leave no rendering behind for a file deleted from the folder and scanned, for a local file replaced by a
  damaged one, for a remote file that vanishes with "remove vanished photos" on, and for a remote file whose new
  version is skipped; in the last case its old original is gone as well (`photo-library` › Rendered sizes follow the
  index › File deleted over USB, › File replaced by a damaged one; `remote-sources` › Vanished photos › Photo deleted
  at the source).
- [x] 4.3 Image versions and tombstones (design decision 5): `LibraryEdit.newVersion`; a new entry gets it, a changed
  entry gets the version in the edit plus 1, in `LocalPhotoSource.scan`, the upload's add and `RemoteSource.sync`;
  `RenderCache.removeAll` keeps tombstones, checked under the cache's lock before rendering, before the handover to the
  download thread and before the rename of the `.tmp` file. Adapt tests that assume version 1 for a new photo,
  `webadmin` fixtures included where they model the server. Verify:
  - `LocalPhotoSourceTest`: delete a file, scan, put another photo of the same dimensions at the same path, scan: the
    version is above every earlier one, the ETag and the image URL are new, and thumbnail and display size are
    rendered again; the same through a damaged file in between (skipped, then valid);
  - `RemoteSourceTest`: the same for a file that vanishes from the server and comes back;
  - `RenderCacheTest`: `removeAll` while a rendering of the photo runs leaves no file, and a waiting job of a removed
    photo neither renders nor fetches its original;
  - (`photo-library` › Stable photo IDs › Another file at the path of a removed photo, › Rendered sizes follow the
    index › Removed while rendering).
- [x] 4.4 Start sweep: after `index.load` and before `slideshow.start`, `FrameCore.start` removes renderings whose photo
  ID is not in the index. Verify: `FrameCoreTest`: a start with a rendering of an unknown ID deletes it, and that of a
  photo in the index stays (`photo-library` › Rendered sizes follow the index › Renderings from before the update).

## 5. Source removal in the background

- [x] 5.1 `SourceRegistry.remove` deletes the originals folder on its scope, outside its lock; the renderings go through
  4.2 (design decision 11). Verify: `SourcesApiTest` with the contract check: `DELETE /sources/{sourceId}` of a source
  with 1,000 photos answers 204 while a test hook holds the deletion of the originals, the photos are gone from the
  library at once, and after the hook is released no original and no rendering of the source is left; a `TestFrame`
  restarted with an originals folder and renderings of a removed source deletes both
  (`remote-sources` › Remove a source › Source with many photos, › App ends during the deletion).

## 6. Storage reserve

- [x] 6.1 `StorageReserve.bytes(total)` and `PlatformInfo.totalBytes(dir)` with the `FakePlatform` override (design
  decision 1). Verify: `StorageReserveTest`: 16 GiB → 600 MiB, 3,000 MiB → 400 MiB, 0 → 100 MiB
  (`photo-library` › Storage reserve › Reference frame, › Small volume); `./gradlew :core:check` (API 21 check)
  is green.
- [x] 6.2 Wiring: `FrameCore` computes the reserve once from the cache folder and logs
  `Storage reserve: N MiB of M MiB`; `PhotoLibrary` uses it instead of `RESERVE_BYTES` and names it in the 507 message;
  `SourceRegistry` passes it and `platform::freeBytes` to every `OriginalsStore`, whose `RESERVE_BYTES` goes. Verify:
  - `UploadApiTest` with the contract check and `FakePlatform.free` just below reserve + upload: 507, nothing stored, no
    partial file (`photo-library` › Upload › Storage full);
  - `RemoteSourceTest`/`SourceRegistryTest`: a download that would go below the reserve fails with `STORAGE`, and the
    free space is asked from `FakePlatform`, not `usableSpace` (`remote-sources` › Storage of the originals › Budgets
    larger than the storage);
  - a `FrameCoreTest` finds the log line.
- [x] 6.3 `RenderCache` below the reserve (design decision 2): free storage checked before each rendering; its own
  entries removed, also below the budget, never a name in `wanted`, an awaited name or a file in `keep`; a job nobody
  awaits fails with a storage error, an awaited one renders; a warning at most once an hour; the index rebuilt from one
  listing when a `peek` misses a name it knows, at most once an hour; `touch` sets the file time at most once an hour
  (clock injected). Verify: `RenderCacheTest` with `FakePlatform.free` below the reserve: a window job is not
  rendered, the oldest renderings are deleted until the reserve is free while the `keep`, `wanted` and awaited files
  stay, a `get()` renders; a file deleted behind the cache's back triggers one listing at the next `peek` and none at
  the following misses within an hour of `TestClock`; repeated `peek`s change the file time at most once per hour of
  `TestClock`; a `TestFrame` with free storage below the reserve
  keeps changing local photos, each rendered when due and none ahead of time (`photo-library` › Storage reserve ›
  Storage runs short; › Going below the storage reserve › Local photos below the reserve).
- [ ] 6.4 The exception for displays (design decisions 1 and 2): `SourceOriginals.fileOf` and `SourceRegistry.original`
  take an on-demand flag, set by the download thread when one of the download's jobs is awaited; `RemoteSource`
  checks `hasRoomOnDisk` and `roomBytes` against `OriginalsStore.ON_DEMAND_FLOOR_BYTES` (100 MiB) for it and against
  the reserve otherwise; a window download that fails with `STORAGE` hands an awaited job that joined it over again
  as an on-demand download. Verify: `RenderDownloadTest`/`RemoteSourceTest` with `FakePlatform.free` between
  100 MiB + the original's size and the reserve: a `get()` of a remote photo without its original downloads it and
  returns the rendering; a window job of another such photo makes no request; with less than 100 MiB + size free the
  `get()` fails with `STORAGE` without a request; a sync in the same state downloads nothing new and fails with
  `STORAGE`; a `get()` that joins a window download failing with `STORAGE` gets its file through a second download
  (`photo-library` › Going below the storage reserve › Remote photo without its original below the reserve,
  › Storage almost full; `remote-sources` › Storage of the originals › Removed original on a nearly full frame,
  › Storage almost full).

## 7. Window of renderings ahead

- [ ] 7.1 `RenderCache.prefetchAhead(next, later, keep)` with the set `wanted`, the `Pending` referenced by each `Job`
  with its awaited flag, removal of an `inFlight` entry only when it is the job's own `Pending`, dropping under the
  one cache lock with the internal "no longer wanted" exception and `finish()`, and the lock order of design decision
  6a (no `index.edit`, `SourceRegistry`, `SourceOriginals` or `onCrashed` call under the cache lock; tombstone check,
  rename and index entry in one critical section); the log line of a failed rendering without a stack trace for a
  `SourceException` or a dropped job (design decision 6). Verify: a test quarantines a photo from the worker while
  another thread's edit removes photos, and both finish; Verify: `RenderCacheTest`:
  `prefetchAhead(A..J)` then `prefetchAhead(K..T)`: none of the waiting A..J renders, and a later `prefetchAhead(A)`
  queues A again; a job raised to `DEMAND` by a `get()` is not dropped and returns its file; a `get()` at `NEXT` (the
  refresh of the photo on screen) is not dropped either.
- [ ] 7.2 Downloads in the window: a queued download with only droppable jobs leaves the queue; a running one is
  cancelled through a cancel check passed by `SourceOriginals.fileOf` and `SourceRegistry.original` into the download
  limits; a job that joins a cancelled download does not take the cancel back and is handed over again after it
  ends; a download with an awaited job is never cancelled. Verify: `RenderDownloadTest`: a dropped queued download
  makes no request; a running one blocked in the fake ends at its next read, leaves no `.part`, and its pin is
  released (`OriginalsStore` trims it); a `get()` that joins it during the cancellation still gets its file through a
  second download; a window job is dropped, a `get()` for the same name follows, and then the cancelled download
  fails: the `get()` still gets its file, and its `inFlight` entry was not removed by the old job.
- [ ] 7.3 `SlideshowController` and `DisplayImages.ahead(next, later, keep)`, `keep` being the files on screen and next
  up: `show()` puts the first `NEXT_AHEAD`
  candidates into the window as next and the rest up to `PREFETCH_AHEAD` as later instead of queueing every candidate,
  renders at most `MAX_DEMAND_ATTEMPTS = 3` on demand and arms the timer when all fail; `Command.Rest(true)` calls
  `ahead([], [], keep)`; the counting test of 2.2 moves from `prefetch` to `ahead()`. Verify: `SlideshowControllerTest`
  with `FakeImages`:
  - after three failed renderings on demand, advancing the test dispatcher's time by one interval (`advanceTimeBy`)
    tries the following candidates;
  - swiping forward drops waiting renderings that left the window;
  - resting empties the window and waking sets it again;
  - (`slideshow-display` › Bounded rendering ahead › Cold cache with 5,000 photos, › Window moves on, › Server of all
    photos unreachable; `display-schedule` › Slideshow rests while the display is off › Renderings queued before the
    night).

## 8. Local scan

- [ ] 8.1 `LocalPhotoSource.scan` returns without `index.edit` when nothing changed; each scan is timed, with a warning
  above 1 s at most once an hour (design decision 7). Verify: `LocalPhotoSourceTest`: the edit counter stays at 0
  and the `Library` instance stays the same for a rescan without a change, a removed file still causes one edit, and a
  slow fake listing logs the warning once within an hour of `TestClock` (`photo-library` › Local scan while the
  display is off › Nothing changed; › Metadata › Rescan without changes).
- [ ] 8.2 Cadence: `scanLoop` waits 15 s with the display on and 5 minutes with it off, the night interval injected
  like `scanInterval` (its `withTimeoutOrNull` runs on real coroutine time, which `TestClock` does not drive); the
  display going on calls `requestScan()`; `GET /photos`, `/albums` and `/skipped-files` call it through a hook when the
  last scan began more than 15 s ago on `clock.monotonicMillis()`, `GET /state` does not. Verify: a `TestFrame` test
  with short injected intervals (day interval short, night interval long), `TestClock` for the guard and
  `FakeDisplayPower`: with the display off a file copied into the folder is not scanned within several day intervals
  and is in the index after the night interval; switching the display on scans at once; with the display off,
  `GET /photos` with the contract check leads to a scan, and the next `GET /photos` lists the file; `GET /state` leads
  to no scan; two `GET /photos` within 15 s of `TestClock` lead to one scan (`photo-library` › Local scan while the display is off › Photo copied at night,
  › Display goes on, › Admin interface at night; › Local photo folder › Photo copied over USB).

## 9. Batch actions

- [ ] 9.1 `PhotoLibrary.setHiddenAll` and `deleteAll` with one index edit each, `delete(id)` on top of `deleteAll`
  keeping 404, 409 `read_only_source` and 500 `delete_failed`; `MediaStoreNotifier.notifyChanged(files)` with a
  default loop; `POST /photos:batch` on top of them (design decision 8). Verify: `LibraryApiTest` with the contract
  check: hide and delete batches with local, Nextcloud and unknown IDs give the same `processed`, `missing` and
  `failed` as before and raise `libraryVersion` by exactly 1; a hide batch of photos already hidden leaves it
  unchanged; `DELETE /photos/{photoId}` still gives 404 and 409, and 500 `delete_failed` when the file cannot be
  deleted (test hook); with `FakePlatform` reporting the storage as not mounted, `DELETE /photos/{photoId}` and a
  delete batch answer 503 `storage_unavailable`, delete nothing and leave the entries in the library; the fake notifier
  gets one call with all deleted files; `RouteAccessTest` stays green (`photo-library` › Batch changes › Large batch,
  › Mixed batch, › Nothing to change; › Delete › Batch with Nextcloud photos; › Photo folder kept › Delete while the
  storage is unavailable).
- [ ] 9.2 `AndroidMediaStoreNotifier` overrides `notifyChanged(files)` with one `MediaScannerConnection.scanFile` call.
  Verify: a Robolectric test sees one scan request with all paths; `./gradlew :android:check` is green.
- [ ] 9.3 Web UI: e2e cases for deleting a single photo and a selection of photos, both through `photos:batch` (own
  `OPENFRAME_E2E_PORT`). Verify: `npm run e2e` is green, and both photos are gone from the list and from the dev
  server's folder.

## 10. Uploads

- [ ] 10.1 Names and albums (design decision 9): a 200-byte UTF-8 budget for file names at code point boundaries with
  the image extension kept; album levels cut without splitting a surrogate pair; each album level resolved against the
  existing folders (exact, then case-insensitive); `isDirectory` checked after `mkdirs`, else 400 `invalid_album`
  naming `album`. Verify: `UploadApiTest` with the contract check:
  - 90 × `日` + `.jpg` is stored with at most 200 bytes and `.jpg`;
  - an emoji of one code point at the cut is kept whole or dropped, and the name is valid UTF-8;
  - a 300-byte name without an image extension stays below 255 bytes with the appended extension;
  - an album level that is a file gives 400 `invalid_album` naming `album`;
  - with a folder `Holiday`, an upload into `holiday` lies in `Holiday/` with the album `Holiday`;
  - (`photo-library` › Names of uploaded files › Long name in another script, › Emoji at the cut, › Album level is a
    file; › Upload into an album in another letter case › Album in lower case).
- [ ] 10.2 All or nothing: targets of all files resolved under one lock, also against each other, then all renamed,
  with the renamed ones deleted on a failure before anything is indexed; then one `LocalPhotoSource.addAll` edit and one
  `notifyChanged(files)`. Verify: `UploadApiTest`: two files where the rename of the second fails (test hook) leave no
  file and no entry, and `libraryVersion` is unchanged; two files with the same name in one request get distinct names
  (`photo-library` › Names of uploaded files › Second file fails; › Upload › Same name).

## 11. Capture dates

- [ ] 11.1 `AndroidMediaEngine.parseExifDate` with `isLenient = false`; `CaptureDates.plausible` (before 1800 →
  `null`) in `LocalPhotoSource` and `RemoteSource` before the file date applies (design decision 10). Verify:
  `AndroidMediaEngineTest`: `0000:00:00 00:00:00` and `2024:00:00 00:00:00` give `null`; a `:core` test with an
  engine wrapper returning year −1: `GET /photos` with the contract check gives the file date as a valid `date-time`
  (`photo-library` › Implausible capture dates › Camera without a set clock).
- [ ] 11.2 After `index.load`, `FrameCore.start` corrects every entry dated before 1800 to its file date in one edit.
  Verify: `FrameCoreTest`: a stored entry of year −1 has its `modifiedMs` as capture date after the start, and the
  library version is one higher; a start without such entries leaves the version unchanged (`photo-library` ›
  Implausible capture dates › Index from before the update).

## 12. SlideRenderer

- [ ] 12.1 `SlideRenderer` per design decision 12: pending flags instead of the 200 ms polling with the catch-up posted
  from `done()`; the full reset in `clear()` with its own counter; `released`, callbacks removed and a graceful
  shutdown in `release()`; an injectable decoder executor; the class KDoc as `BitmapFit` works. Verify: a new
  Robolectric `SlideRendererTest` with a synchronous executor: start a fade, call `preload` during it, then `clear()`
  in the middle; afterwards no runnable is left in the main looper, the `pendingOld` bitmap is recycled, both views
  have `translationX` 0, `alpha` 1 and `translationZ` reset after a cancelled slide and fade, a `preload`
  during a fade decodes exactly once after it ends, and a late decode result after `clear()` or `release()` throws
  nothing and sets no preloaded bitmap; `./gradlew :android:check` is green (`slideshow-display` › Memory limits ›
  Endurance run).

## 13. Checks

- [ ] 13.1 `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e`
  (own `OPENFRAME_E2E_PORT`) are green. Verify: the command outputs.
- [ ] 13.2 `openspec validate library-performance` exits 0 without warnings. Verify: the output.

## 14. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 14.1 Before the release, read-only on both frames: `df` of the data volume and of the photo folder,
  `dumpsys devicestoragemonitor`, and `settings get global sys_storage_threshold_percentage` and
  `settings get global sys_storage_threshold_max_bytes`. Verify: the values are noted in the private checklist, with
  whether either frame has between 100 and 600 MiB free, whether the two `df` values match, and whether the threshold
  and the trimming points match design decision 1 (design, Risks).
- [ ] 14.2 The main session adds rows to the private device checklist, each with its spec reference:
  - Frameo with 5,000 to 10,000 synthetic photos from `scripts/generate-test-fixtures.py` and a cleared render cache:
    after the first photo only about 10 renderings appear; scan time from the log and CPU of the app and the storage
    daemon with `dumpsys cpuinfo`; memory with `dumpsys meminfo`; the time of the render cache's first listing from the
    log (`slideshow-display` › Bounded rendering ahead › Cold cache with 5,000 photos; `photo-library` › Local scan
    while the display is off › Nothing changed);
  - a test share with many photos: sync time, then removing the source and its answer time (`remote-sources` › Remove
    a source › Source with many photos);
  - Lenovo: upload into `holiday` next to an existing `Holiday`; after 30 s the photo has the same ID and a comment set
    on it stays; then the test photo is deleted (`photo-library` › Upload into an album in another letter case ›
    Album in lower case);
  - Lenovo: 20 uploaded test photos deleted in one batch are no longer visible over MTP (`photo-library` › Batch
    changes › Large batch);
  - Lenovo with fade: the schedule switches the display off during a crossfade; an hour later `logcat` and
    `dumpsys meminfo` show no growth (`slideshow-display` › Memory limits › Endurance run);
  - both frames: the reserve line in the start log (`photo-library` › Storage reserve › Reference frame);
  - the existing upload row "less than 100 MB would remain free" moved to the reserve (`photo-library` › Upload ›
    Storage full).

  Verify: the rows exist with their references.
- [ ] 14.3 Run the rows of 14.2 after the release, updating through the updater, with the owner's consent for the
  update, the synthetic photos, clearing the render cache, the test share, the test uploads and the schedule. Verify:
  results are entered, the test data is removed again, and anything that differs gets a finding in the private
  findings list.

## Workflow follow-up

- Archive the change after the device rows of 14.3 are done.
