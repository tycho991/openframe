# Spec Delta

## MODIFIED Requirements

### Requirement: Albums
The interface SHALL have no separate albums section. Each album heading in the photo overview SHALL have a switch that hides and unhides the album with its sub-albums and shows whether its photos are visible. A sub-album whose state differs from its parent SHALL be marked as an exception. The heading for photos without an album SHALL have no switch.

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
- **THEN** the interface shows the photo overview and the address bar shows the address of the photo overview

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

### Requirement: Photos from Nextcloud in the overview
The photo overview SHALL show photos from Nextcloud like local ones, with albums and hiding. For them the interface SHALL NOT offer deleting, but point to hiding. This SHALL also hold for a selected photo whose album heading has been folded since it was selected.

#### Scenario: Select Nextcloud photos
- **WHEN** the user selects a Nextcloud photo
- **THEN** hiding is offered and deleting is not

#### Scenario: Selection in a folded album
- **WHEN** the user selects a Nextcloud photo and a local photo in two open albums and then folds the album of the Nextcloud photo
- **THEN** both photos stay selected, hiding is offered and deleting is not

## ADDED Requirements

### Requirement: Photo overview by album
The interface SHALL show the photos as grids of thumbnails grouped under album headings, filterable by visibility and sortable; filter and sort order SHALL apply within every album. Above the headings it SHALL show how many photos of the library match the filter. Even with 10,000 photos in one open album, scrolling SHALL stay smooth, so only the visible thumbnails may be loaded. Hidden photos SHALL be recognizable.

#### Scenario: Large library
- **WHEN** an open album contains 10,000 photos and the user scrolls through its grid
- **THEN** the interface loads only thumbnails in the visible area and responds without noticeable delay

#### Scenario: Tab open for hours
- **WHEN** the interface stays open for three hours without the library changing
- **THEN** the grids of the open albums still show all thumbnails without the user reloading

#### Scenario: Visibility filter within the albums
- **WHEN** the user chooses to see only hidden photos
- **THEN** every open album shows only its hidden photos, in the chosen sort order, and the count above the headings is the number of hidden photos in the library

#### Scenario: Empty library
- **WHEN** the library holds no photo
- **THEN** the overview shows no heading but the hint to upload photos or copy them over USB

### Requirement: Grids follow the layout
Every open grid SHALL show the thumbnails of its rows that are on screen, without the user scrolling, also right after a heading above it was folded or opened, a grid above it loaded more photos, a notice above the overview appeared or closed, or the window changed its size. Returning from the detail view SHALL bring the tile of that photo into view at its current place.

#### Scenario: Fold a heading above an open album
- **WHEN** `family` and `holiday` are open, the user has scrolled to the grid of `holiday` and folds `family`
- **THEN** the grid of `holiday` shows its thumbnails at its new place at once, without empty rows

#### Scenario: Grid above grows
- **WHEN** the grid of an upper open album loads more photos while the user looks at a lower one
- **THEN** the lower grid keeps showing the thumbnails of the rows on screen

#### Scenario: Notice above the overview closes
- **WHEN** an album is open, the user closes the notice above the overview that tells under which name the browser was paired, and scrolls
- **THEN** the grid shows the thumbnails of the rows on screen, without empty rows

### Requirement: Photo requests from the overview
The overview SHALL request the photos of an open album only when its grid comes near the visible area, with a first page of at most 60 photos. It SHALL run at most two photo list requests at a time for all albums together. An album without photos of its own that match the filter SHALL cost no photo list request.

#### Scenario: Reload with many open albums
- **WHEN** 20 albums are remembered as open and the user reloads the overview
- **THEN** at most two photo list requests run at a time, and only the albums near the visible area are requested

#### Scenario: Album without photos of its own
- **WHEN** `holiday` holds photos only in its sub-album `holiday/2024` and both are open
- **THEN** no photo list is requested for `holiday` itself

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

#### Scenario: Source briefly unreachable
- **WHEN** `holiday` is open and its photos are missing from the album list for a while
- **THEN** `holiday` is open again once its photos are back

### Requirement: Album heading content
Each album heading SHALL show the album's name and the number of photos of the album and its sub-albums that match the visibility filter. An album without a matching photo in its branch SHALL NOT be shown while a visibility filter is set; the same SHALL hold for the heading for photos without an album.

#### Scenario: Count follows the filter
- **WHEN** `holiday` and its sub-albums hold 40 photos, 5 of them hidden, and the user chooses only hidden photos
- **THEN** the heading of `holiday` shows 5 photos

#### Scenario: Album without matching photos
- **WHEN** every photo of `family` is visible and the user chooses only hidden photos
- **THEN** the overview shows no heading for `family`

### Requirement: Album switch in the photo overview
After the switch on a heading has been used, the open grids SHALL show the new visibility of their photos without waiting for the regular check for library changes. The switch SHALL keep the focus after it is used. While a visibility filter is set, the switched heading SHALL stay in place until the filter or the sort order changes, even if no photo of its branch matches the filter any more.

#### Scenario: Grids follow the switch
- **WHEN** `holiday` is open and the user turns off its switch
- **THEN** the photos in the grid of `holiday` are marked as hidden before the next regular check for library changes

#### Scenario: Switch with a filter
- **WHEN** only hidden photos are shown, every photo of `holiday` is hidden by the album, and the user turns on its switch
- **THEN** the heading of `holiday` stays under the pointer with its switch focused and on, and its grid shows no photos

#### Scenario: Switch a sub-album with a filter
- **WHEN** only hidden photos are shown, `holiday` has no photos of its own, every photo of `holiday/2024` is hidden by the album, and the user turns on the switch of `holiday/2024`
- **THEN** the headings of `holiday` and `holiday/2024` both stay in place, and the switch of `holiday/2024` keeps the focus

### Requirement: Album headings for every screen and input
At 360 px width a heading of any depth and with a long name SHALL fit without horizontal scrolling. Every heading SHALL be announced as a heading at a level that follows its depth. Its fold button SHALL tell whether it is open, and the names of fold button and switch SHALL contain the album's path. Fold buttons and switches SHALL be reachable with Tab and work with Enter and Space.

#### Scenario: Deep album on a phone
- **WHEN** an album five levels deep with a name of 60 characters is open at 360 px width
- **THEN** its heading, count and switch are on screen without horizontal scrolling

#### Scenario: Fold with the keyboard
- **WHEN** the user moves to the fold button of `holiday` with Tab and presses Enter
- **THEN** `holiday` opens, its fold button tells that it is open, and the focus stays on it

### Requirement: Uploaded photos in the overview
After an upload through the interface, the heading of the album the photos went to SHALL be open, with the headings above it, and remembered as open, also when it is the heading for photos without an album.

#### Scenario: Upload on the first visit
- **WHEN** a user on their first visit uploads a photo without choosing an album
- **THEN** the heading for photos without an album is open and shows the photo

#### Scenario: Upload into a folded sub-album
- **WHEN** `holiday` and `holiday/2024` are folded and the user uploads a photo into `holiday/2024`
- **THEN** `holiday` and `holiday/2024` are open and the grid of `holiday/2024` shows the photo

## REMOVED Requirements

### Requirement: Photo overview
**Reason**: The photo overview is grouped by album headings; the album dropdown and "only this album" go away. Replaced by "Photo overview by album", "Album headings in the photo overview", "Folding album headings", "Album heading content" and the other requirements added by this change.
**Migration**: Open the album's heading in the photo overview instead of filtering by the album; its own photos are above the headings of its sub-albums.
