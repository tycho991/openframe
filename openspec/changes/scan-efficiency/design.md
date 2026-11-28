# Design

## Context

See proposal.md for the motivation. Line references are to `main` at 0.15.0 plus the archive of the four 0.14/0.15
changes (commit 92cdd67). No change is active, so this one conflicts with none.

**The local scan on `main`:**

- `LocalPhotoSource.scan` (`library/LocalPhotoSource.kt:62-77`) times `scanFolder` and warns above 1 s at most once an
  hour.
- `scanFolder` (:79-175):
  - It lists the folder through the `listFiles` hook (:47). The default is `listImageFiles` (:244-249):
    `walkTopDown`, then `isFile`, the dot-name and extension filters, and `relativeTo(root).invariantSeparatorsPath`
    for each file.
  - Then it reads `length()` and `lastModified()` for each file (:102-103) and skips files whose size and time match
    the index entry or the skipped entry.
  - Every other file is probed with `read` (:115, :212-235), which calls `lastModified()` and `length()` once more.
  - With nothing probed and nothing missing it returns without an edit (:123, design decision 7 of
    `library-performance`).
  - Otherwise one `index.edit` applies all results and the removals at the end (:125-174).
- A failed read becomes a skipped entry, with reason `CORRUPT` unless it is an `ImageException` (:147-153). A
  non-`ImageException` is logged with its stack trace for every file (:151). The skipped entry stores `file.length()`
  and `file.lastModified()` again: both are 0 for a file that is gone.
- `AppLog` (`log/AppLog.kt:24-31`) folds repeated warnings only when the message is the same. "Error reading
  OF-Synth/synth-04358.jpg" differs per file, so nothing is folded. The file log keeps 2 × 512 KiB (:253-269).
- `FrameCore` builds the source at `FrameCore.kt:233` and scans in `scanLoop` (:783-800): every 15 s with the display
  on, every 5 minutes with it off.
- `PlatformInfo` (`platform/Platform.kt:33-79`) already holds file-system questions with JVM defaults
  (`freeBytes`, `totalBytes`, `storageMounted`). `AndroidPlatformInfo` (`android/.../AndroidPlatform.kt:46`) overrides
  them.
- `java.io.File` on Android goes through libcore: each of `isFile`, `isDirectory`, `length` and `lastModified` is its
  own `stat` call, and on a FUSE photo folder each one is a round trip through the `sdcard` daemon. Today a file costs
  four calls before the comparison: two for the listing, two for size and time.

**Measured on the Frameo (Android 6.0.1, ART, FUSE), 5,160 files, per scan, 10 rounds:**

| Variant | Wall | CPU |
|---|---|---|
| Today: `listImageFiles`, then `length` and `lastModified` | 4.0 s | 2.8 s |
| `File.list()` recursion, string prefix paths, `isFile` only for image names, then `length` and `lastModified` | 2.0 s | 1.4 s |
| `File.list()` and one `android.system.Os.stat` per entry (type, size, time) | 1.3 s | 0.8 s |

In the app the scan cost about 5 s of CPU per 15-s cycle with the display on, more than in the benchmark. The
difference is the comparison maps and garbage collection: the `HeapTaskDaemon` thread had 29 s of CPU after half an
hour. Every variant above allocates less than today.

## Goals / Non-Goals

**Goals:**

- One system query per entry of the photo folder in a scan, and no further `stat` for a file that is unchanged.
- The file facts a scan compares are the ones it listed; the probe of a changed file reads its time once more, as
  today, because it stores that time in the index.
- No known photo is read again because the way of reading times changed.
- A vanished file costs nothing but its removal.

**Non-Goals:**

- Watching the folder with `FileObserver` (inotify). On Android 5/6 inotify on the FUSE view does not report all
  changes made over MTP, and the 15-s scan has to stay anyway.
- Skipping unchanged folders by their own modification time. A file overwritten in place does not change the
  folder's time, so changed files would be missed (spec "Local photo folder": changed files).
- Changing the scan intervals or the 1-s warning.
- Remote sources: they do not list a local folder.

## Decisions

