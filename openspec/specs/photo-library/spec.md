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

#### Scenario: Large progressive JPEG on an old device
- **WHEN** a progressive JPEG of 4800 × 3000 pixels lies in the folder on a device with Android 5, whose decoder cannot decode it at any sample size
- **THEN** it appears in the list of skipped files with the reason "not supported", not as a damaged file

### Requirement: Local photo folder
The frame SHALL have a photo folder that is reachable over USB (MTP) without root. Subfolders SHALL be scanned. New, changed and removed files SHALL be in the index 30 seconds after the change at the latest. Files that the frame itself creates or deletes, for example through upload, SHALL afterwards also be visible correctly over USB. While the storage is unavailable, entries SHALL NOT be removed (see "Photo folder kept").

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
For every photo the index SHALL contain width and height (after EXIF rotation), capture date (EXIF, falling back to the file date), GPS position (EXIF, if present), file size, album and visibility. Unchanged files SHALL NOT be read again during a rescan, except once for the metadata of photos that are already in the index (see "Metadata of photos already in the index").

#### Scenario: Rescan without changes
- **WHEN** a rescan runs and no file has changed
- **THEN** no image file is opened and the index remains unchanged

#### Scenario: Photo with GPS position
- **WHEN** a photo is imported whose EXIF data hold the position 43.3188° N, 11.3308° E
- **THEN** the index holds this position for the photo

#### Scenario: Photo without GPS position
- **WHEN** a photo without GPS data in its EXIF data is imported
- **THEN** the photo has no position, and a later rescan does not open the file again because of it

### Requirement: Albums from folders
The folder path below the folder of a photo's source SHALL be its album, at every depth. Photos directly in that folder SHALL belong to no album. Every folder on that path SHALL be an album too, also without photos of its own. Folders with the same path in different sources SHALL be one album. It SHALL be possible to hide an album as a whole. This SHALL also apply to photos that arrive in that folder later.

#### Scenario: Assign album
- **WHEN** `holiday/2025/beach.jpg` is imported
- **THEN** the photo belongs to the album `holiday/2025`, and `holiday` is an album as well

#### Scenario: Photo directly in the folder
- **WHEN** `beach.jpg` lies directly in the photo folder
- **THEN** the photo belongs to no album

#### Scenario: Same path in two sources
- **WHEN** the photo folder and a remote source both hold photos in `holiday/2025`
- **THEN** all of them belong to the one album `holiday/2025`

#### Scenario: Hidden album receives a new photo
- **WHEN** the album `holiday` is hidden and a new photo is copied into `holiday/`
- **THEN** the new photo is hidden as well

### Requirement: Hide individual photos
It SHALL be possible to hide and unhide individual photos. A photo is visible to the slideshow if neither the photo itself is hidden nor its album, by the album's own setting or by inheritance.

#### Scenario: Hide photo
- **WHEN** a photo is hidden
- **THEN** the slideshow no longer shows it, but it stays in the library

#### Scenario: Hidden photo in a shown sub-album
- **WHEN** a photo is hidden and its album `holiday/2024` is shown
- **THEN** the slideshow does not show the photo

### Requirement: Upload
It SHALL be possible to upload photos through the API, one or several, optionally into an album. Uploads SHALL be streamed to disk instead of being held in memory. File names and album paths SHALL be sanitized so that no file can end up outside the photo folder. An album path SHALL have one or more folder levels separated by `/` and at most 255 characters. An upload after which less than 100 MB of storage would remain free SHALL be rejected.

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
- **WHEN** less than 100 MB of storage would remain free after an upload
- **THEN** the API answers with 507, stores nothing and leaves no partial file behind

### Requirement: Delete
It SHALL be possible to delete photos through the API. The file and all derived image sizes SHALL be removed in the process. Photos of a source that the frame only reads (Nextcloud) SHALL NOT be deleted: the API refuses with 409 and the error code `read_only_source`, because the photo would come back with the next sync. Hiding it remains possible.

#### Scenario: Delete photo
- **WHEN** a photo is deleted
- **THEN** file, index entry and rendered sizes are removed

#### Scenario: Delete a photo from Nextcloud
- **WHEN** a client deletes a photo that comes from Nextcloud
- **THEN** the API answers 409 with `read_only_source`, and the photo stays in the library

#### Scenario: Batch with Nextcloud photos
- **WHEN** a batch deletes a local photo and a Nextcloud photo together
- **THEN** the local photo is deleted and the Nextcloud photo is reported as failed

