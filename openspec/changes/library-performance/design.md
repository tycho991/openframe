# Design

## Context

See proposal.md for the motivation. The state on `main` (0.13.0) that shapes the approach:

- **Slideshow.** `SlideshowController.show()` walks the whole order with `ready()` and, for every photo without a
  display size, calls `prefetch(listOf(entry), NEXT)`, so a cold cache queues the whole order. Its loop of renderings
  on demand has no limit, and when every one fails it sets no timer and stays in `Loading`. `NEXT_AHEAD = 2` and
  `PREFETCH_AHEAD = 10` exist, but only `prefetchUpcoming()` uses them. Resting cancels only the timer, so queued jobs
  keep rendering at night, against `display-schedule` "Slideshow rests while the display is off".
- **Render cache.**
  - `RenderCache` has one worker and, since `nextcloud-hardening`, a download thread: a job whose remote original is
    missing is handed over (`handOver`), stays in `inFlight`, and the original is pinned in `OriginalsStore` from the
    start of its fetch (`SourceOriginals.fileOf` → `SourceRegistry.original(pin = true)`) until the re-queued jobs
    call `finish()`.
  - `enqueue` raises the priority of a pending name but never removes one, so a pending entry left behind would block
    that name for good.
  - `lastUse` maps names to a logical counter, and `touch` never sets a file time, so after a restart the order of use
    comes from the creation times.
  - `render()` lists the folder after every rendering to delete older versions (`deleteMatching`), `trim` reads the
    size of every file it knows, `init` sorts by `lastModified()` inside the comparator, and `peekAnyDisplay` scans all
    of `lastUse` for every candidate.
  - `removeAll` lists the folder once per call (#116). There is no check of free storage.
- **Index.** `PhotoIndexStore.edit` copies the photo map, compares it with the old one and tells nobody which IDs left.
  Renderings are removed only by `PhotoLibrary.delete` (`cache.remove`) and through the `onPhotosRemoved` parameter of
  `SourceRegistry` (source removal, orphans and, as `onRemoved`, vanished remote photos). A local scan that removes a
  file and every `LibraryEdit.skip`, local or remote, leave the renderings behind.
- **Image versions.** A new entry gets `(previous?.version ?: 0) + 1` (`LocalPhotoSource.read`,
  `RemoteSource.fetch`). A removed photo that comes back at its path gets version 1 again, the name `id-v1-…` of a
  rendering that may still be there, the same ETag and the same image URL.
- **Storage.** `PhotoLibrary.RESERVE_BYTES` and `OriginalsStore.RESERVE_BYTES` are 100 MiB. `FrameCore` passes no
  `freeBytes` to `SourceRegistry`, so originals use `File.usableSpace` instead of `PlatformInfo.freeBytes`. Downloads
  already read the reserve from `OriginalsStore` (`roomBytes = originals::roomOnDisk` and `hasRoomOnDisk` in
  `fetchForRendering`). The updater downloads into `filesDir/update`, not into the cache, and checks
  `2 × APK + 100 MiB` (`app-update`).
- **Scan.** `scanLoop` scans every 15 s whatever the display does, and `LocalPhotoSource.scan` always calls
  `index.edit`. `FrameCore.requestScan()` has no caller.
- **Batch.** `POST /photos:batch` calls `delete` or `setHidden` once per ID: one index edit each, and for a delete one
  `notifyChanged` each. The web UI sends single deletes through the batch endpoint as well.
- **Uploads.** `sanitizeName` cuts at 120 UTF-16 units and `sanitizeAlbum` at 64 per level, both able to split a
  surrogate pair. `move` ignores the result of `mkdirs`, renames and indexes each file on its own, so a failure of the
  second file leaves the first in place despite the KDoc's "All or nothing". There is no case resolution, while the
  photo folder of the reference frames sits on a FUSE file system that does not tell case apart.
- **Capture dates.** `AndroidMediaEngine.parseExifDate` uses a lenient `SimpleDateFormat`, so `0000:00:00 00:00:00`
  becomes a date in the year 1 or 2 BC, and neither source checks the result.
- **Slideshow view.** `SlideRenderer.preload` and `refit` poll every 200 ms during a transition; `clear()` resets
  neither `animating` nor `pendingOld`; `release()` calls `shutdownNow()`, after which a late `decoder.execute`
  throws on the main thread; the class KDoc still says photos are shown "on black".

### Not in this change

- **Batched removal** of renderings and originals per source (#116), and `OriginalsStore.remove(entry)` of the
  original plan: `removeAll` with one listing per batch exists, and this change keeps it.
- **A change of the updater** (owner's decision): `UpdateManager` and `app-update` keep `2 × APK + 100 MiB`, and the
  coupling "the updater trims the render cache" of the original plan is dropped. The render cache frees space by
  itself once free storage falls below the reserve (decision 2).
- **`FileObserver`** for the photo folder (bootstrap design, decision 8), a budget check on `PATCH /settings`,
  `relativeTo` micro-optimisations, NFC normalisation of uploaded names, and an upper bound for capture dates (the
  clock of a frame may be wrong, and unchanged files are never read again).
- **Moving the first listing of the render cache off the constructor.** The start logs its time; the device row
  measures it on the Frameo, and only a value above about 200 ms leads to a follow-up.

## Goals / Non-Goals

**Goals:**

- No work per photo of the library where work per change suffices: no listing per rendering, no size read per file
  per trim, no index edit per batch ID, no index copy per idle scan.
- Every exit of a photo from the index reaches the caches through one path, and a version never names two pictures.
- The frame stays above the free space at which Android 5 and 6 start trimming app caches, with one value for
  uploads, originals and renderings ahead.

**Non-Goals:**

- No change of the cache budget's meaning in `frame-settings`; the cache only shrinks below it when storage is short.
- No new API endpoint or field. Only texts in the contract change, and the delete endpoints get the 503
  `storage_unavailable` answer the upload already has (decisions 1 and 8, tasks group 1).
- No change to how often remote sources sync.

## Decisions

### 1. One storage reserve, computed once

- **Formula:** `StorageReserve.bytes(total) = min(total / 10, 500 MiB) + 100 MiB`, a pure object in
  `core/.../library/`. It is the frame's own reserve, based on the low-storage threshold of Android 5 and 6 (10 % of
  the data volume, at most 500 MiB; the 10 % rule holds up to Android 7.1, Android 8 and later use 5 %). It does not
  follow the device's setting at run time.
- **Why it is enough:** on Android 5 and 6, AOSP starts trimming app caches at about `(3 · low + full) / 4` free, about
  375 MiB with a threshold of 500 MiB, and trims until about `low + 2 · (low − start)`, about 750 MiB. A frame that
  keeps 600 MiB free stays above the start. That this holds on the frames is not verified (risks, task 14.1).
- **Volume size:** `PlatformInfo.totalBytes(dir)` with the default `dir.totalSpace` (API 9); `FakePlatform` can set it.
- **Where it is computed:** `FrameCore` computes it once from the cache folder and logs
  `Storage reserve: N MiB of M MiB` at the start. On the reference frames the cache and the photo folder are on the
  same data volume.
- **Who uses it:**
  - `PhotoLibrary` instead of `RESERVE_BYTES`, with `platform.freeBytes(root)` as today. The 507 message names the
    reserve in MiB.
  - `SourceRegistry` passes it and `platform::freeBytes` to every `OriginalsStore`; `OriginalsStore.RESERVE_BYTES`
    goes. The download limit and the check in `fetchForRendering` read it there already.
  - **Exception for displays** (owner's decision): a download of a missing original for a rendering somebody awaits
    may go below the reserve down to a floor of 100 MiB free, the old reserve (`OriginalsStore.ON_DEMAND_FLOOR_BYTES`).
    Downloads for the window ahead and for syncs keep the shared reserve (decision 2).
  - `RenderCache` (decision 2).
- **Alternative:** a reserve only for the caches, uploads keeping 100 MB. Rejected by the owner: an upload that pushes
  the frame below the threshold makes Android trim renderings and originals, which then come back at every sync.

### 2. The render cache below the reserve

- **Check:** before each rendering the worker reads `freeBytes(dir)`. Below the reserve it first removes its own
  entries unused longest, also below the budget, until the reserve is free again or only protected files are left.
- **Protected files:** neither this trim nor the trim to the budget deletes a name in `wanted`, a name a `get()`
  awaits, or a file the slideshow shows or has decoded as the next photo. The slideshow passes these two files with
  the window (`ahead(next, later, keep)`, decision 6). Deleting one of them would make the display skip a photo as
  undecodable.
- **Who may go below:** a job somebody awaits (a `get()` from the slideshow or a request, see decision 6) renders
  anyway. A job only for the window ahead fails with a storage error and is not retried until the window asks again.
  A warning is logged at most once an hour.
- **Originals:** the download thread decides the room of a download when it starts it. If one of its jobs is awaited,
  `SourceOriginals.fileOf` asks `SourceRegistry.original` for an on-demand fetch, and `RemoteSource` checks
  `hasRoomOnDisk` and the download limit (`roomBytes`) against the floor of 100 MiB instead of the reserve; otherwise
  against the reserve, as today. Below the floor the rendering fails with `STORAGE` and the slideshow goes on with
  others.
- **A job that arrives later:** a download started for the window only that fails with `STORAGE` hands an awaited job
  that joined it meanwhile over again, as a new on-demand download, in the same way as after a cancellation
  (decision 6).
- **Order of use across restarts:** `touch` sets the file time when the last one it set is more than an hour old
  (clock injected), so the start sorts by real use and Android's trimming, which removes old files first, spares the
  renderings in use.
- **Running total refreshed:** when a `peek` misses a name the index knows, Android or someone else has deleted files
  in the folder. The cache then rebuilds its index from one listing, at most once an hour. A rebuild triggered by free
  space would point the wrong way: Android's trimming leaves the frame above the reserve, and a frame that stays below
  it would list the folder for every rendering. (After review:) a rebuild does not take back a name that waits for its
  deletion, nor one of a version at or below its photo's tombstone, and it reads the time and size of unknown files
  outside the cache lock before it merges them.

### 3. An index of the render cache in memory

- **Contents:** name → (last use, size, file time last set), and photo ID → names. The photo ID is the part of a name
  before the first `-v<digits>-`. IDs (`local-…`, `nextcloud-…`, `s<8 hex>-…` followed by a SHA-1 in hex) contain no
  `-v`, and the render generation sits in the folder name (`renders-r1`), not in file names. A file whose name does not
  parse is deleted at the start.
- **Running total:** kept with every rendering, touch, trim and removal. `trim` returns at once within the budget and
  otherwise walks the names sorted by last use without reading any size. `totalBytes()` returns the total.
- **Start:** one listing, each file asked for its time and size once, then sorted. Its time is logged.
- **Older versions:** after a rendering, the older versions of that photo are found through photo ID → names, not by
  listing the folder. They are still removed only after the new version is rendered: removing them at once would take
  away a file that the display may be decoding as the photo on screen or the next one. (After review:) only versions
  below the new one go, never a file in `keep`, and a late rendering of a version below one already in the index is
  discarded, so it cannot take away the newer file on screen.
- **`peekAnyDisplay` and `ready`:** answered from photo ID → names; the disk is asked only for a hit.
- **Deleting:** `removeAll(entries)` takes the names out of the index at once and hands the files to a delete thread of
  low priority, so a source with 10,000 photos does not hold up a rendering on demand. Every name taken out to be
  deleted (removal, sweep, trim, older versions) counts as being deleted until its file is gone.
- **Test seam:** listing the folder goes through one function, as in `OriginalsStore`, so a test counts listings.
- **Alternative:** keep listing the folder and cache the result. Any external deletion would still need a listing, and
  the index has to exist anyway for the tombstones of decision 5.

### 4. One notification when photos leave the index

- **Where:** `PhotoIndexStore.edit` reports the entries whose ID left the index to a listener given to its
  constructor. `LibraryEdit` records the IDs touched by `remove` and `skip`; the photo map is no longer writable from
  outside, so these two are the only ways out. The report compares only those IDs, not the whole map.
- **When:** after the new state is published, still inside the edit's lock, so reports come in the order of the
  edits. The listener only changes memory and hands file work to a background thread. A failure of the listener is
  logged and does not make the applied edit fail (after review); the start sweep completes what it left.
- **What `FrameCore` wires to it:** `cache.removeAll(entries)` and, for remote photos, `SourceRegistry` removing their
  originals on its scope through the running source's `OriginalsStore.removeAll`. A removed source is no longer
  running at that point; its folder goes as a whole (decision 11).
- **What goes:** the `onPhotosRemoved` parameter of `SourceRegistry`, `onRemoved` of `RemoteSource`, and
  `cache.remove` in `PhotoLibrary.delete`. Scan, `skip` (local and remote), sync, source removal, orphan cleanup and API
  delete then all take the same path. (After review:) `RemoteSource.sync` still removes the originals of vanished
  photos itself before its trim, since the registry's removal runs later and the trim would count them and evict
  originals of photos that stay; the notification's removal then finds nothing left. The trims during the downloads
  leave the originals of photos the sync is going to remove out of the count, with their names and bytes worked out
  once per sync.
- **Leftovers:** after `index.load` and before `slideshow.start`, `FrameCore.start` removes the renderings whose photo
  ID is not in the index, and those whose version is above the entry's version (an index restored from its backup, or
  a debounced save lost at the end of the process, would otherwise meet renderings of versions it will assign again).
  That covers files from before the update and a background deletion cut off by the end of the process.

### 5. An image version the ID never had, and tombstones

- **Rule, applied inside the edit:** an entry that is new in the index gets `edit.newVersion`, the library version
  before the edit plus 1, clamped to `Int.MAX_VALUE`. A changed entry gets the version of the entry *in the edit*
  plus 1, not that of the snapshot taken before the probe or download. This holds for `LocalPhotoSource.scan`, the
  upload's `addAll` and `RemoteSource.sync`.
- **Why it is new:** every edit raises the library version by one, so no entry's version is ever above the library
  version. An earlier incarnation's versions were applied in earlier edits, so `edit.newVersion` is above all of them,
  and the incarnation's later versions grow from there.
- **Effect:** the file name of every rendering, the ETag and the image URL (`v=`) change, so neither the cache nor a
  browser holding an `immutable` answer serves the old picture. A quarantine of an earlier incarnation no longer
  matches.
- **Tombstones:** `removeAll` keeps the highest removed version per photo ID in memory. Under the cache lock (decision
  6a), which `removeAll` also takes, the worker drops a job whose version is not above its tombstone before it renders,
  before it hands the job to the download thread and before it renames the `.tmp` file. The tombstone check, the
  rename and the entry in the in-memory index are one critical section, so a `removeAll` cannot fall between them. A
  dropped job deletes its `.tmp` file. Tombstones live until the process ends, a few bytes per removed photo.
- **Contract:** `Photo.version` (the schema in `api/openapi.yaml`) reads "changes with every change of the file and is never reused for another
  file at the same path". It stays an integer.
- **Alternative:** a version counter per path stored in the index. It would grow without bound and need a new field
  that older builds drop.

### 6. A window of renderings ahead

- **Interface:** `DisplayImages.prefetch` becomes `ahead(next, later, keep)`. `CachedDisplayImages` calls
  `RenderCache.prefetchAhead(next, later, keep)`, which replaces the set `wanted` of target names and the protected
  files of `keep` (decision 2), and queues the missing ones as `NEXT` and `PREFETCH`.
- **Who awaits:** a `Pending` records whether a `get()` awaits it. One that is only in the window can be dropped;
  one awaited, at any priority, never is. This keeps the slideshow's refresh of the photo on screen, which awaits a
  `NEXT` rendering, safe. (After review:) it counts its awaiters; a `get()` that is cancelled before the result stops
  counting, and once none is left the job can be dropped like one of the window, and so can its download. Otherwise a
  rendering on demand or a refresh abandoned by resting or by fast swipes would keep rendering and downloading.
- **Each job knows its `Pending`:** after a drop, the same name can get a new `Pending` from a request while the old
  job still sits in the queue or in a download's job list. So every `Job` holds a reference to the `Pending` it was
  queued for. Drop decisions read `job.pending.awaited`, and every path that ends a job (worker, download failure,
  drop) removes the entry only with `if (inFlight[key] === job.pending) inFlight.remove(key)`, never by name alone.
  `Map.remove(key, value)` is not used, since it needs API 24.
- **Dropping:** the worker decides under the cache lock: a job whose `Pending` is not awaited and whose name is not
  in `wanted` is dropped. Its own `inFlight` entry goes, and its `Deferred` completes with an internal "no longer
  wanted" exception, never a `CancellationException`, which could end the slideshow's command loop. `finish()` runs,
  so a pinned original is released.
- **Downloads:** `prefetchAhead` also walks `pendingDownloads` under its lock.
  - A download not started whose jobs are all droppable leaves the queue together with its jobs.
  - A started one with only droppable jobs is marked cancelled. The fetch takes a cancel check, passed through
    `SourceOriginals.fileOf` and `SourceRegistry.original` into the download limits, so it ends at the next read and
    its `.part` goes.
  - A job that joins a cancelled download does not take the cancel back: the download ends at its next read, and the
    job is handed over again as a new download when it ends (on demand if awaited), instead of failing with it. One
    path, rather than a race between clearing the flag and a read that has already failed. A download that has an
    awaited job when the window changes is never cancelled.
- **`keep`:** the slideshow passes the files on screen and next up as `keep` with every window.
- **Resting:** `Command.Rest(true)` calls `ahead([], [], keep)` and cancels the renderings on demand and the refresh
  of the photo on screen (after review), whose current part is then rendered on wake. Waking sets the window again.
- **`show()`:** it still looks for a ready photo along the whole order, now an index lookup per photo. If none is
  ready, the window gets the first `NEXT_AHEAD` candidates as next and the rest up to `PREFETCH_AHEAD` as later. At
  most `MAX_DEMAND_ATTEMPTS = 3` candidates are rendered on demand. If all three fail, `armTimer()` runs, and the next
  tick tries the following ones.
  - **Found in the implementation:** the renderings on demand run in a coroutine of their own and report back as a
    command, while a photo on screen stays; awaiting them in the command loop would hold up a swipe and resting for as
    long as a download takes. A step does not stop at the photo on screen.
  - **Found in the implementation:** a step skips the due photo only for one rendered ahead, not for one of the last
    `PREFETCH_AHEAD` photos shown, whose rendering is left from its turn. Below the reserve nothing is rendered ahead,
    and in a library of up to 11 photos the photo on screen and the one before it (both protected, as `keep` and in
    the window) would otherwise take turns for good, against "Local photos below the reserve". The due photo is then
    rendered on demand.
  - **Found in the implementation:** the search walks the order with `next()`, which shuffles a new pass at the end of
    one; it returns to the position and the pass it began with (`Playlist.mark`/`reset`), so a search without a ready
    photo does not shuffle away the photos of the pass not shown yet. A result of the renderings on demand moves the
    order to where the search found that photo; after a change of the library or the order those positions are
    dropped and the result goes through a new search from the photo on screen. When all fail after a step forward the
    order goes to the last one tried, by its mark or, after such a change, by its ID; after a step back it goes back to where the search began, since the tick steps
    forward and would otherwise render the failed photos again. A photo that comes again across the end of a pass is
    a candidate once.
- **Log:** a failed rendering logs the stack trace only for unexpected errors, not for a `SourceException` or a dropped
  job.

### 6a. One cache lock and a fixed lock order

- **One lock:** `RenderCache` guards `inFlight`, `pendingDownloads`, `wanted`, `keep`, the tombstones and its
  in-memory index with one lock.
- **Order:** `SourceRegistry`'s lock → the index lock (`PhotoIndexStore.edit`) → the cache lock → the lock of an
  `OriginalsStore`. The removal listener runs inside the index lock and takes the cache lock in `removeAll`; it hands
  the removal of originals to the registry's scope and takes no registry lock.
- **Forbidden under the cache lock:** `index.edit`, any call into `SourceRegistry` or `SourceOriginals` (`fetch`,
  `release`, `onFrame`), `onCrashed` (it ends in `quarantine` → `index.edit`), rendering and file deletion. The worker
  collects what to call and calls it after leaving the lock. Otherwise the worker holding the cache lock while
  quarantining and the listener holding the index lock while removing would deadlock.

### 7. Local scan: no edit without a change, slower at night

- **No change:** `LocalPhotoSource.scan` returns without `index.edit` when nothing was probed and every known path and
  every known skipped path is still in the listing.
- **Duration:** each scan is timed. Above 1 s a warning is logged at most once an hour; every 15 s would overwrite the
  two log files of 512 KiB.
- **Cadence** (owner's decision): `scanLoop` waits 15 s while the display is on and 5 minutes while it is off; the
  night interval is a constructor parameter like `scanInterval`, so a test can make it short. The display going on
  calls `requestScan()`. `GET /photos`, `/albums` and `/skipped-files` call it through a hook when the last scan began
  more than 15 s ago, measured on `clock.monotonicMillis()`. A request does not wait for the scan; the client sees the change with its next
  fetch, prompted by the library version.
- **Not `GET /state`** (owner's decision, 22 Nov 2026): clients poll it, and an integration polling it would keep the
  frame scanning all night. An open web UI tab therefore sees a file copied over USB at night only when it loads
  photos, albums or skipped files again.
- **`requestScan()` stays** and gets its callers.

### 8. Batch actions in one edit

- **New methods:**
  - `PhotoLibrary.setHiddenAll(ids, hidden)`: one edit, unknown IDs are missing.
  - `deleteAll(ids)`: classifies each ID outside the index lock: unknown is missing, a remote photo is failed (spec
    "Batch with Nextcloud photos"), otherwise its file is deleted. A file already gone counts as deleted, a failed
    delete as failed. Then one edit removes the deleted entries, the listener removes their renderings, and one
    `notifyChanged(files)` follows.
- **Photo folder unavailable:** while the storage of the photo folder is not mounted or the folder cannot be listed
  (`LocalPhotoSource.available()`), a missing file says nothing, so a `deleteAll` with a local photo deletes nothing and
  the request answers 503 `storage_unavailable`, as an upload does (spec "Photo folder kept"). A batch of remote and
  unknown IDs answers as before (changed after review). The contract gets the 503 at `DELETE /photos/{photoId}` and
  `POST /photos:batch`.
- **Single delete:** `delete(id)` is `deleteAll` of one ID and keeps 404, 409 `read_only_source` and 500
  `delete_failed`; the batch result says which failed IDs are of a read-only source, so 409 and 500 do not depend on
  reading the index again.
- **Media store:** `MediaStoreNotifier` gets `notifyChanged(files: List<File>)` with a default loop; it stays a
  `fun interface`. `AndroidMediaStoreNotifier` calls `MediaScannerConnection.scanFile` once with all paths.
- **Route:** `POST /photos:batch` uses the new methods with the same `processed`, `missing` and `failed`.

### 9. Upload names, albums and all or nothing

- **File names:** `sanitizeName` splits off an image extension (`ImageFormats.hasImageExtension`); a long
  extension of another kind counts as part of the stem. The stem is cut at code point boundaries until the whole name
  has at most 200 bytes in UTF-8, then trimmed again. That leaves room below the 255-byte name limit for ` (n)` from
  `uniqueFile` and an extension that `move` appends. A cut at a code point can still separate the parts of an emoji
  sequence joined with ZWJ or of a flag; that is accepted, the name stays valid UTF-8.
- **Album levels:** `sanitizeAlbum` still cuts each level at 64 UTF-16 units, but never inside a surrogate pair. A
  level then has at most 192 bytes in UTF-8, below the 255-byte name limit, so no byte budget is needed there.
- **Case:** each album level is resolved against the existing folder by listing its parent: an exact match first, then
  a case-insensitive one that is a folder, otherwise a new folder. Never by `exists()`, which on the frames' FUSE says
  yes for `holiday` when only `Holiday` is there (found in review). The folders are created level by level, and only
  those this upload really created are recorded and removed again on a failure, and only as empty folders, so a file
  that answers to the same name in another letter case is never deleted. A folder that cannot be listed refuses the
  upload (500 `album_unreadable`) instead of counting as empty. The index path then matches the folder on the frame's FUSE file
  system. The same happens on a case-sensitive file system, so the JVM tests see it. `nested-albums` decision 1 (two
  albums that differ in case) then holds only for remote sources.
- **Folder check:** after `mkdirs`, `move` checks `isDirectory` and answers 400 `invalid_album` naming `album`
  otherwise. The contract text and `error.invalid_album` in `en.json` and `de.json` say "invalid or too long"; today
  the web UI shows "at most 255 characters" for any `invalid_album`.
- **All or nothing:** under one lock, the targets of all files are resolved, also against each other, and then all
  are renamed. If one rename fails, the ones already renamed and the album folders this upload created are deleted
  before anything is indexed. A scan that
  picked one up in between loses it again at the next scan. Then one `LocalPhotoSource.addAll` adds all of them in
  one edit, and one `notifyChanged(files)` follows.

### 10. Capture dates

- **Parse:** `AndroidMediaEngine.parseExifDate` parses the fields strictly (`java.time`, `ResolverStyle.STRICT`) and
  then places the local date-time in the device's zone, so that a time in the gap of the switch to summer time is moved
  forward instead of refused, as a strict `SimpleDateFormat` would (after review). The offset the camera recorded
  (`OffsetTimeOriginal`, `OffsetTime`) applies when present; a device zone that the time zone data does not know falls
  back to UTC instead of making every capture date unknown.
- **Plausibility in `:core`:** `CaptureDates.plausible(instant)` turns a date before 1800-01-01 into `null`.
  `LocalPhotoSource` and `RemoteSource` apply it before falling back to the file date. This is the real guard: the
  Robolectric tests run the host JDK's `java.text`, not the libcore of Android 5 and 6.
- **Stored entries:** after `index.load`, `FrameCore.start` corrects in one edit every entry whose capture date lies
  before 1800 to its file date. The library version rises, so web UI tabs reload.
- **What stays:** a date that the lenient parse already rolled into a valid-looking one cannot be recognised.

### 11. Removing a source answers at once

- **Lock:** `SourceRegistry.remove` keeps the index edit under its lock, which is quick, and deletes the originals
  folder on its scope, outside the lock.
- **Renderings:** they go through the notification (decision 4) to the delete thread.
- **Cut off:** a deletion cut off by the end of the process is completed at the next start, by `removeOrphans` for the
  originals folder and the sweep of decision 4 for the renderings. `removeOrphans` runs only when `sources.json` can
  be read (spec "Photos kept when the sources cannot be read"), so with an unreadable list the leftovers wait for a
  start that can read it.

### 12. SlideRenderer

- **No polling:** during a transition, `preload` only remembers `lastNext` and sets `preloadPending`, and `refit` sets
  `refitPending`. `done()` posts both to the main thread, so a `swapTo` that calls `done()` itself finishes first and
  the photo on screen is not decoded twice.
- **`clear()`:** after `cancel()` it sets `animating = false`, recycles `pendingOld` unless it is `current`, and
  resets `pendingOld`, `preloading`, `preloaded` and both flags. It also resets `translationX`, `alpha` and
  `translationZ` of both views, which a cancelled slide or fade leaves halfway. It raises a counter of its own, by which late decode
  results of `preload` are dropped; `generation` does not fit, since `show()` raises it.
- **`release()`:** sets `released`, removes the handler's callbacks and shuts the executor down gracefully. No
  `decoder.execute` runs after `released`, so none can throw `RejectedExecutionException` on the main thread.
- **Test seam:** the decoder executor is a constructor parameter; the default stays.
- **KDoc:** describes what `BitmapFit` does: scaled down to the view's size, black borders as pixels of the bitmap,
  views without a background.

### 13. Tests that count

- **Counters:** a listing counter in `RenderCache` (decision 3) and an edit counter in `PhotoIndexStore`, added in a
  first commit of their own, so the tests compile on the old code and fail there. The fixes then make them pass. The
  slideshow test counts through the existing `prefetch` of its fake first and moves to `ahead()` with group 7.
- **Small numbers:** the tests count, so N = 1,000 is enough and no wall-clock bound is needed.
- **The four claims:**
  - rendering N photos and then removing their source lists the render folder at most a few times in total, not once
    per rendering (#116 already made the removal itself list once);
  - a cold cache with 1,000 photos keeps the window at no more than `PREFETCH_AHEAD` distinct photos (counted over
    the names queued, not `queued`, which counts a raised job twice);
  - a rescan without a change makes no edit;
  - a batch over N IDs raises the library version by exactly 1.

## Risks / Trade-offs

- **Dropping stale jobs races with the download thread and its pins.**
  → One lock for the decision, each job removes only its own `Pending`, an awaited job is never dropped, a dropped job
  always runs `finish()`, and `RenderDownloadTest` gets cases for a dropped queued download, a cancelled running one
  that a new job then joins, a drop followed by a request for the same name before the cancelled download fails, and a
  raised job that keeps its result.
- **Deadlock between the index and the cache.** → The fixed lock order of decision 6a, and a test that quarantines a
  photo on the worker while an edit removes photos.
- **Downloads on demand go below the reserve.** On a frame with 100 to 600 MiB free, an evicted original that a
  rendering waits for is still downloaded (owner's decision). That includes the web UI's thumbnail and full-size
  requests: browsing remote photos whose originals were evicted can download many originals, down to 100 MiB free,
  and below the point where Android starts trimming app caches.
  → Never ahead of time; the render cache shrinks first (decision 2), and the trim of the originals keeps them within
  their source's budget. Below 100 MiB free such a photo is not shown, as the spec says. Before the rollout a read-only
  `df` on both frames shows whether either is in that range, and the reserve is in the start log.
- **The low-storage threshold, the trimming points and Android's trimming order are not verified on the frames.**
  → The formula is a constant in one place. Task 14.1 reads `dumpsys devicestoragemonitor` and the global settings
  `sys_storage_threshold_percentage` and `sys_storage_threshold_max_bytes` read-only; the device row reads the start
  log line and `df`.
- **`freeBytes` of the photo folder goes through FUSE on the reference frames.** It is not verified that it matches
  the free space of the data volume on Android 5 and 6.
  → The read-only check before the rollout compares `df` of the data volume and of the photo folder.
- **Every delete in the web UI changes shape**, since single deletes use `photos:batch` too.
  → e2e cases for a single delete and a batch delete; `DELETE /photos/{photoId}` keeps its answers.
- **The `version` contract changes.** A new photo at an old path has a version above 1.
  → The contract only promised an integer that changes. Tests that assume version 1 for a new photo are adapted.
  External API clients that assumed it would see larger numbers, still integers. The Home Assistant integration
  ha-openframe (`image.py`, `api.py`, `tests/fake_frame.py`) was checked: it assumes no `v=1` and builds image URLs
  from the version only.
- **Higher reserve, earlier refusals.** A nearly full frame refuses uploads (507) and downloads for syncs and
  renderings ahead (`storage`) about 500 MB earlier than today; downloads on demand keep the floor of 100 MiB.
  → Owner's decision; the 507 text names the reserve.
- **Scanning every 5 minutes at night delays USB changes while the display is off.**
  → The display going on and the web UI's photo, album and skipped-file lists scan at once.
- **Open package P11 plans to delete `requestScan()` as dead code.**
  → It now has callers; P11 has to drop that part or land after this change.
- **Background deletion can be cut off.** → The start sweep and `removeOrphans` finish it (decision 11).
- **Case resolution for uploads** sends an upload into an existing folder on every file system. → Intended; the spec
  and the decision record that `nested-albums` decision 1 now holds only for remote sources.
- **The render cache is in the display path, and device rows run only on the released build.**
  → The JVM tests cover the cache's paths, safe mode stops a crash loop, and a fix ships as a follow-up release.

## Migration Plan

- **Renderings:** left in the render folder from before the update, the files of photos not in the index are deleted
  at the first start, and files whose names do not parse as well.
- **Index:** existing entries keep their versions; only new incarnations get larger ones. The index format is
  unchanged; tombstones live only in memory.
- **Capture dates:** corrected once at the first start, with one rise of the library version.
- **Storage:** the reserve applies from the first start; downloads on demand keep the old floor of 100 MiB. Check the
  frames with a read-only `df` before the rollout.
- **Rollback:** install the previous release over it. It reads the same index; corrected capture dates and larger
  versions stay valid for it.
