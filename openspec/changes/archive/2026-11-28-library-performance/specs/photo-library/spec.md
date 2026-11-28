## MODIFIED Requirements

### Requirement: Local photo folder
The frame SHALL have a photo folder that is reachable over USB (MTP) without root. Subfolders SHALL be scanned. New, changed and removed files SHALL be in the index 30 seconds after the change at the latest while the display is on. Files that the frame itself creates or deletes, for example through upload, SHALL afterwards also be visible correctly over USB. While the storage is unavailable, entries SHALL NOT be removed (see "Photo folder kept").

#### Scenario: Photo copied over USB
- **WHEN** a photo is copied into the photo folder over USB while the display is on
- **THEN** it is in the index and visible after 30 seconds at the latest

#### Scenario: File removed
- **WHEN** a file is deleted from the photo folder while the display is on
- **THEN** its entry disappears from the index after 30 seconds at the latest

#### Scenario: Uploaded photo over USB
- **WHEN** a photo is uploaded through the admin interface and the frame is connected over USB afterwards
- **THEN** the photo is visible in the photo folder

### Requirement: Stable photo IDs
Every photo SHALL have an ID that derives from its relative path and stays the same across restarts and rescans. If a file is replaced at the same path, the ID SHALL stay the same, but its image version SHALL change so that caches become invalid. If a file appears at the path of a photo that had left the index (deleted, skipped, or gone from its source), it SHALL get the same ID and an image version that this ID never had before.

#### Scenario: Restart
- **WHEN** the app restarts
- **THEN** all photos have the same IDs as before

#### Scenario: File replaced
- **WHEN** `holiday/1.jpg` is replaced by a different file of the same name
- **THEN** the photo keeps its ID, its image version increases and its hidden state is kept

#### Scenario: Another file at the path of a removed photo
- **WHEN** `holiday/1.jpg` is deleted, a scan removes it, and then a different photo of the same dimensions is copied to `holiday/1.jpg`
- **THEN** the photo has the old ID, an image version it never had, a new ETag and a new image URL, and its thumbnail and display size show the new picture

### Requirement: Upload
It SHALL be possible to upload photos through the API, one or several, optionally into an album. Uploads SHALL be streamed to disk instead of being held in memory. File names and album paths SHALL be sanitized so that no file can end up outside the photo folder. An album path SHALL have one or more folder levels separated by `/` and at most 255 characters. An upload after which less free storage than the storage reserve would remain SHALL be rejected.

#### Scenario: Upload into album
- **WHEN** two JPEG files are uploaded with the album `family`
- **THEN** they lie in `family/`, are in the index immediately and the response contains their IDs

#### Scenario: Upload into a sub-album
- **WHEN** a JPEG file is uploaded with the album `family/2025`
- **THEN** it lies in `family/2025/` and belongs to the album `family/2025`

#### Scenario: Album path too long
- **WHEN** a file is uploaded with an album path of more than 255 characters
- **THEN** the API answers with 400 naming the field `album` and stores nothing

#### Scenario: Path traversal
- **WHEN** a file named `../../evil.jpg` is uploaded, or a file is uploaded into the album `../../x` or `a/../../x`
- **THEN** it ends up sanitized in a folder below the photo folder or the upload is rejected with 400, and no file is created outside the photo folder

#### Scenario: Same name
- **WHEN** a file is uploaded whose name already exists in the target folder
- **THEN** the existing file is not overwritten, instead the new file receives a unique name

#### Scenario: Unsupported format
- **WHEN** a file is uploaded that has no supported format
- **THEN** the API answers with 415 and stores nothing

#### Scenario: Storage full
- **WHEN** less free storage than the storage reserve would remain after an upload
- **THEN** the API answers with 507, stores nothing and leaves no partial file behind

### Requirement: Photo folder kept
The photo folder chosen at the first start SHALL be kept; the frame SHALL NOT switch to internal storage while the external storage is unavailable. While the storage is not mounted or the folder cannot be listed, a scan SHALL NOT remove entries, and uploads and deletes of local photos SHALL be rejected with 503 and the error code `storage_unavailable`. A folder deleted over USB on mounted storage SHALL be created again, and its entries removed.