### Requirement: Rendered image sizes
The library SHALL provide a thumbnail (longest edge 400 px), a display size and a full size for every photo. The display size SHALL be the part of the photo the frame shows, at screen resolution and never larger than that part of the original. The full size SHALL be the uncropped photo with a longest edge of at most 2048 px, never larger than the original. All sizes SHALL be EXIF-rotated and cached. The response SHALL be cacheable for the long term.

#### Scenario: Portrait photo on a landscape frame
- **WHEN** a photo of 3000×4000 pixels is rendered on a frame with 1920×1200 in `landscape` in the fill mode `fill`
- **THEN** the display size is 1920×1200 pixels and shows the middle 3000×1875 pixels of the photo

#### Scenario: Portrait photo with borders
- **WHEN** the same photo is rendered in the fill mode `auto`
- **THEN** the display size is 1200×1200 pixels and shows the middle 3000×3000 pixels of the photo; the borders are not part of the image

#### Scenario: Fetch thumbnail
- **WHEN** the admin interface fetches a photo's thumbnail
- **THEN** it receives a JPEG with a longest edge of 400 px and `Cache-Control: immutable`

#### Scenario: Repeated request
- **WHEN** the same thumbnail is requested a second time with `If-None-Match`
- **THEN** the API answers with 304 without rendering again

### Requirement: Section of a photo
It SHALL be possible to set and remove a photo's section through the API: a focus point `x`, `y` (fractions of width and height after EXIF rotation, 0 to 1) and a zoom (greater than 0, at most 4). Invalid values SHALL be rejected with 400 and the field. The section SHALL survive restarts and rescans and SHALL be possible for photos of read-only sources too, without writing to the source.

#### Scenario: Set a section
- **WHEN** a client sets the section x 0.5, y 0.2, zoom 1.5 on a photo
- **THEN** the photo carries this section, and the slideshow shows it with it from the next change on

#### Scenario: Invalid focus point
- **WHEN** a client sets a section with x 1.2
- **THEN** the API answers with 400 and names `crop.x`, and the photo keeps its previous section

#### Scenario: Remove a section
- **WHEN** a client removes a photo's section
- **THEN** the photo has no section, and the fill mode applies to it again

#### Scenario: Photo of a share
- **WHEN** a client sets a section on a photo of a remote source
- **THEN** the photo carries the section, and nothing is written to the source

### Requirement: Section when the file changes
If a photo's file is replaced by one with the same aspect ratio (within 1 %), the photo SHALL keep its section. If the aspect ratio changes, the section SHALL be removed, because its focus point would refer to another picture.

#### Scenario: Edited photo of the same size
- **WHEN** a photo with a section is replaced by an edited file of the same dimensions
- **THEN** its image version increases and its section is kept

#### Scenario: Photo cropped elsewhere
- **WHEN** a photo of 4000×3000 pixels with a section is replaced by a file of 3000×3000 pixels at the same path
- **THEN** the photo has no section any more, and the fill mode applies to it

### Requirement: Largest zoom
The frame SHALL show a section with at most the zoom at which one pixel of the original covers one pixel of the screen, and at most 4. A photo that does not cover the screen at zoom 1 SHALL allow zoom 1. A stored zoom above this limit SHALL be shown with the limit.

#### Scenario: Photo of 12 megapixels
- **WHEN** a photo of 4000×3000 pixels with zoom 3 is shown on a screen of 1920×1200
- **THEN** it is shown with a zoom of about 2.08, at which 1920 original pixels cover the screen's width

#### Scenario: Photo of 48 megapixels
- **WHEN** a photo of 8000×6000 pixels with zoom 4 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 4

### Requirement: Rendering a section within memory
Rendering a section SHALL get by with the heap of the reference devices, also for a photo of 48 megapixels at the largest zoom.

#### Scenario: Large photo zoomed in
- **WHEN** a photo of 48 megapixels with the largest zoom is rendered on the Frameo
- **THEN** the app does not crash and the display size appears sharp

### Requirement: Section in the photo list
Every entry of the photo list SHALL contain the photo's section or `null`, its presentation (the part of the photo the frame shows with the current settings and screen, as fractions, and the zoom it is shown with) and a signed URL of the full size. The URL of the display size SHALL change whenever the part it shows changes.

