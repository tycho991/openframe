# Delta for photo-library

## MODIFIED Requirements

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

### Requirement: Photo list through the API
The API SHALL deliver the photos page by page, filterable by album and visibility and sortable by capture date or name. The album filter SHALL include the album's sub-albums unless only the album itself is asked for. Every entry SHALL contain ID, album, visibility, dimensions, capture date, image version and server-signed URLs for the thumbnail and the display size. A library of 10,000 photos SHALL page without noticeable delay.

#### Scenario: Fetch page
- **WHEN** page 2 with 100 entries from the album `holiday` is fetched
- **THEN** the response contains at most 100 photos from `holiday` and its sub-albums, the total count and a link to the next page

#### Scenario: Only the album itself
- **WHEN** the photos of the album `holiday` are fetched without sub-albums
- **THEN** the response contains only photos that lie directly in `holiday/`

## ADDED Requirements

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