#### Scenario: Storage mounted late after a restart
- **WHEN** the device restarts and its storage is mounted only after the app has started
- **THEN** no photo disappears from the library, and comments, sections and hidden states are kept

#### Scenario: Upload while the storage is unavailable
- **WHEN** a photo is uploaded while the storage is not mounted
- **THEN** the API answers 503 with `storage_unavailable` and stores nothing

#### Scenario: Delete while the storage is unavailable
- **WHEN** a client deletes a local photo, alone or in a batch, while the storage is not mounted
- **THEN** the API answers 503 with `storage_unavailable`, and the photo stays in the library

#### Scenario: Folder deleted over USB
- **WHEN** the whole photo folder is deleted over USB
- **THEN** the folder exists again after the next scan and no photo of the folder remains in the library

## ADDED Requirements

### Requirement: Local scan while the display is off
While the display is off, the frame SHALL scan the photo folder at least every 5 minutes, at once when the display goes on, and at once when a client fetches photos, albums or skipped files and the last scan is more than 15 seconds old. A scan that finds no change SHALL leave the library and its version unchanged.

#### Scenario: Photo copied at night
- **WHEN** the display is off and a photo is copied into the photo folder over USB
- **THEN** it is in the index after 5 minutes at the latest

#### Scenario: Display goes on
- **WHEN** a photo was copied into the photo folder while the display was off, and the display goes on a minute later
- **THEN** the photo is in the index within a few seconds after the display went on

#### Scenario: Admin interface at night
- **WHEN** the display is off, a photo was copied over USB a minute ago and a client fetches the photo list
- **THEN** the photo is in the index within a few seconds, and the client finds it when it fetches the list again

#### Scenario: Nothing changed
- **WHEN** a scan runs and no file in the photo folder has changed
- **THEN** the library version stays the same

### Requirement: Storage reserve
The frame SHALL keep a storage reserve of 10 % of its data volume, at most 500 MiB, plus 100 MiB, based on the low-storage threshold of Android 5 and 6. Uploads, downloads of originals and renderings ahead of time SHALL leave the reserve free, except as "Going below the storage reserve" allows. To keep it, the render cache SHALL remove its entries unused longest, also below its budget, but never those of the photo on screen, of the next photos or ones a request waits for.

#### Scenario: Reference frame
- **WHEN** the frame's data volume holds 16 GiB
- **THEN** the storage reserve is 600 MiB, and the log names it at the start

#### Scenario: Small volume
- **WHEN** the frame's data volume holds 3,000 MiB
- **THEN** the storage reserve is 400 MiB

#### Scenario: Storage runs short
- **WHEN** the free storage drops below the reserve while the cache of rendered sizes is within its budget
- **THEN** the cache removes the renderings unused longest until the reserve is free again or only those of the photo on screen, of the next photos and of waiting requests are left, the photo on screen stays, and the photos stay in the library

### Requirement: Going below the storage reserve
Only renderings someone waits for, the photo the slideshow shows now or an image a client asks for, SHALL be allowed to go below the storage reserve. Fetching a missing original for such a rendering SHALL be allowed to go below it as well, but SHALL leave at least 100 MiB free. A download of an original for a rendering ahead of time SHALL NOT go below the reserve.

#### Scenario: Local photos below the reserve
- **WHEN** the free storage is below the reserve and the slideshow shows local photos
- **THEN** the photos keep changing, each rendered when it is due, and no photo is rendered ahead of time

#### Scenario: Remote photo without its original below the reserve
- **WHEN** the free storage is below the reserve but more than 100 MiB plus the size of the original, and the slideshow comes to a remote photo whose original is not stored
- **THEN** its original is downloaded when the photo is due, not ahead of time, and the photo is shown

#### Scenario: Storage almost full
- **WHEN** less than 100 MiB plus the size of the original would remain free, and the slideshow comes to a remote photo whose original is not stored
- **THEN** its original is not downloaded, the photo is not shown, it stays in the library, and the slideshow shows other photos