#### Scenario: Photo without a section
- **WHEN** a client fetches a photo without a section in the fill mode `fill`
- **THEN** its section is `null` and its presentation is the part that `fill` shows, with zoom 1

#### Scenario: New section, new image
- **WHEN** a client has cached a photo's display size and the photo's section is changed afterwards
- **THEN** the photo list gives a different display URL, and fetching it returns the new part

### Requirement: Limit storage space
The cached image sizes SHALL stay within the budget from the settings (`frame-settings`). When it is exceeded, the entries that have gone unused longest SHALL be removed. Originals that are the only copy, those in the local photo folder, SHALL NOT be deleted in the process. Downloaded originals of Nextcloud photos are a cache and follow the budget of `nextcloud-source`.

#### Scenario: Budget exceeded
- **WHEN** the cache exceeds the budget
- **THEN** the cache entries unused for the longest time are removed until it is below the budget again, and all originals in the local photo folder are kept

### Requirement: Photo list through the API
The API SHALL deliver the photos page by page, filterable by album and visibility and sortable by capture date or name. The album filter SHALL include the album's sub-albums unless only the album itself is asked for. Every entry SHALL contain ID, album, visibility, dimensions, capture date, image version and server-signed URLs for the thumbnail and the display size. A library of 10,000 photos SHALL page without noticeable delay.

#### Scenario: Fetch page
- **WHEN** page 2 with 100 entries from the album `holiday` is fetched
- **THEN** the response contains at most 100 photos from `holiday` and its sub-albums, the total count and a link to the next page

#### Scenario: Only the album itself
- **WHEN** the photos of the album `holiday` are fetched without sub-albums
- **THEN** the response contains only photos that lie directly in `holiday/`

### Requirement: Signal changes
The API SHALL provide a library version that changes with every change to the index. Clients SHALL be able to use it to detect whether they need to reload.

#### Scenario: New photo
- **WHEN** a photo is added
- **THEN** the API afterwards provides a different library version than before

### Requirement: Inherited album visibility
An album without a setting of its own SHALL take the visibility of its parent album; an album at the top level SHALL be visible unless it is hidden. A sub-album SHALL be able to be shown inside a hidden album and hidden inside a visible one. Such an exception SHALL apply to its own sub-albums and to photos that arrive there later.

#### Scenario: Sub-album of a hidden album
- **WHEN** the album `holiday` is hidden and `holiday/2024/may` has no setting of its own
- **THEN** the photos of `holiday/2024/may` are not visible to the slideshow

#### Scenario: Sub-album shown inside a hidden album
- **WHEN** the album `holiday` is hidden and `holiday/2024` is shown
- **THEN** the photos of `holiday/2024` and `holiday/2024/may` are visible and those of `holiday` and `holiday/2023` are not

#### Scenario: Exception inside an exception
- **WHEN** `holiday` is hidden, `holiday/2024` is shown and `holiday/2024/june` is hidden
- **THEN** the photos of `holiday/2024/june` are not visible and those of `holiday/2024/may` are

### Requirement: Changing an album resets its sub-albums
Hiding or showing an album SHALL remove the settings of all its sub-albums, so that its whole branch follows it. An album set to the state it would take from its parent SHALL have no setting of its own. The API SHALL tell for each album whether its state differs from its parent's.

#### Scenario: Parent switched with an exception below
- **WHEN** `holiday` is hidden and `holiday/2024` is shown, and then `holiday` is shown and hidden again
- **THEN** `holiday/2024` is hidden and has no setting of its own

#### Scenario: Back to the parent's state
- **WHEN** `holiday` is hidden, `holiday/2024` is shown and then `holiday/2024` is hidden
- **THEN** `holiday/2024` has no setting of its own and follows `holiday` again

### Requirement: Album states kept across the update
The albums a frame had hidden before albums became folder paths SHALL stay hidden after the update, and no photo SHALL change its visibility through the update.

#### Scenario: Hidden album with a deeper folder
- **WHEN** a frame had hidden the album `holiday` and holds `holiday/2024/beach.jpg`, and is updated
- **THEN** the photo is in the album `holiday/2024`, which has no setting of its own, and it stays invisible to the slideshow

### Requirement: Valid GPS position
A position SHALL count only if its latitude lies within ±90° and its longitude within ±180°. The position 0° N, 0° E SHALL count as no position, because cameras without a fix write it.

#### Scenario: Position zero
- **WHEN** a photo's EXIF data hold the position 0° N, 0° E
- **THEN** the photo has no position

