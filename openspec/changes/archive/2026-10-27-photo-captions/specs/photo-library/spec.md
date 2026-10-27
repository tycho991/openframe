# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

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
