# Proposal

## Why

Audit package P8 found that the library does not scale to the 10,000 photos the specs name, and that a few smaller
things are wrong. On a cold cache the slideshow queues a rendering for every photo of the order, and the queued jobs
keep running while the display is off. The render cache lists its folder after every rendering and reads the size of
every file when it trims. The local scan copies the index every 15 seconds, also at night. A batch of 10,000 IDs is
10,000 index edits. Renderings of photos that left the index stay behind, and a new file at the path of a removed
photo gets version 1 again, so it can be served the old picture. The 100 MB reserve lies below Android's own
low-storage threshold, where Android starts to trim app caches. The prerequisites the plan named are on `main` now
(render generation in the folder name, safe mode and quarantine, the download thread of `nextcloud-hardening`); #116
already batched the removal of renderings and originals. All 14 findings of the package are still open on 0.13.0, two
of them in part.

## What Changes

- **Bounded rendering ahead.**
  - The slideshow renders ahead only the next 10 photos of the order. Queued renderings of photos that left this
    window are dropped before they start, also when the display goes off, together with a download of a missing
    original that nobody else waits for.
  - Without any rendered photo the slideshow renders at most 3 photos on demand in a row, then tries the next ones
    after the interval; today it stays in "loading" without a timer when all fail.
- **One storage reserve** (owner's decision): Android's low-storage threshold plus 100 MiB, about 600 MiB on the
  reference frames, for uploads, downloads of originals and renderings ahead.
  - To keep it, the render cache shrinks below its budget.
  - Only renderings someone waits for go below it. The download of a missing original for such a rendering may go
    below it too, down to 100 MiB free (owner's decision), so remote photos keep showing on a frame with 100 to
    600 MiB free; downloads for renderings ahead and for syncs keep the reserve.
  - The updater keeps its own rule (`app-update`, twice the APK plus 100 MiB) and does not change.
- **Rendered sizes follow the index.** Every way a photo leaves the index (API delete, scan, skip, sync, removed
  source, orphan cleanup) removes its renderings through one notification from the index. A late rendering of a
  removed photo is discarded, and the start removes renderings of photos that are not in the index.
- **New file at an old path.** A photo that comes back at a path gets an image version its ID never had, so the file
  name, the ETag and the image URL change.
- **Render cache without directory listings.** An index of its files in memory with a running total replaces the
  listing after every rendering and the size reads when it trims.
- **Removing a source answers at once.** Originals and renderings are deleted in the background, and the next start
  completes a deletion that was cut off.
- **Local scan.**
  - A scan without a change does not edit the index.
  - While the display is off (owner's decision), the frame scans every 5 minutes, at once when the display goes on,
    and at once when a client fetches photos, albums or skipped files. While it is on, the 15-second scan stays.
- **Batch actions in one step.** Hide, show and delete of a batch are one index edit and one library version, with one
  notification of the system's media store. Single deletes in the web UI use the batch endpoint, so they change too.
- **Uploads.**
  - File names are shortened to 200 bytes of UTF-8 at character boundaries, with their image extension kept.
  - An album level that exists as a file gives 400 `invalid_album`.
  - An upload of several files is all or nothing for real, as one index edit.
  - An album that differs from a local folder only in letter case goes into that folder.
- **Capture dates.** An EXIF date that is not a calendar date or lies before 1800 counts as missing; stored entries
  with such a date get their file date at the next start.
- **Slideshow view on the frame.** No 200 ms polling during a transition, a complete reset in `clear()`, a release that
  cannot throw on the main thread, and a class comment that matches the code.
- **Tests that count.** Listing and edit counters prove the scaling claims with small numbers instead of timings.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-library`: "Local photo folder", "Stable photo IDs" and "Upload" change; new requirements "Local scan while
  the display is off", "Storage reserve", "Going below the storage reserve", "Rendered sizes follow the index", "Batch
  changes", "Names of uploaded files", "Upload into an album in another letter case", "Implausible capture dates".
- `remote-sources`: "Storage of the originals" and "Remove a source" change.
- `slideshow-display`: new requirement "Bounded rendering ahead".
- `display-schedule`: "Slideshow rests while the display is off" gets a scenario for renderings queued before the
  display went off.

## Impact

- `core`:
  - `library/`: `RenderCache` (index, running total, reserve, window of renderings ahead, tombstones, delete thread),
    `PhotoIndex` (`PhotoIndexStore` removal notification, new image versions, edit counter), `LocalPhotoSource`,
    `PhotoLibrary` (reserve, batch, upload names and albums), a new `StorageReserve` and `CaptureDates`.
  - `sources/`: `SourceRegistry` (no `onPhotosRemoved`, background deletion), `RemoteSource` (no `onRemoved`, new
    image versions, plausible dates), `OriginalsStore` (the shared reserve).
  - `slideshow/SlideshowController` (window, at most 3 renderings on demand), `platform/` (`PlatformInfo.totalBytes`,
    `MediaStoreNotifier` for several files), `api/LibraryRoutes` (batch, scan on reads), `FrameCore` (wiring, scan
    cadence, start sweep and date correction, reserve log line).
- `android`: `SlideRenderer`, `AndroidMediaEngine.parseExifDate`, `AndroidMediaStoreNotifier`.
- `api/openapi.yaml`: the upload text (reserve, nothing stored on any error, `invalid_album` also for an album level
  that is a file), the `PhotoEntry.version` text (never reused for another file at the same path). Then
  `npm run gen:api`.
- `webadmin`: `error.invalid_album` in `en.json` and `de.json`; e2e coverage of single and batch deletes.
- Docs: the README names the reserve instead of 100 MB.
- Behaviour visible to users:
  - A nearly full frame refuses uploads and stops downloading originals for syncs and renderings ahead about 500 MB
    earlier than today; an original the slideshow waits for is still downloaded down to 100 MiB free.
  - A new photo at an old path has a version number above 1.
  - While the display is off, a photo copied over USB appears within 5 minutes, or when the display goes on or the web
    UI loads photos.
- Device rows (private checklist): a large synthetic library on the Frameo (cold start, scan, sync, source removal,
  memory), the letter-case upload on the Lenovo, a batch delete, a display that goes off during a crossfade, the
  reserve from the log, and the existing upload row moved to the reserve.
