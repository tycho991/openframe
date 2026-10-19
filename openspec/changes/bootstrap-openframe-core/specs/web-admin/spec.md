# Spec Delta

## Purpose

Provides a modern admin interface in the browser of a laptop or phone. It lets users maintain a frame's photos, albums and settings without having to operate anything on the frame itself.

## ADDED Requirements

### Requirement: Served by the frame
The admin interface SHALL be served by the frame at `/` and work without internet access. It SHALL NOT load resources from foreign servers.

#### Scenario: Offline on the home network
- **WHEN** the home network has no internet access and the user opens the frame's address
- **THEN** the admin interface loads completely

### Requirement: Supported browsers
The admin interface SHALL work in the two most recent major versions each of Firefox, Chrome and Safari on desktop and mobile devices. It SHALL be usable from 360 px width without horizontal scrolling.

#### Scenario: Phone
- **WHEN** the user opens the interface on a phone with 390 px width
- **THEN** all functions are reachable without horizontal scrolling

### Requirement: Pairing in the interface
Without a valid token the interface SHALL show the pairing dialog. After successful pairing it SHALL store the token in the browser. On a 401 response it SHALL return to the pairing dialog.

#### Scenario: First visit
- **WHEN** a browser opens the interface for the first time
- **THEN** the user sees the pairing dialog with a hint where the code appears on the frame

#### Scenario: Returning visit
- **WHEN** a paired browser opens the interface again
- **THEN** the user sees their photos directly

### Requirement: Photo overview
The interface SHALL show the photos as a grid of thumbnails, filterable by album and visibility. Even with 10,000 photos, scrolling SHALL stay smooth, so only the visible thumbnails may be loaded. Hidden photos SHALL be recognizable.

#### Scenario: Large library
- **WHEN** the library contains 10,000 photos and the user scrolls through the grid
- **THEN** the interface loads only thumbnails in the visible area and responds without noticeable delay

#### Scenario: Tab open for hours
- **WHEN** the interface stays open for three hours without the library changing
- **THEN** the grid still shows all thumbnails without the user reloading

### Requirement: Skipped files
If the photo folder contains files that the frame could not accept, the interface SHALL point this out and list them with path and reason.

#### Scenario: HEIC over USB on an old device
- **WHEN** a HEIC file has been copied into the photo folder over USB on a frame with Android 6
- **THEN** the interface shows a hint, and the list names the file with the reason "not supported"

### Requirement: Edit photos
In the interface it SHALL be possible to hide, unhide and delete individual or several selected photos. Deleting SHALL be confirmed beforehand. A detail view SHALL show the photo larger with album, capture date and dimensions.

#### Scenario: Hide several
- **WHEN** the user selects three photos and chooses "Hide"
- **THEN** all three are marked as hidden and no longer appear in the slideshow

#### Scenario: Confirm delete
- **WHEN** the user chooses "Delete"
- **THEN** the interface asks for confirmation and deletes only afterwards

### Requirement: Upload in the interface
It SHALL be possible to upload photos by file selection and by drag and drop, optionally into an existing or new album. Progress SHALL be visible per file. Errors SHALL be reported per file without aborting the other uploads.

#### Scenario: Partial failure
- **WHEN** the user uploads five files, one of which is a text file
- **THEN** four are uploaded and an error message appears for the text file

### Requirement: Albums
The interface SHALL show the albums with the number of photos and a cover image, and SHALL let the user hide and unhide an album with a switch.

#### Scenario: Hide album
- **WHEN** the user turns off the switch of the album `holiday`
- **THEN** the slideshow shows no more photos from `holiday`

### Requirement: Edit settings
The interface SHALL be able to edit all settings that the API offers and SHALL show validation errors at the affected field. Saved changes SHALL be confirmed without reloading.

#### Scenario: Error at the field
- **WHEN** the user saves an invalid interval
- **THEN** the error message appears at the interval field and nothing is saved

### Requirement: Paired devices
The interface SHALL list the paired devices and allow revoking a pairing. The current device SHALL be recognizable in the list.

#### Scenario: Remove old device
- **WHEN** the user chooses "Revoke pairing" for another device
- **THEN** it disappears from the list and the current device stays paired

### Requirement: Language
The interface SHALL support German and English and take the language from the browser as long as the user does not choose another. The choice applies per browser and is independent of the language on the frame.

#### Scenario: German browser
- **WHEN** the browser reports German as its preferred language
- **THEN** the interface appears in German

### Requirement: Current state
The interface SHALL show changes made elsewhere, for example over USB or from a second browser, after 30 seconds at the latest, without the user reloading.

#### Scenario: Photo over USB
- **WHEN** the interface is open and a photo arrives over USB
- **THEN** it appears in the grid after 30 seconds at the latest
