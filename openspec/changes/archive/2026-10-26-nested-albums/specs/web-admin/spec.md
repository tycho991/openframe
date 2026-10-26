# Delta for web-admin

## MODIFIED Requirements

### Requirement: Photo overview
The interface SHALL show the photos as a grid of thumbnails, filterable by album and visibility. The album filter SHALL show the albums with their paths and include sub-albums, with an option for only the album itself. Even with 10,000 photos, scrolling SHALL stay smooth, so only the visible thumbnails may be loaded. Hidden photos SHALL be recognizable.

#### Scenario: Large library
- **WHEN** the library contains 10,000 photos and the user scrolls through the grid
- **THEN** the interface loads only thumbnails in the visible area and responds without noticeable delay

#### Scenario: Tab open for hours
- **WHEN** the interface stays open for three hours without the library changing
- **THEN** the grid still shows all thumbnails without the user reloading

#### Scenario: Filter by a parent album
- **WHEN** the user filters by the album `holiday`
- **THEN** the grid shows the photos of `holiday` and of all its sub-albums

#### Scenario: Filter by the album only
- **WHEN** the user filters by `holiday` and chooses "only this album"
- **THEN** the grid shows only the photos that lie directly in `holiday`

### Requirement: Upload in the interface
It SHALL be possible to upload photos by file selection and by drag and drop, optionally into an existing album at any depth or into a new album, also below an existing one. Progress SHALL be visible per file. Errors SHALL be reported per file without aborting the other uploads.

#### Scenario: Partial failure
- **WHEN** the user uploads five files, one of which is a text file
- **THEN** four are uploaded and an error message appears for the text file

#### Scenario: New sub-album
- **WHEN** the user uploads a photo into a new album `2025` below `family`
- **THEN** the photo appears in the album `family/2025`

### Requirement: Albums
The interface SHALL show the albums as a tree with the number of photos and a cover image, both including sub-albums, and SHALL let the user hide and unhide each album with a switch that shows whether its photos are visible. A sub-album whose state differs from its parent SHALL be marked as an exception.

#### Scenario: Hide album
- **WHEN** the user turns off the switch of the album `holiday`
- **THEN** the slideshow shows no more photos from `holiday` and its sub-albums, and their switches show them as hidden

#### Scenario: Show a sub-album of a hidden album
- **WHEN** `holiday` is hidden and the user turns on the switch of `holiday/2024`
- **THEN** the slideshow shows the photos of `holiday/2024`, and the album is marked as an exception

#### Scenario: Switching a parent removes exceptions
- **WHEN** `holiday/2024` is an exception and the user switches `holiday`
- **THEN** `holiday/2024` follows `holiday` and is no longer marked as an exception
