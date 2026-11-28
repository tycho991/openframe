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

### Requirement: Delete
It SHALL be possible to delete photos through the API. The file of a local photo SHALL move into the trash (see "Trash for deleted photos"), and its index entry and all derived image sizes SHALL be removed. Photos of a source that the frame only reads (Nextcloud) SHALL NOT be deleted: the API refuses with 409 and the error code `read_only_source`, because the photo would come back with the next sync. Hiding it remains possible.

#### Scenario: Delete photo
- **WHEN** a photo is deleted
- **THEN** its file is in the trash and no longer at its path, and its index entry and rendered sizes are removed

#### Scenario: Delete a photo from Nextcloud
- **WHEN** a client deletes a photo that comes from Nextcloud
- **THEN** the API answers 409 with `read_only_source`, and the photo stays in the library

#### Scenario: Batch with Nextcloud photos
- **WHEN** a batch deletes a local photo and a Nextcloud photo together
- **THEN** the local photo is moved to the trash and the Nextcloud photo is reported as failed

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
- **THEN** the photo carries this section, and the slideshow shows it with it at once if the photo is on screen, otherwise when it appears next

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
The frame SHALL show a section with at most zoom 4, whatever the photo's resolution. Above the zoom at which one pixel of the original covers one pixel of the screen, the frame SHALL enlarge the part to the screen. The display size SHALL still have no more pixels than that part of the original.

#### Scenario: Photo of 12 megapixels
- **WHEN** a photo of 4000×3000 pixels with zoom 3 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 3: a part of about 1333×833 original pixels, enlarged to fill the screen

#### Scenario: Photo of about the screen's size
- **WHEN** a photo of 1600×1200 pixels with zoom 2 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 2: a part of 800×500 original pixels, enlarged to fill the screen

#### Scenario: Photo of 48 megapixels
- **WHEN** a photo of 8000×6000 pixels with zoom 4 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 4, and the part has more original pixels than the screen

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
If the decoder crashes or hangs while rendering a photo of the library, that version of the photo SHALL be quarantined: it is never rendered and never shown in the slideshow, but stays in the library with its comment, section and visibility. The photo list SHALL mark it as quarantined. It SHALL be possible to hide it, to delete it if it is a local photo, and to retry it through the API. A new version of the file SHALL lift the quarantine. Restoring it SHALL keep the quarantine.

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
- **THEN** its file is in the trash, its index entry and rendered sizes are removed, and its trash entry is marked as quarantined and has no thumbnail

#### Scenario: Restore a quarantined photo
- **WHEN** a quarantined local photo is restored from the trash
- **THEN** it is back in the library with a new image version and still quarantined, and the frame does not render it

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

### Requirement: Partial uploads after a crash
Partial files of an upload that was interrupted by a crash, a kill or an update SHALL be deleted at the next start, before the server accepts uploads.

#### Scenario: Crash during an upload
- **WHEN** the app ends while a photo is being uploaded and starts again
- **THEN** no partial file of that upload remains in the photo folder

### Requirement: Album list through the API
The API SHALL list every album that holds a photo in its branch, in tree order, with its effective state, whether it differs from its parent, the number of photos and of visible photos in its branch, and a cover: the newest photo of the branch that is not quarantined, none if all are. Visible SHALL mean what the visibility filter of the photo list means, so a quarantined photo counts by its hidden state alone.

#### Scenario: Counts of a branch
- **WHEN** `holiday` holds 10 photos and `holiday/2024` 30, and 5 of them are hidden
- **THEN** the entry of `holiday` has 40 photos and 35 visible ones

#### Scenario: Hidden by the album and by the photo
- **WHEN** `holiday` with 10 photos is hidden, its sub-album `holiday/2024` with 30 photos is shown as an exception, and 2 photos in `holiday/2024` are hidden themselves
- **THEN** the entry of `holiday` has 40 photos and 28 visible ones, and the entry of `holiday/2024` 30 and 28

#### Scenario: Quarantined photo
- **WHEN** a photo in `holiday` that is not hidden is quarantined
- **THEN** it counts as visible in the entry of `holiday`, as the photo list with the filter for visible photos lists it

#### Scenario: Album with only quarantined photos
- **WHEN** every photo of an album is quarantined
- **THEN** its entry has no cover

### Requirement: Photos without an album in the API
The album list SHALL also give the number of photos and of visible photos that belong to no album. The photo list SHALL be able to select only these photos, with the same visibility filter, sort order and paging as for an album. A request that asks for them and for an album at once SHALL be refused as a bad request.

#### Scenario: Counts without an album
- **WHEN** 3 photos lie directly in the photo folder, one of them hidden
- **THEN** the album list gives 3 photos and 2 visible ones without an album

#### Scenario: List the photos without an album
- **WHEN** the photos without an album are fetched
- **THEN** the response contains only photos that lie directly in the folder of their source, from every source, with their total count

#### Scenario: Without an album and an album at once
- **WHEN** the photos without an album and of the album `holiday` are asked for in one request
- **THEN** the API answers with a bad request and no photos

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