### 1. `FolderLister` on `PlatformInfo`, with a JVM default in `:core`

```kotlin
// core/.../platform/FolderLister.kt
data class FileFacts(val file: File, val size: Long, val modifiedMs: Long)

fun interface FolderLister {
    /** The image files below [root] by relative path ("a/b.jpg"), dot names excluded, or null if [root] cannot be listed. */
    fun list(root: File): Map<String, FileFacts>?
}
```

`PlatformInfo` gets `val folderLister: FolderLister get() = JvmFolderLister`. `FrameCore` passes
`platform.folderLister` to `LocalPhotoSource`, which replaces its `listFiles` hook with a `lister: FolderLister`
parameter. Tests that act while the folder is listed (`LocalPhotoSourceTest.kt:214, :574`) wrap the JVM lister
instead.

**`JvmFolderLister`** walks with an explicit stack of `(dir, prefix)` pairs instead of recursion, so a deep tree
cannot overflow the thread's stack.
- **Per folder:** one `File.list()`.
- **Per name:** a dot name is skipped. An image name gets one `isFile`; if that is false, an `isDirectory`, since a
  folder may be called `x.jpg`. Any other name gets one `isDirectory`.
- **For files:** `length()` and `lastModified()`.
- **Paths:** built as `prefix + name` with `/`, the form `invariantSeparatorsPath` gives today, so IDs stay the same
  (`PhotoEntry.idFor(source, path)`).

On the JVM this costs at most three queries per image file. It is the fallback and the tests' lister; the
benchmark's second row.

**`AndroidFolderLister`** (`:android`) uses the same walk, but one `Os.stat(path)` per name gives the type
(`S_ISDIR`, `S_ISREG`), `st_size` and the time. An `ErrnoException` with `ENOENT` means the entry is gone: it is left
out. Any other errno leaves out that entry and is counted for one log line per scan.

*Alternatives:*
- **`Os.lstat`:** rejected. `File.isFile` and `walkTopDown` follow symbolic links today, and `stat` keeps that.
- **A separate constructor parameter on `FrameCore`:** rejected. `PlatformInfo` already carries the other
  file-system questions, and `TestCore`, `FakePlatform` and the test server get the JVM default for free.
- **Keep `java.io` and only drop `relativeTo`:** rejected. It saves the path cost but keeps four stat calls per file
  over FUSE; the `sdcard` daemon's share stays.

### 2. Times must equal `File.lastModified()`: a self-check, then a fallback

The index stores `modifiedMs` from `File.lastModified()`, so a lister must give exactly the same number for an
unchanged file; otherwise every photo counts as changed, is probed again and gets a new version. How libcore computes
`lastModified()` is not part of the API: on Android 5/6 it is `st_mtime × 1000`, whole seconds, and later releases
may use the nanosecond field. `AndroidFolderLister` therefore computes the time as follows:

- **API 21–26:** `st_mtime * 1000`.
- **API 27 and later:** from `st_mtim` (`StructTimespec`, added in API 27) as `tv_sec * 1000 + tv_nsec / 1_000_000`.

