# photo-library Specification

## Purpose
Manages a frame's photos: import from the local folder and by upload, an index with stable IDs, albums, visibility and rendered image sizes for the slideshow and the admin interface.

## Requirements

### Requirement: Supported formats
The library SHALL accept JPEG, PNG, WebP and GIF. GIF SHALL be taken over as a still image from its first frame. HEIC/HEIF SHALL be accepted only if the device can decode it. Other files SHALL be ignored without aborting the import. Files with an image extension that cannot be accepted (undecodable format or damaged file) SHALL appear, with relative path and reason, in a list of skipped files that the API provides.

#### Scenario: Mixed folder
- **WHEN** the photo folder contains `a.jpg`, `b.png`, `note.txt` and `c.mp4`
- **THEN** the library accepts `a.jpg` and `b.png` and ignores the other files

#### Scenario: HEIC on an old device
- **WHEN** a HEIC file lies in the folder on a device with Android 6
- **THEN** it is not added to the index and appears in the list of skipped files with the reason "not supported"

### Requirement: Local photo folder
The frame SHALL have a photo folder that is reachable over USB (MTP) without root. Subfolders SHALL be scanned. New, changed and removed files SHALL be in the index 30 seconds after the change at the latest. Files that the frame itself creates or deletes, for example through upload, SHALL afterwards also be visible correctly over USB.

#### Scenario: Photo copied over USB
- **WHEN** a photo is copied into the photo folder over USB
- **THEN** it is in the index and visible after 30 seconds at the latest

#### Scenario: File removed
- **WHEN** a file is deleted from the photo folder
- **THEN** its entry disappears from the index after 30 seconds at the latest

#### Scenario: Uploaded photo over USB
- **WHEN** a photo is uploaded through the admin interface and the frame is connected over USB afterwards
- **THEN** the photo is visible in the photo folder

### Requirement: Stable photo IDs
Every photo SHALL have an ID that derives from its relative path and stays the same across restarts and rescans. If a file is replaced at the same path, the ID SHALL stay the same, but its image version SHALL change so that caches become invalid.

#### Scenario: Restart
- **WHEN** the app restarts
- **THEN** all photos have the same IDs as before

#### Scenario: File replaced
- **WHEN** `holiday/1.jpg` is replaced by a different file of the same name
- **THEN** the photo keeps its ID, its image version increases and its hidden state is kept

### Requirement: Metadata
For every photo the index SHALL contain width and height (after EXIF rotation), capture date (EXIF, falling back to the file date), file size, album and visibility. Unchanged files SHALL NOT be read again during a rescan.

#### Scenario: Rescan without changes
- **WHEN** a rescan runs and no file has changed
- **THEN** no image file is opened and the index remains unchanged

### Requirement: Albums from folders
The first subfolder below the photo folder SHALL determine a photo's album. Photos directly in the photo folder SHALL belong to no album. It SHALL be possible to hide an album as a whole. This SHALL also apply to photos that arrive in that folder later.

#### Scenario: Assign album
- **WHEN** `holiday/2025/beach.jpg` is imported
- **THEN** the photo belongs to the album `holiday`

#### Scenario: Hidden album receives a new photo
- **WHEN** the album `holiday` is hidden and a new photo is copied into `holiday/`
- **THEN** the new photo is hidden as well

### Requirement: Hide individual photos
It SHALL be possible to hide and unhide individual photos. A photo is visible to the slideshow if neither the photo itself nor its album is hidden.

#### Scenario: Hide photo
- **WHEN** a photo is hidden
- **THEN** the slideshow no longer shows it, but it stays in the library

### Requirement: Upload
It SHALL be possible to upload photos through the API, one or several, optionally into an album. Uploads SHALL be streamed to disk instead of being held in memory. File names and album names SHALL be sanitized so that no file can end up outside the photo folder. An album name SHALL form exactly one folder level. An upload after which less than 100 MB of storage would remain free SHALL be rejected.

#### Scenario: Upload into album
- **WHEN** two JPEG files are uploaded with the album `family`
- **THEN** they lie in `family/`, are in the index immediately and the response contains their IDs

#### Scenario: Path traversal
- **WHEN** a file named `../../evil.jpg` is uploaded, or a file is uploaded into the album `../../x`
- **THEN** it ends up sanitized in a folder directly below the photo folder or the upload is rejected with 400, and no file is created outside the photo folder

#### Scenario: Same name
- **WHEN** a file is uploaded whose name already exists in the target folder
- **THEN** the existing file is not overwritten, instead the new file receives a unique name

#### Scenario: Unsupported format
- **WHEN** a file is uploaded that has no supported format
- **THEN** the API answers with 415 and stores nothing

#### Scenario: Storage full
- **WHEN** less than 100 MB of storage would remain free after an upload
- **THEN** the API answers with 507, stores nothing and leaves no partial file behind

### Requirement: Delete
It SHALL be possible to delete photos through the API. The file and all derived image sizes SHALL be removed in the process.

#### Scenario: Delete photo
- **WHEN** a photo is deleted
- **THEN** file, index entry and rendered sizes are removed

### Requirement: Rendered image sizes
The library SHALL provide a thumbnail (longest edge 400 px) and a display size for every photo. The display size SHALL fully cover the screen in the configured orientation so that the `fill` mode is sharp as well. It SHALL have at most 2.5 times the screen's pixel count, for example for panoramas. Both sizes SHALL be EXIF-rotated and cached. The response SHALL be cacheable for the long term through the image version.

#### Scenario: Portrait photo on a landscape frame
- **WHEN** a photo of 3000×4000 pixels is rendered on a frame with 1920×1200 in `landscape`
- **THEN** the display size is 1920×2560 pixels

#### Scenario: Fetch thumbnail
- **WHEN** the admin interface fetches a photo's thumbnail
- **THEN** it receives a JPEG with a longest edge of 400 px and `Cache-Control: immutable`

#### Scenario: Repeated request
- **WHEN** the same thumbnail is requested a second time with `If-None-Match`
- **THEN** the API answers with 304 without rendering again

### Requirement: Limit storage space
The cached image sizes SHALL stay within the budget from the settings (`frame-settings`). When it is exceeded, the entries that have gone unused longest SHALL be removed. Original photos SHALL NOT be deleted in the process.

#### Scenario: Budget exceeded
- **WHEN** the cache exceeds the budget
- **THEN** the cache entries unused for the longest time are removed until it is below the budget again, and all originals are kept

### Requirement: Photo list through the API
The API SHALL deliver the photos page by page, filterable by album and visibility and sortable by capture date or name. Every entry SHALL contain ID, album, visibility, dimensions, capture date, image version and server-signed URLs for the thumbnail and the display size. A library of 10,000 photos SHALL page without noticeable delay.

#### Scenario: Fetch page
- **WHEN** page 2 with 100 entries from the album `holiday` is fetched
- **THEN** the response contains at most 100 photos from `holiday`, the total count and a link to the next page

### Requirement: Signal changes
The API SHALL provide a library version that changes with every change to the index. Clients SHALL be able to use it to detect whether they need to reload.

#### Scenario: New photo
- **WHEN** a photo is added
- **THEN** the API afterwards provides a different library version than before