### Requirement: Trash for deleted photos
A deleted local photo SHALL be kept in a hidden folder `.trash` inside the photo folder, below which the file keeps its path relative to the photo folder. The library SHALL NOT scan the trash, so a photo in it is in neither the index, the photo list nor the slideshow, and a rescan does not bring it back. The API SHALL offer no way to empty the trash.

#### Scenario: Deleted photo leaves the slideshow
- **WHEN** a local photo in the album `holiday` is deleted
- **THEN** the slideshow no longer shows it, the photo list no longer lists it, and it is still in the trash after the next scan

#### Scenario: No way to empty the trash
- **WHEN** a paired client sends `DELETE` to the trash or to one of its entries
- **THEN** the API answers 404 or 405, and the trash keeps all its entries

### Requirement: Entries of the trash
Only a folder directly in `.trash` whose name has the form of a trash entry SHALL count as one; the frame SHALL leave every other file and folder in `.trash` alone, never list, move or delete it, and SHALL NOT follow symbolic links there. An entry SHALL hold exactly one image file; an entry with none or several SHALL be listed as damaged and SHALL NOT be restorable.

#### Scenario: A folder of the user's own
- **WHEN** the user has copied a folder `.Trash` with files into `.trash` over USB
- **THEN** the trash does not list it, and no purge or restore touches it

#### Scenario: Entry with two files
- **WHEN** a second image file is copied into the folder of a trash entry over USB
- **THEN** the trash lists the entry as damaged, and restoring it reports it as failed and moves nothing

### Requirement: Retention of the trash
An entry SHALL stay in the trash for 7 days after it was deleted and SHALL then be deleted for good, checked after start and at least once an hour. An entry whose deletion time lies before a fixed date in the past, from a clock that was not yet set, SHALL get the current time as its deletion time, and with it a new ID, once the clock is past that date, instead of being deleted at once.

#### Scenario: After 7 days
- **WHEN** a photo was deleted 7 days and 1 hour ago
- **THEN** its file is gone from the trash and the trash no longer lists it

#### Scenario: Clock not yet set at the deletion
- **WHEN** a photo is deleted while the device's clock still shows a date years in the past, and the clock is set to the right date later
- **THEN** the photo stays in the trash for 7 days from when the clock was set

### Requirement: Trash under storage pressure
Under storage pressure the frame SHALL delete only trash entries deleted more than 24 hours ago, the oldest first, and only as many as the need requires. If deleting all such entries would still not meet the need, it SHALL delete none and refuse as before. Entries deleted within the last 24 hours SHALL stay.

#### Scenario: Upload makes room
- **WHEN** an upload would leave less free storage than the storage reserve and the trash holds photos deleted two days ago that free enough storage
- **THEN** those photos are deleted from the trash, oldest first, only as many as needed, and the upload succeeds

#### Scenario: Not enough to gain
- **WHEN** an upload would leave less free storage than the storage reserve, and deleting every trash entry older than 24 hours would not free enough
- **THEN** the API answers 507 and the trash keeps all its entries

#### Scenario: Only recent entries
- **WHEN** an upload would leave less free storage than the storage reserve and every photo in the trash was deleted within the last 24 hours
- **THEN** the API answers 507 and the trash keeps all its entries

### Requirement: Where storage pressure frees the trash
Storage pressure SHALL mean: an upload that would leave less than the storage reserve free while it is written; the render cache or a download of an original below the storage reserve after the render cache has trimmed itself; and an update that would be refused for lack of storage. The trash SHALL be used only when it is on the same volume as the storage that is short.

#### Scenario: Update on a full frame
- **WHEN** an update is found, the free storage is short of what its download needs, and trash entries older than 24 hours would free enough
- **THEN** those entries are deleted and the update is downloaded

#### Scenario: Announced size of an upload
- **WHEN** an upload announces a body larger than the free storage above the storage reserve
- **THEN** no trash entry is deleted before the body is written

### Requirement: Trash list through the API
Every paired device SHALL be able to list the trash. Each entry SHALL contain its ID, the photo's former ID and path (`null` when damaged), album, size, deletion time, the time after which it is deleted (`null` while the clock is not yet set), the deleting device's ID or `null`, whether it is quarantined or damaged, and a signed thumbnail URL or `null`. The list SHALL give the total size. While the photo folder is unavailable, listing SHALL answer 503 `storage_unavailable`.

#### Scenario: List after a delete
- **WHEN** a phone deletes the local photo `holiday/beach.jpg` and a laptop lists the trash
- **THEN** the list has an entry with the path `holiday/beach.jpg`, the album `holiday`, the phone's ID, the deletion time and a time 7 days later

#### Scenario: Storage unavailable
- **WHEN** a client lists the trash while the storage of the photo folder is not mounted
- **THEN** the API answers 503 with `storage_unavailable`

### Requirement: Thumbnails in the trash
A trash entry SHALL have a thumbnail URL signed like the image URLs of the photo list, valid for that entry only. An entry that is quarantined, damaged or without its saved state SHALL have no thumbnail URL, and a request for its thumbnail SHALL answer 503 without decoding. If the decoder crashes or hangs while rendering a trash thumbnail, the entry SHALL become quarantined.

