# web-admin Specification

## Purpose
Provides a modern admin interface in the browser of a laptop or phone. It lets users maintain a frame's photos, albums and settings without having to operate anything on the frame itself.

## Requirements

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
Without a valid token the interface SHALL show the pairing dialog, unless it was opened through a pairing link. After successful pairing it SHALL store the token in the browser. On a 401 response it SHALL return to the pairing dialog.

#### Scenario: First visit
- **WHEN** a browser opens the interface for the first time without a pairing link
- **THEN** the user sees the pairing dialog with the hint to scan the QR code on the frame or to enter the code below it

#### Scenario: Returning visit
- **WHEN** a paired browser opens the interface again
- **THEN** the user sees their photos directly

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

### Requirement: Edit settings
The interface SHALL be able to edit all settings that the API offers and SHALL show validation errors at the affected field. Saved changes SHALL be confirmed without reloading.

#### Scenario: Error at the field
- **WHEN** the user saves an invalid interval
- **THEN** the error message appears at the interval field and nothing is saved

### Requirement: Paired devices
The interface SHALL list the paired devices and allow revoking a pairing and renaming a device. The current device SHALL be recognizable in the list.

#### Scenario: Remove old device
- **WHEN** the user chooses "Revoke pairing" for another device
- **THEN** it disappears from the list and the current device stays paired

#### Scenario: Rename device
- **WHEN** the user chooses "Rename" for the current device and saves a new name
- **THEN** the list shows the new name without reloading

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

### Requirement: Pairing from a pairing link
The interface SHALL redeem a pairing link on its own, after removing the secret from the address bar and the history. If the browser already has a valid token, it SHALL discard the secret without redeeming it. After pairing it SHALL show once under which name the device was paired, with the option to rename it.

#### Scenario: Opened through a pairing link
- **WHEN** a browser without a token opens the interface through a valid pairing link
- **THEN** the user sees their photos and the name under which the device was paired, and the address bar no longer contains the secret

#### Scenario: Expired pairing link
- **WHEN** a browser without a token opens an expired pairing link
- **THEN** the interface shows the pairing dialog with the hint to scan the QR code again

#### Scenario: Already paired
- **WHEN** a paired browser opens a valid pairing link
- **THEN** the list of paired devices stays unchanged and the link stays valid

### Requirement: Invite a device
The interface SHALL be able to create an invitation from the list of paired devices. It SHALL show the invitation as a QR code and as a selectable link, together with the remaining validity. After expiry it SHALL offer to create a new invitation.

#### Scenario: Show invitation
- **WHEN** the user chooses "Invite device" in the list of paired devices
- **THEN** the interface shows a QR code, the link and the remaining time

#### Scenario: Invitation accepted
- **WHEN** another device redeems the invitation shown
- **THEN** it appears in the list of paired devices after 30 seconds at the latest

### Requirement: Sources in the interface
The interface SHALL have a section for sources: list them with name, kind and status, add one by the Nextcloud login flow or by a share link (with an optional password), and for each edit name, folder, subfolders, interval, removal of vanished photos and the storage budget, start a sync, show the status of the last sync with its error in readable form, and remove it after a confirmation. Validation errors SHALL appear at the affected field.

#### Scenario: Add a share link
- **WHEN** the user pastes the link of a share and chooses "Add"
- **THEN** the source appears in the list and shows that its first sync is running

#### Scenario: Share needs a password
- **WHEN** the share is protected and no password was given
- **THEN** the interface asks for the password at the password field

#### Scenario: Connect
- **WHEN** the user gives a server address and chooses "Connect"
- **THEN** the interface opens the approval page of Nextcloud in a new tab and shows that it waits for the approval

#### Scenario: Connected
- **WHEN** the approval has been given
- **THEN** the interface lists the account with its user name and shows its settings

#### Scenario: Several sources
- **WHEN** two sources exist
- **THEN** the list shows both with their own status, and syncing or removing one leaves the other as it is

#### Scenario: Sync now
- **WHEN** the user chooses "Sync now" for a source
- **THEN** the interface shows that a sync is running and then its result with the three numbers

#### Scenario: Error of the last sync
- **WHEN** the last sync of a source failed
- **THEN** the interface shows the time and a readable error, and no password and no share link token

#### Scenario: Disconnect
- **WHEN** the user removes a source and confirms
- **THEN** the source leaves the list and its photos disappear from the overview

### Requirement: Photos from Nextcloud in the overview
The photo overview SHALL show photos from Nextcloud like local ones, with albums and hiding. For them the interface SHALL NOT offer deleting, but point to hiding.

#### Scenario: Select Nextcloud photos
- **WHEN** the user selects a Nextcloud photo
- **THEN** hiding is offered and deleting is not

### Requirement: Note on instant changes
If the frame changes photos instantly because its display could not keep up with a transition, the interface SHALL say so next to the setting for the transition and SHALL say that changing the transition or its duration tries it again.

#### Scenario: Frame with a weak GPU
- **WHEN** the frame has fallen back to instant changes and the settings are open
- **THEN** a note next to the transition names this and how to try again

#### Scenario: Frame that manages the transition
- **WHEN** the frame shows its transitions
- **THEN** no such note appears

### Requirement: Display schedule in the interface
The settings SHALL show the display schedule with its on/off switch, the on and off time for weekdays and for the weekend, and the minutes after waking. While device administration is not active, the switch SHALL be disabled, with a notice that it can be activated in the device menu on the frame.

#### Scenario: Set the schedule
- **WHEN** the user switches the schedule on, sets the weekend on time to 09:00 and saves
- **THEN** the change is confirmed without reloading and the frame follows the new times

#### Scenario: No device administration
- **WHEN** the user opens the settings of a frame on which device administration is not active
- **THEN** the schedule's switch is disabled and the notice explains the entry in the device menu

### Requirement: Display state and manual switch
The interface SHALL show whether the display is on, why, and when it changes next by itself, and SHALL offer a button to switch it off or on now. The button SHALL be disabled while device administration is not active. The state SHALL follow changes made elsewhere as in "Current state".

#### Scenario: Switch off now
- **WHEN** the display is on by day and the user chooses "Turn display off"
- **THEN** the display goes off, and the interface shows it as off until the next switching time

#### Scenario: Changed on the frame
- **WHEN** the interface is open and someone wakes the frame with the power button at night
- **THEN** the interface shows the display as on after 30 seconds at the latest