### Requirement: Metadata of photos already in the index
Photos that are in the index from an app version that did not read the GPS position SHALL get it once, in the background, without the user doing anything. A local photo SHALL get it from its file. A remote photo SHALL get it from its original if that is on the frame, otherwise with its next download, also when its ETag is unchanged. A photo SHALL be read for this at most once.

#### Scenario: Update with local photos
- **WHEN** the app is updated on a frame with 1,000 local photos that have GPS data
- **THEN** all of them have their position some time later, and none was rendered again for it

#### Scenario: Remote photo without its original
- **WHEN** the original of a remote photo was removed from the frame's storage and the photo has not been read for its position yet
- **THEN** it has no position until its original is downloaded again, and has it afterwards

#### Scenario: Photo without GPS data after the update
- **WHEN** a photo from before the update has no GPS data and has been read once for it
- **THEN** it is not read again for it, also not after a restart

### Requirement: Comment of a photo
A photo SHALL be able to carry a comment as part of its state, as plain text of at most 500 characters. The comment SHALL be stored with surrounding whitespace removed and line breaks and other control characters replaced by spaces. An empty comment SHALL remove it. Photos of a remote source SHALL be able to carry a comment as well; the source SHALL NOT be written to.

#### Scenario: Set comment
- **WHEN** a client sets the comment "  Grandma's 80th birthday " on a photo
- **THEN** the photo's comment is "Grandma's 80th birthday"

#### Scenario: Comment with a line break
- **WHEN** a client sets a comment with a line break between two words
- **THEN** the stored comment has a space there

#### Scenario: Remove comment
- **WHEN** a client sets the comment of a photo to `null` or to a text of spaces only
- **THEN** the photo has no comment

#### Scenario: Comment on a Nextcloud photo
- **WHEN** a client sets a comment on a photo of a Nextcloud source
- **THEN** the photo has the comment and the file on the server is unchanged

### Requirement: Comment too long
A comment longer than 500 characters after removing surrounding whitespace SHALL be rejected with 400, the error code `validation_failed` and an error entry for the field `comment`. The photo's state SHALL stay unchanged, also for the other fields of the same change.

#### Scenario: 501 characters
- **WHEN** a client sets a comment of 501 characters and `hidden` to `true` in the same change
- **THEN** the API answers with 400 naming `comment`, and the photo keeps its comment and stays visible

### Requirement: Comment when the file changes
The comment SHALL be kept when the photo's file changes at the same path, like the hidden state. A photo that leaves the index, for example because its file was moved or removed, SHALL lose its comment.

#### Scenario: File replaced
- **WHEN** a photo with a comment is replaced by a different file of the same name
- **THEN** the photo keeps its ID and its comment

#### Scenario: File moved
- **WHEN** a photo with a comment is moved into another folder
- **THEN** the photo at the new path has no comment

### Requirement: Place name from the position
The library SHALL name a photo's place offline, without any request leaving the frame. The place SHALL be the nearest one in the GeoNames dataset `cities500`, at most 50 km from the position. The place name SHALL be "place, country". Without a country it SHALL be the place alone. A photo without a position, or with no place within 50 km, SHALL have no place name.

#### Scenario: Photo from Siena
- **WHEN** a photo has the position 43.3188° N, 11.3308° E and the frame's language is English
- **THEN** its place name is "Siena, Italy"

#### Scenario: Photo on the open sea
- **WHEN** a photo has a position more than 50 km from every place in the dataset
- **THEN** it has no place name

#### Scenario: No network
- **WHEN** the frame has no internet access and a photo with a position is shown
- **THEN** the photo has its place name

### Requirement: Language of the place name
The place name SHALL be in the frame's language (`frame-settings`). The country SHALL be named in that language. The place SHALL use the name from the name pack of that language if the pack has one for it, and GeoNames' own name otherwise. The frame SHALL ship a name pack for German. Changing the frame's language SHALL change the place names without reading any photo again.

#### Scenario: Milan in German
- **WHEN** a photo was taken in Milan and the frame's language is German
- **THEN** its place name is "Mailand, Italien"

#### Scenario: Milan in English
- **WHEN** the frame's language is changed to English
- **THEN** the same photo's place name is "Milan, Italy", and no photo file was opened for it

### Requirement: Comment and location in the photo list
Every entry of the photo list SHALL contain the comment, or `null` without one, and the location, or `null` without a position. The location SHALL contain latitude, longitude and the place name, or `null` for the place name if there is none. It SHALL be possible to set and remove the comment through the API, on its own or together with the other fields of a photo.