#### Scenario: Thumbnail of a deleted photo
- **WHEN** a paired client lists the trash and fetches the thumbnail URL of an entry without a token
- **THEN** the frame serves a thumbnail of the deleted photo as long as the signature is valid

#### Scenario: Signature of a photo for a trash entry
- **WHEN** a client attaches the signature of a photo's image URL to the thumbnail URL of a trash entry
- **THEN** the API answers 401

#### Scenario: Crash on a trash thumbnail
- **WHEN** the decoder crashes while rendering the thumbnail of a trash entry
- **THEN** the entry is listed as quarantined without a thumbnail URL, its thumbnail is not rendered again, and restoring it gives a quarantined photo

### Requirement: Restore from the trash
Every paired device SHALL be able to restore one or several trash entries. A restored photo SHALL return to its path with its ID, a new image version, its hidden state, comment, section and quarantine, and SHALL be in the index at once. A missing album folder SHALL be created again. On a taken path the photo SHALL get a unique name and so a new ID, keeping its state. An entry that cannot be restored SHALL stay in the trash. In safe mode restoring SHALL answer 503 `safe_mode`.

#### Scenario: Restore a hidden photo with a comment
- **WHEN** a hidden photo with a comment and a section is deleted and then restored
- **THEN** it has its old ID, a new image version, is hidden, and has its comment and section, and the slideshow never showed it in between

#### Scenario: Path taken meanwhile
- **WHEN** `holiday/beach.jpg` is deleted, a new `holiday/beach.jpg` is uploaded, and the deleted one is restored
- **THEN** the upload keeps its name, and the restored photo is `holiday/beach (1).jpg` with a new ID and its old state

#### Scenario: Album folder gone
- **WHEN** the only photo of the album `trip/2024` is deleted, the empty folder is removed over USB, and the photo is restored
- **THEN** the folder `trip/2024` exists again and the photo belongs to the album `trip/2024`

#### Scenario: Album folder in another letter case
- **WHEN** the only photo of `Trip` is deleted, the folder is removed, a photo is uploaded into the album `trip`, and the deleted photo is restored
- **THEN** it lies in the folder `trip` and belongs to the album `trip`

#### Scenario: Restore after a restart
- **WHEN** a photo is deleted and the frame restarts before it is restored
- **THEN** it is still in the trash and is restored with its ID and state

#### Scenario: Safe mode
- **WHEN** a client restores a trash entry while the frame is in safe mode
- **THEN** the API answers 503 with `safe_mode` and the entry stays in the trash

### Requirement: Album state of a restored photo
A restored photo SHALL take the current state of its album, by the album's own setting or by inheritance, as a photo copied there would. Restoring SHALL NOT change any album setting. An album that lost its last photo to the trash SHALL keep its own setting while it is empty.

#### Scenario: Hidden album that lost its last photo
- **WHEN** the album `holiday/2024` is shown inside the hidden album `holiday`, which holds other photos, and the only photo of `holiday/2024` is deleted and then restored
- **THEN** `holiday/2024` is still shown with a setting of its own, and the restored photo is visible

#### Scenario: Parent switched while the photo was in the trash
- **WHEN** `holiday/2024` is shown inside the hidden `holiday`, which holds other photos, the only photo of `holiday/2024` is deleted, `holiday` is shown and hidden again, and the photo is restored
- **THEN** `holiday/2024` has no setting of its own and the restored photo is not visible

### Requirement: Restore during a scan
A scan that runs while a photo is restored or uploaded SHALL NOT replace the entry the frame has just put in place: an entry in the index whose file size and modification time match the file SHALL be kept as it is, with its version, state and quarantine.

#### Scenario: Restore while the folder is scanned
- **WHEN** a quarantined photo is restored while a scan of the photo folder is running
- **THEN** after the scan the photo has the version the restore gave it and is still quarantined

### Requirement: Trash without its saved state
The files in the trash SHALL be the source of truth. If the saved state of an entry is missing, the entry SHALL still be listed, purged and restorable; restoring it SHALL check the file like a photo copied over USB and add it as a hidden photo without comment or section. A file removed from the trash over USB SHALL leave the list without an error.

#### Scenario: State file lost
- **WHEN** a photo is deleted and the frame's record of the trash is lost before the next start
- **THEN** the trash still lists the entry without a deleting device and without a thumbnail, and restoring it adds the photo as a hidden photo

#### Scenario: Deleted from the trash over USB
- **WHEN** the file of a trash entry is deleted over USB
- **THEN** the trash no longer lists it after the next check at the latest, and restoring it reports it as missing

### Requirement: Log of trash actions
The frame SHALL write one info line to its log for each photo moved to the trash and each photo restored, naming the photo's path and the ID of the device that acted, and one line for each purge with the number of entries and bytes deleted.

#### Scenario: Deleted and restored by two devices
- **WHEN** a phone deletes a photo and a laptop restores it
- **THEN** the log has a line with the path and the phone's ID, and one with the path and the laptop's ID