### Requirement: Rendered sizes follow the index
When a photo leaves the index for any reason (deleted through the API, removed from the photo folder, skipped, gone from its source, or its source removed), its rendered sizes SHALL be removed, and a rendering of it that finishes afterwards SHALL be discarded. Rendered sizes of an older image version of a photo that stays SHALL be removed at the latest when its new version is rendered.

#### Scenario: File deleted over USB
- **WHEN** a file whose sizes are rendered is deleted from the photo folder and a scan runs
- **THEN** none of its rendered sizes is left on the frame

#### Scenario: File replaced by a damaged one
- **WHEN** a rendered photo is replaced by a damaged file at the same path and a scan lists it as skipped
- **THEN** none of its rendered sizes is left on the frame

#### Scenario: Removed while rendering
- **WHEN** a photo is deleted while one of its sizes is being rendered
- **THEN** that rendering is discarded and leaves no file

#### Scenario: Renderings from before the update
- **WHEN** the app starts with rendered sizes of photos that are not in the index
- **THEN** those rendered sizes are deleted, and those of photos in the index stay

### Requirement: Batch changes
A batch that hides, shows or deletes photos SHALL change the library in one step: the library version SHALL change at most once, however many IDs the batch has, and exactly once if the batch changed a photo. Every ID SHALL still end in exactly one of the lists processed, missing or failed.

#### Scenario: Large batch
- **WHEN** a client hides 1,000 photos in one batch
- **THEN** all of them are hidden, `processed` is 1,000, and the library version changed once

#### Scenario: Mixed batch
- **WHEN** a batch deletes a local photo, a Nextcloud photo and an unknown ID
- **THEN** the local photo is deleted and listed as processed, the Nextcloud photo as failed and the unknown ID as missing, and the library version changed once

#### Scenario: Nothing to change
- **WHEN** a client hides 10 photos in one batch that are all hidden already
- **THEN** `processed` is 10, and the library version is unchanged

### Requirement: Names of uploaded files
The name of an uploaded file SHALL be shortened to at most 200 bytes in UTF-8 without splitting a code point, keeping its image extension. If a level of the album path exists as a file, the API SHALL answer with 400 `invalid_album` naming the field `album`. If one file of an upload of several cannot be stored, no file of the request SHALL stay and the library SHALL be unchanged.

#### Scenario: Long name in another script
- **WHEN** a file named with 90 Japanese characters and `.jpg` is uploaded
- **THEN** it is stored with a name of at most 200 bytes that ends in `.jpg`

#### Scenario: Emoji at the cut
- **WHEN** a file is uploaded whose name has an emoji of one code point where it would be cut
- **THEN** the stored name has the whole emoji or none of it, and is valid UTF-8

#### Scenario: Album level is a file
- **WHEN** a file is uploaded into the album `family/2025` and `family` is a file in the photo folder
- **THEN** the API answers with 400 `invalid_album` naming the field `album` and stores nothing

#### Scenario: Second file fails
- **WHEN** two files are uploaded and the second cannot be put in place
- **THEN** neither file is in the photo folder or in the index, and the library version is unchanged

### Requirement: Upload into an album in another letter case
An upload into an album whose levels differ from existing local folders only in letter case SHALL go into those folders and SHALL be indexed under their names, because the photo folder of the reference devices does not tell letter case apart. Albums of remote sources SHALL stay case-sensitive.

#### Scenario: Album in lower case
- **WHEN** the photo folder has the folder `Holiday` and a file `x.jpg` is uploaded into the album `holiday`
- **THEN** it lies in `Holiday/`, belongs to the album `Holiday`, and keeps its ID after the next scan

### Requirement: Implausible capture dates
An EXIF capture date that is not a valid calendar date, such as `0000:00:00 00:00:00`, or that lies before the year 1800, SHALL count as missing, and the file date SHALL be the capture date. Photos in the index with such a date SHALL get their file date at the next start, and the library version SHALL change with it.

#### Scenario: Camera without a set clock
- **WHEN** a photo whose EXIF date is `0000:00:00 00:00:00` is imported
- **THEN** its capture date is the file date, and the photo list gives it as a valid date

#### Scenario: Index from before the update
- **WHEN** the app starts with a photo in the index whose capture date lies before 1800
- **THEN** the photo has its file date as capture date, and the library version is higher than before
