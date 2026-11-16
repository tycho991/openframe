# Spec Delta

## MODIFIED Requirements

### Requirement: Albums
The interface SHALL have no separate albums section. Each album heading in the photo overview SHALL have a switch that hides and unhides the album and shows whether its photos are visible. A sub-album whose state differs from its parent SHALL be marked as an exception.

#### Scenario: Hide album
- **WHEN** the user turns off the switch on the heading of the album `holiday`
- **THEN** the slideshow shows no more photos from `holiday` and its sub-albums, and their switches show them as hidden

#### Scenario: Show a sub-album of a hidden album
- **WHEN** `holiday` is hidden and the user turns on the switch of `holiday/2024`
- **THEN** the slideshow shows the photos of `holiday/2024`, and the album is marked as an exception

#### Scenario: Switching a parent removes exceptions
- **WHEN** `holiday/2024` is an exception and the user switches `holiday`
- **THEN** `holiday/2024` follows `holiday` and is no longer marked as an exception

#### Scenario: Old bookmark
- **WHEN** the user opens a bookmark to the former albums section
- **THEN** the interface shows the photo overview

### Requirement: Quarantined photos in the interface
For a quarantined photo, the photo grid and the detail view SHALL show a placeholder without requesting the image. The section editor SHALL NOT be offered for it. Selecting, deleting and hiding SHALL stay possible as for other photos, and the detail view SHALL offer to try the photo again.

#### Scenario: Grid with a quarantined photo
- **WHEN** the overview shows a quarantined photo
- **THEN** its tile shows the placeholder and the browser requests no image for it

#### Scenario: Album with only quarantined photos
- **WHEN** every photo of an album is quarantined and the user opens its heading
- **THEN** the album's grid shows only placeholders and the browser requests no image for them

#### Scenario: Try again
- **WHEN** the user chooses to try a quarantined photo again in the detail view
- **THEN** the photo is no longer quarantined and its image is requested again

## ADDED Requirements

### Requirement: Photo overview by album
The interface SHALL show the photos as grids of thumbnails grouped under album headings, filterable by visibility and sortable; filter and sort order SHALL apply within every album. Even with 10,000 photos in one open album, scrolling SHALL stay smooth, so only the visible thumbnails may be loaded. Hidden photos SHALL be recognizable.

#### Scenario: Large library
- **WHEN** an open album contains 10,000 photos and the user scrolls through its grid
- **THEN** the interface loads only thumbnails in the visible area and responds without noticeable delay

#### Scenario: Tab open for hours
- **WHEN** the interface stays open for three hours without the library changing
- **THEN** the grids of the open albums still show all thumbnails without the user reloading

#### Scenario: Visibility filter within the albums
- **WHEN** the user chooses to see only hidden photos
- **THEN** every open album shows only its hidden photos, in the chosen sort order

### Requirement: Album headings in the photo overview
The photo overview SHALL show one heading per album, nested as the album tree and indented by depth, in the order the frame sorts albums. Below an open heading the photos that lie directly in the album SHALL come first, then the headings of its sub-albums. Photos without an album SHALL have a heading of their own after all albums.

#### Scenario: Nested albums
- **WHEN** the library has photos in `holiday`, `holiday/2024` and `family`, and the user opens `holiday`
- **THEN** `family` and `holiday` are top-level headings, and below `holiday` its own photos come before the indented heading `2024`

#### Scenario: Photos without an album
- **WHEN** photos lie directly in the photo folder and the user opens the heading for photos without an album
- **THEN** that heading is the last one and shows exactly those photos

### Requirement: Folding album headings
Each album heading SHALL fold and unfold its photos and sub-albums. On the first visit in a browser every heading SHALL be folded; afterwards the browser SHALL remember per album whether it is open, also across reloads. A folded album SHALL NOT load photos. Without browser storage the overview SHALL work and start folded.

#### Scenario: First visit
- **WHEN** the user opens the photo overview in a browser for the first time
- **THEN** every album heading is folded and no photo list is requested

#### Scenario: Remembered state
- **WHEN** the user opens `holiday` and reloads the page
- **THEN** `holiday` is open again and every other album is still folded

### Requirement: Album heading content
Each album heading SHALL show the album's name and the number of photos of the album and its sub-albums that match the visibility filter. An album without a matching photo in its branch SHALL NOT be shown while a visibility filter is set.

#### Scenario: Count follows the filter
- **WHEN** `holiday` and its sub-albums hold 40 photos, 5 of them hidden, and the user chooses only hidden photos
- **THEN** the heading of `holiday` shows 5 photos

#### Scenario: Album without matching photos
- **WHEN** every photo of `family` is visible and the user chooses only hidden photos
- **THEN** the overview shows no heading for `family`

## REMOVED Requirements

### Requirement: Photo overview
**Reason**: The photo overview is grouped by album headings; the album dropdown and "only this album" go away. Replaced by "Photo overview by album", "Album headings in the photo overview", "Folding album headings" and "Album heading content".
**Migration**: Open the album's heading in the photo overview instead of filtering by the album; its own photos are above the headings of its sub-albums.