#### Scenario: Photo with comment and position
- **WHEN** a client fetches a photo with a comment and a position
- **THEN** the entry contains the comment and the location with latitude, longitude and place name in the frame's language

#### Scenario: Set only the comment
- **WHEN** a client changes only the comment of a hidden photo
- **THEN** the photo has the new comment and stays hidden

### Requirement: Skipped after a decoder crash
If the decoding process crashes or hangs while a file from the photo folder or from a source is being checked, the file SHALL appear in the list of skipped files with the reason `crashed`, like any file that cannot be read; a changed file of a photo in the library loses its entry this way. It SHALL NOT be checked or downloaded again until its size or modification time changes.

#### Scenario: Poisoned file copied over USB
- **WHEN** a file is copied into the photo folder whose check crashes the decoder
- **THEN** the next scan lists it as skipped with the reason `crashed`, and later scans do not check it again

#### Scenario: File replaced
- **WHEN** a file skipped as `crashed` is replaced by a file of a different size
- **THEN** the next scan checks it again

### Requirement: Quarantined photos
If the decoder crashes or hangs while rendering a photo of the library, that version of the photo SHALL be quarantined: it is never rendered and never shown in the slideshow, but stays in the library with its comment, section and visibility. The photo list SHALL mark it as quarantined. It SHALL be possible to hide it, to delete it if it is a local photo, and to retry it through the API. A new version of the file SHALL lift the quarantine.

#### Scenario: Crash while rendering
- **WHEN** the decoder crashes while rendering the display size of a photo
- **THEN** the photo is marked as quarantined, the slideshow skips it, and a request for its image answers 503 without decoding

#### Scenario: Visibility unchanged
- **WHEN** a quarantined photo was visible before
- **THEN** the photo list still reports it as visible and as quarantined

#### Scenario: Retry
- **WHEN** a paired client sets a quarantined photo back to not quarantined
- **THEN** the frame renders it again at the next request, and quarantines it again if the decoder crashes again

#### Scenario: Delete a quarantined photo
- **WHEN** a paired client deletes a quarantined local photo
- **THEN** file, index entry and rendered sizes are removed

### Requirement: Image size limits
Images of more than 100 megapixels or with an aspect ratio of more than 32:1 SHALL be skipped with the reason `too_large`; an upload of such an image SHALL be rejected with 415 and the error code `image_too_large`. Checking an image SHALL decode at most about one megapixel. A memory error while checking SHALL skip the file as `too_large`, a memory error while rendering only that rendering.

#### Scenario: Very long strip
- **WHEN** a PNG of 500,000 × 127 pixels is copied into the photo folder
- **THEN** it is listed as skipped with the reason `too_large` and the app keeps running

#### Scenario: Upload over the limit
- **WHEN** a JPEG of 120 megapixels is uploaded
- **THEN** the API answers 415 with `image_too_large` and stores nothing

#### Scenario: Large phone photo
- **WHEN** a JPEG of 48 megapixels is copied into the photo folder
- **THEN** it is accepted and shown

### Requirement: Photo folder kept
The photo folder chosen at the first start SHALL be kept; the frame SHALL NOT switch to internal storage while the external storage is unavailable. While the storage is not mounted or the folder cannot be listed, a scan SHALL NOT remove entries and uploads SHALL be rejected with 503 and the error code `storage_unavailable`. A folder deleted over USB on mounted storage SHALL be created again, and its entries removed.

#### Scenario: Storage mounted late after a restart
- **WHEN** the device restarts and its storage is mounted only after the app has started
- **THEN** no photo disappears from the library, and comments, sections and hidden states are kept

#### Scenario: Upload while the storage is unavailable
- **WHEN** a photo is uploaded while the storage is not mounted
- **THEN** the API answers 503 with `storage_unavailable` and stores nothing

#### Scenario: Folder deleted over USB
- **WHEN** the whole photo folder is deleted over USB
- **THEN** the folder exists again after the next scan and no photo of the folder remains in the library

### Requirement: Partial uploads after a crash
Partial files of an upload that was interrupted by a crash, a kill or an update SHALL be deleted at the next start, before the server accepts uploads.

#### Scenario: Crash during an upload
- **WHEN** the app ends while a photo is being uploaded and starts again
- **THEN** no partial file of that upload remains in the photo folder
