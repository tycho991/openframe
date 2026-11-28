# Proposal

## Why

The device rows of `library-performance` on 28 Nov 2026 put 5,000 synthetic photos into the Frameo's local photo
folder and found three problems with the local scan. They are findings 103 and 104 in the private findings list.

- **An unchanged scan costs too much.** With the display on, the frame scans every 15 s. With 5,000 local photos this
  kept the app's main process at about 22–24 s of CPU per minute, about 38 % of one core, and the storage daemon
  (`sdcard`, FUSE) at about 10 s per minute. Without the test album the app used 1.5 s per minute. Each night scan
  without a change cost about 10 s every 5 minutes. The scan found correctly that nothing had changed
  (`libraryVersion` stayed the same); it only spent that much to find out.
  - **Where the time goes.** A benchmark on the Frameo's own ART runtime over its FUSE storage (5,160 files) timed
    today's listing at 4.0 s wall time and 2.8 s CPU per scan. `walkTopDown` and `isFile` cost two stat calls per
    file, `length()` and `lastModified()` two more, and Kotlin's `relativeTo` alone took 0.5–0.7 s. The same listing
    with `File.list()` and one `android.system.Os.stat` per entry took 1.3 s wall time and 0.8 s CPU. On the PC's JVM
    the whole listing takes 46 ms, so tests there cannot show the cost.
- **A file that vanishes during a scan is decoded.** Deleting the 5,000-file album while a scan ran made every file
  that was gone by the time it was stat'ed count as changed: `length()` and `lastModified()` return 0 for a missing
  file. The scan then sent it to the decoder. It logged "Error reading … The decoder failed" with a full stack trace
  about every 26 ms and kept one core at 100 % for almost 3 minutes. Then it put all 5,000 on the skipped list as
  broken files, and only the next scan removed them. The stack traces rotated the frame's log several times, and the
  history before the flood was lost. The same happens when a large folder is deleted over USB, which takes long on
  MTP.
- **Many new photos appear only at the end.** The scan that took up the 5,000 new files ran for 5.8 minutes and put
  nothing into the index until it ended. The spec promises that a new file is in the index 30 seconds after the
  change at the latest while the display is on. Decoding 5,000 files cannot meet that. Showing the first ones after
  minutes instead of seconds is not needed either.

## What Changes

- **One query per entry.** The listing of the photo folder moves behind a new platform interface, `FolderLister` in
  `core/.../platform`. For every entry it returns the relative path, whether it is a regular file, its size and its
  modification time, from one query.
  - **Implementations:** `:android` implements it with `File.list()` and one `android.system.Os.stat` per entry. The
    JVM default in `:core` uses a `File.list()` recursion with string prefix paths and asks only image-named entries
    whether they are files. Tests also use the JVM default.
  - **No further stat calls:** the scan compares with the returned facts and calls neither `length()` nor
    `lastModified()` again.
  - **Self-check:** at the first scan of a process, the Android lister checks its times against
    `File.lastModified()` for up to 20 files. If any differs, it falls back to the JVM lister for that process and
    logs this once. A different time would make every known file look changed, and the scan would decode the whole
    folder again.
- **Vanished files are not decoded.** An entry that is gone by the time it is stat'ed is not in the listing. A file
  that vanishes after the listing and before it is decoded is noticed when its read fails and the file no longer
  exists. Such a file is neither added nor put on the skipped list; this scan or the next one removes its entry.
- **One stack trace per scan at most.** A scan logs a failed read of a file that still exists as one line without a
  stack trace. At most one stack trace per scan comes on top, for the first unexpected error. The other read errors
  of the scan end in a count ("4,999 more files could not be read").
- **New photos in steps.** While a scan decodes new or changed files, it applies what it has read so far at least
  every 10 seconds, instead of only at the end. Removals stay in the scan's last step, as today.
- **Unchanged:** the scan intervals (15 s with the display on, 5 minutes with it off), how a change is recognised
  (size and modification time), the IDs of photos, the index format and the API.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-library`: three new requirements, "Cost of an unchanged scan", "Files that vanish during a scan" and "Many
  new photos at once". "Local photo folder" keeps its text, since its 30-second rule plus a sentence on many files
  would go over 500 characters; the new requirement refers to it instead.

## Impact

- **Code:**
  - `:core`:
    - `platform/` gets the new `FolderLister` with its JVM default.
    - `library/LocalPhotoSource.kt`: the listing, the comparison, the vanished-file check, the logging and the steps.
    - `FrameCore` and `TestFrame` wire it up.
  - `:android`: a new `AndroidFolderLister`, passed in by `FrameApp`/`AndroidPlatform`. It uses only the framework
    (API 21), no AndroidX.
- **Tests:**
  - `LocalPhotoSourceTest` and `ScanCadenceTest`: the `listFiles` hook changes type.
  - New tests:
    - a counting lister for "one query per entry, no file opened";
    - a lister whose files vanish before the read;
    - a slow fake engine for the steps;
    - a check of the self-check;
    - Robolectric tests for the Android lister's mapping.
- **API, contract, web UI, index format:** unchanged.
- **Devices:** new rows in the private device checklist. The Frameo with 5,000 synthetic photos again, compared with
  the numbers of 28 Nov: CPU with the display on and at night, a folder deleted during a scan, many photos at once.
  The Lenovo gets a check of the self-check line.