**Self-check.** A `CheckedFolderLister(fast, fallback, check = File::lastModified)` in `:core` wraps it. At the first
listing of a process, it compares up to 20 regular files of the result with `check(file)`. If all match, or the
folder has no file yet, the fast lister is used from then on. At the first mismatch it logs once ("The fast folder
listing gives other file times (… vs …); the scan uses java.io") and uses the fallback for the rest of the process.
That first listing is then taken from the fallback, so no file counts as changed.

**Why in `:core`:** the check is JVM-testable with a fake fast lister. `:android` wires
`CheckedFolderLister(AndroidFolderLister, JvmFolderLister)`.

*Alternative:* always read the time with `lastModified()` and only the type and size with `Os.stat`. Rejected: that
is two calls per file again, for a case the check handles.

### 3. A vanished file is a removal, not a broken file

- **Before the probe:** an entry that is gone at the time of its `stat` is not in the listing (`ENOENT`, or
  `isFile`/`isDirectory` false), so the known entry counts as missing and is removed in the scan's last step, as
  today.
- **During the probe:** for a file that vanishes after the listing, the probe fails. After any failed `read` the scan
  asks `file.exists()`. If the file is gone, it records the path as vanished: no skipped entry, no log line. It is not
  added or updated. Its known entry, if any, is removed in the scan's last step, since its path is taken out of the
  listing. Without a known entry nothing is left.
- **Skipped entries:** they store the facts from the listing, not a second `length()`/`lastModified()`.

### 4. Logging of read errors per scan

- **`ImageException`:** not logged, as today. The file is on the skipped list with its reason.
- **Other errors:**
  - The first one in a scan, for a file that still exists, is logged with its stack trace, as today.
  - Each further one only increments a counter. At the end of the scan one line says
    "N more files could not be read (see the list of skipped files)".
- **Why not use `AppLog`'s repeat folding:** it keys on the message, which contains the path. Moving the path out of
  the message would hide which file failed first.

### 5. Applying in steps

While probing, the scan remembers when it last applied its results (on `clock.monotonicMillis()`). When at least
10 s have passed since then and something was probed, it applies the probed results in an `index.edit` of their own,
with the same rules as today's edit, then clears them.

- **Last step:** the scan's last edit applies the rest and the removals, as today. Removals stay at the end: the
  identity check `known[entry.path] === entry` (:160-165) needs the entries as they were when the scan began, and an
  intermediate edit does not touch the entries of missing paths.
- **Result:** the `ScanResult` is the sum of the steps. One "Scan: …" line at the end gives the totals.
- **30-second limit:** a scan starts at most 15 s after a copy with the display on, and its first step comes 10 s
  after the scan began. So the first photos are in the index within about 25 s, plus the listing (about 1.3 s for
  5,000 files on the Frameo).
- **Cost:** each step is a library version and an index write (`PhotoIndexStore`, written in the background). A
  large addition of 5,000 files over 5.8 minutes makes about 35 steps instead of one. Every window and client sees the
  new photos earlier, and the renderer's "next 10" moves as it does after an upload.

*Alternative:* steps by count, every 500 files. Rejected: with a slow decoder 500 files can take more than 30 s.

### 6. What stays

- The comparison by size and time, the "no edit without a change" rule (:123), the dot-name rules, the mounted and
  listable checks (:80-89), `requestScan` and the cadence.
- `LocalPhotoSource.add`/`addAll` (uploads) still use `relativePath` (:252) for single files, where its cost does not
  matter. `read` keeps reading `lastModified()` and `length()`, since it stores them.

## Risks / Trade-offs

- **[Risk] A device whose `lastModified()` is not `st_mtime × 1000` / the `st_mtim` milliseconds.** → The self-check
  (decision 2) falls back to `java.io` before any file is compared. The Lenovo and the Frameo device rows look for the
  log line. A device that falls back is no worse off than today.
- **[Risk] The self-check sees only files whose time happens to be a whole second.** A frame on API 27 or later whose
  first 20 files all have a whole-second time could pass with a lister that drops the milliseconds, and later files
  would then count as changed once. → The Android lister computes exactly what libcore does on the same API level, so
  the check is a guard, not the only protection. A one-time re-probe costs time, not data: comments, hidden states
  and sections are taken over (`withStateOf`).
- **[Risk] Steps make a removal and an addition of the same photo appear at different times** (a file renamed during
  a large addition). → The rename appears as an addition in a step and the removal at the end of the same scan, at
  most one scan later than today. The section, comment and hidden state of a renamed file are not carried over today
  either.
- **[Risk] `Os.stat` on a path with invalid UTF-8.** → `File.list()` already returns the names as Java strings; the
  path given to `Os.stat` is the same one `java.io` would use.
- **[Trade-off] More code in `:android`.** → The lister is about 40 lines, and its error and time mapping is tested
  as a pure function on the JVM. Robolectric cannot run `Os.stat` against a real FUSE folder; the device rows cover
  that.

## Migration Plan

Nothing is stored differently. After the update the first scan lists with the new lister; thanks to the self-check
it finds the known photos unchanged. Rollback is the previous APK; the index is the same.
