# Tasks

One implementation pull request for the whole change. Line references are to `main` at 92cdd67; check them again
before editing. No contract or web UI change.

## 1. Failing tests first

- [ ] 1.1 In `LocalPhotoSourceTest`, against `main`, write the tests for the new behaviour and note in the pull
  request which of them fail there:
  - **Facts from the listing are trusted:** a lister wrapper reports one known file with its real size but another
    time. The scan probes exactly that file, and no other.
  - **No file opened** (`photo-library` › Cost of an unchanged scan › Nothing changed with 5,000 photos): a counting
    fake engine and 5,000 known files. A second scan probes nothing, and `libraryVersion` stays the same.
  - **Vanished before the probe** (› Files that vanish during a scan › Folder deleted during a scan): a lister reports
    50 paths of known files, the test deletes them before the scan probes, and the engine throws `IOException` for
    missing files. No skipped entry, no "Error reading" line; after the next scan the entries are gone.
  - **Vanished, unknown:** the same for 50 new paths that never were in the index. Nothing is added or skipped.
  - **Many unreadable files** (› Many unreadable files): 100 new files whose probe throws `IOException` while they
    exist. All 100 are skipped as `CORRUPT`, the log holds one "Error reading" entry with a stack trace and one line
    "99 more files could not be read".
  - **Steps** (› Many new photos at once › 5,000 photos copied at once): a fake engine that advances the `TestClock`
    by 1 s per probe, and 30 new files. The library version changes at least twice during the scan, and the first
    step holds the photos read in the first 10 s.

  Verify: the tests compile against the new `FolderLister` interface (1.2 adds it first if needed) and the behaviour
  tests fail on today's scan.
- [ ] 1.2 Add `FileFacts` and `fun interface FolderLister` in `core/.../platform/FolderLister.kt` (design decision 1)
  and `val folderLister: FolderLister` with a JVM default on `PlatformInfo` (`platform/Platform.kt:33-79`). Verify:
  `./gradlew :core:compileKotlin` and `:core:checkNoAndroidImports` are green.

## 2. JVM lister and self-check in `:core`

- [ ] 2.1 `JvmFolderLister` (design decision 1): an explicit stack of `(dir, prefix)`, one `File.list()` per folder,
  dot names skipped, `isFile` only for image names (`ImageFormats.hasImageExtension`), otherwise `isDirectory`, paths
  as `prefix + name` with `/`, `length()` and `lastModified()` for files. It returns `null` when `root.list()` is
  `null`. Verify: a unit test compares its keys with today's `listImageFiles` on a tree with nested folders, dot
  folders, dot files, a folder named `x.jpg`, non-image files and upper-case extensions: same paths and same IDs; a
  tree 200 levels deep does not overflow the stack.
- [ ] 2.2 `CheckedFolderLister(fast, fallback, check)` (design decision 2): at its first listing it compares up to 20
  files with `check`, then uses `fast` for good, or logs once and uses `fallback` for the process; a folder without
  files postpones the check to the next listing. Verify: unit tests with a fake fast lister that is off by 500 ms
  (`photo-library` › Cost of an unchanged scan › Times read another way: a known photo is not probed again, one log
  line), one that matches (no log, `fast` used), and an empty folder.

## 3. The scan

- [ ] 3.1 `LocalPhotoSource` takes `lister: FolderLister` instead of `listFiles` (:47). `scanFolder` (:79-175)
  compares with the listed `FileFacts`, without `length()`/`lastModified()` for listed files. A `null` listing keeps
  today's "cannot be listed" path (:85-88). `FrameCore.kt:233` passes `platform.folderLister`. The two tests that use
  the old hook (`LocalPhotoSourceTest.kt:214`, `:574`) wrap `JvmFolderLister`. Verify: the "facts trusted" and "no
  file opened" tests of 1.1 pass, and the whole `LocalPhotoSourceTest`, `ScanCadenceTest`,
  `RenderedSizesFollowIndexTest` and `TrashStoreTest` stay green.
- [ ] 3.2 Vanished files (design decision 3): after a failed `read`, `file.exists()` decides. A vanished path is taken
  out of the listing and gets no skipped entry and no log line. Skipped entries store the listed facts. Verify: both
  "vanished" tests of 1.1 pass.
- [ ] 3.3 Read errors per scan (design decision 4): the first non-`ImageException` of a scan for an existing file is
  logged with its stack trace; the others are counted into one line at the end. Verify: the "many unreadable files"
  test of 1.1 passes, and the existing tests that expect an "Error reading" line still pass.
- [ ] 3.4 Steps (design decision 5): while probing, apply the probed results in their own `index.edit` once 10 s on
  `clock.monotonicMillis()` have passed since the last step. The last edit applies the rest and the removals, with
  the identity check unchanged; `ScanResult` is the sum, and one "Scan: …" line comes at the end. Verify: the "steps"
  test of 1.1 passes; `LocalPhotoSourceTest`'s "Restore during a scan" and upload-during-scan tests stay green.

## 4. Android lister

- [ ] 4.1 `AndroidFolderLister` in `:android` (design decisions 1 and 2): the walk of `JvmFolderLister` with one
  `android.system.Os.stat` per name. It maps `S_ISDIR`/`S_ISREG`, `st_size`, and the time from `st_mtime * 1000` below
  API 27 and from `st_mtim` from API 27 on. `ENOENT` leaves the entry out; other errnos leave it out and are counted
  for one log line per scan. Put the mapping (mode, size, time fields, API level → facts) in a pure function. Verify:
  a JVM unit test of that function for both API ranges, for a folder, a regular file, another type and `ENOENT`;
  `./gradlew :android:lintDebug` is green; framework only, no AndroidX.
- [ ] 4.2 `AndroidPlatformInfo.folderLister` (`android/.../AndroidPlatform.kt:46`) returns
  `CheckedFolderLister(AndroidFolderLister, JvmFolderLister, File::lastModified)`. Verify: a Robolectric test in
  `:android` that the platform hands out a `CheckedFolderLister`; `TestCore` still uses the JVM default.

## 5. Checks

- [ ] 5.1 `./gradlew check` is green (formatting, tests, API 21 check, Android Lint). Verify: the command output.
- [ ] 5.2 `openspec validate scan-efficiency --strict` exits 0 without warnings. Verify: the output.
- [ ] 5.3 In the pull request body, name the device rows of group 6 and the numbers of 28 Nov they compare with.
  Verify: the body.

## 6. Device rows after the release (main session, privately)

Each persistent device change only with the owner's consent at that moment.

- [ ] 6.1 The main session adds rows to the private device checklist, each with its spec reference. Verify: the rows
  exist with their references.
  - **Both frames:** no "fast folder listing gives other file times" line after the update, and no known photo
    probed again (no "Scan: … updated=" line at the first scan) (`photo-library` › Cost of an unchanged scan › Times
    read another way).
  - **Frameo with the 5,000 synthetic photos of 28 Nov** (made again the same way, outside the repository):
    - CPU of the main process with the display on, per minute, from `/proc/<pid>/stat`: compare with 22–24 s
      before and 1.5 s without the album, target at most 8 s;
    - an unchanged night scan: compare with about 10 s, target at most 3 s;
    - the `sdcard` daemon in both cases;
    - all from `/proc/<pid>/stat` (› Nothing changed with 5,000 photos).
  - **Frameo, the 5,000 copied at once with the display on:** the first photos are in the index within 30 s, and
    the count grows during the scan (› Many new photos at once).
  - **Frameo, the album deleted in the shell during a scan:** no "Error reading" line, no skipped entries, no CPU
    spike after the scan, the log not rotated by it (› Files that vanish during a scan).
- [ ] 6.2 Run the rows of 6.1 after the release, updating through the updater, with the owner's consent for the
  update, the synthetic photos and their removal. Verify: results entered and the test data removed; findings 103 and
  104 closed or annotated; anything that differs gets a finding in the private findings list.

## Workflow follow-up

- Archive the change after the device rows of 6.2 are done.
