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
The admin interface SHALL work in the two most recent major versions each of Firefox, Chrome and Safari on desktop and mobile devices. It SHALL be usable from 360 px width without horizontal scrolling, in portrait and in landscape.

#### Scenario: Phone
- **WHEN** the user opens the interface on a phone with 390 px width
- **THEN** all functions are reachable without horizontal scrolling

#### Scenario: Phone in landscape
- **WHEN** the user turns a phone with 390 × 844 px to landscape
- **THEN** all functions are reachable without horizontal scrolling and the navigation does not cover the content

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
If the photo folder contains files that the frame could not accept, the interface SHALL point this out and list them with path and reason. The reasons `crashed` and `too_large` SHALL have their own texts.

#### Scenario: HEIC over USB on an old device
- **WHEN** a HEIC file has been copied into the photo folder over USB on a frame with Android 6
- **THEN** the interface shows a hint, and the list names the file with the reason "not supported"

#### Scenario: File that crashed the decoder
- **WHEN** a file was skipped because the decoder crashed on it
- **THEN** the list names it with a reason saying that it made the decoder crash

### Requirement: Edit photos
In the interface it SHALL be possible to hide, unhide and delete individual or several selected photos. Deleting SHALL be confirmed beforehand. A detail view SHALL show the photo larger, as the frame shows it (section, fill mode and black borders in the screen's aspect ratio), with album, capture date and dimensions.

#### Scenario: Hide several
- **WHEN** the user selects three photos and chooses "Hide"
- **THEN** all three are marked as hidden and no longer appear in the slideshow

#### Scenario: Confirm delete
- **WHEN** the user chooses "Delete"
- **THEN** the interface asks for confirmation and deletes only afterwards

#### Scenario: Detail view of a portrait photo
- **WHEN** the user opens a portrait photo without a section on a landscape frame in the fill mode `auto`
- **THEN** the detail view shows it in the frame's aspect ratio with the same black borders and the same part as the frame

### Requirement: Choose a section
From the detail view the user SHALL be able to choose a photo's section in an editor. It SHALL show the photo in a viewport of the frame's aspect ratio as the frame would show it, move it by dragging and zoom it with two fingers, the mouse wheel and buttons, within the zoom range of the frame. "Automatic" SHALL remove the section. Nothing SHALL be stored before "Save".

#### Scenario: Move the section
- **WHEN** the user drags a portrait photo down in the editor of a landscape frame and saves
- **THEN** the frame shows the upper part of the photo from the next change on, and the detail view shows the same part

#### Scenario: Zoom out to borders
- **WHEN** the user zooms out until the whole photo is visible and saves
- **THEN** the editor and the frame show the whole photo with black borders, and the editor does not zoom out further

#### Scenario: Back to automatic
- **WHEN** the user chooses "Automatic" for a photo with a section and saves
- **THEN** the photo has no section, and the fill mode applies to it again

#### Scenario: Cancel
- **WHEN** the user moves the photo in the editor and closes it without saving
- **THEN** the photo's section is unchanged

### Requirement: Section editor on the phone
The section editor SHALL be usable on a phone from 360 px width: its viewport SHALL fit the width without horizontal scrolling, and its buttons SHALL be at least 44 px high and wide.

#### Scenario: Phone in portrait
- **WHEN** the user opens the editor of a landscape frame on a phone with 390 px width
- **THEN** the viewport takes the width of the screen, the photo can be moved with one finger and zoomed with two, and all buttons are reachable without scrolling sideways

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
The settings SHALL show the display schedule with its on/off switch, the on and off time for weekdays and for the weekend, the minutes after waking, an optional dim time for each window and the dim level. While device administration is not active, the switch SHALL be disabled, with a notice that it can be activated in the device menu on the frame. While the brightness cannot be changed, the dim fields SHALL carry the brightness notice.

#### Scenario: Set the schedule
- **WHEN** the user switches the schedule on, sets the weekend on time to 09:00 and saves
- **THEN** the change is confirmed without reloading and the frame follows the new times

#### Scenario: No device administration
- **WHEN** the user opens the settings of a frame on which device administration is not active
- **THEN** the schedule's switch is disabled and the notice explains the entry in the device menu

#### Scenario: Dim in the evening
- **WHEN** the user sets the weekday dim time to 20:30 and the dim level to 25 % and saves
- **THEN** the change is confirmed without reloading, and an invalid dim time shows its error at the weekday dim field

### Requirement: Display state and manual switch
The interface SHALL show whether the display is on, why, and when it changes next by itself, and SHALL offer a button to switch it off or on now. The button SHALL be disabled while device administration is not active. It SHALL also show the current brightness, whether the schedule dims, and a slider that sets the brightness at once, disabled while the brightness cannot be changed. The state SHALL follow changes made elsewhere as in "Current state".

#### Scenario: Switch off now
- **WHEN** the display is on by day and the user chooses "Turn display off"
- **THEN** the display goes off, and the interface shows it as off until the next switching time

#### Scenario: Changed on the frame
- **WHEN** the interface is open and someone wakes the frame with the power button at night
- **THEN** the interface shows the display as on after 30 seconds at the latest

#### Scenario: Brightness now
- **WHEN** the user drags the brightness slider to 60 % and lets go
- **THEN** the frame's display is at 60 % at once and the interface shows 60 %

### Requirement: Edit the comment
The detail view of a photo SHALL show its comment and SHALL let the user write, change and remove it, for local and remote photos alike. It SHALL show how many of the 500 characters are used. A rejected comment SHALL show its error at the field and keep the text the user wrote.

#### Scenario: Write a comment
- **WHEN** the user writes "Grandma's 80th birthday" into the comment field of a photo and saves
- **THEN** the detail view shows the saved comment without reloading, and the frame shows it in the caption

#### Scenario: Remove a comment
- **WHEN** the user empties the comment field and saves
- **THEN** the photo has no comment

#### Scenario: Nextcloud photo
- **WHEN** the user opens the detail view of a photo from Nextcloud
- **THEN** the comment field can be edited

#### Scenario: Text too long
- **WHEN** the user tries to save a comment of more than 500 characters
- **THEN** the error appears at the comment field, nothing is saved and the text stays in the field

### Requirement: Location in the detail view
The detail view SHALL show a photo's place name as the frame names it, in the frame's language, and its coordinates as numbers. It SHALL show no map and SHALL NOT load anything from another server for it. A photo without a position SHALL show no location.

#### Scenario: Photo with position
- **WHEN** the user opens the detail view of a photo taken in Siena on a frame set to German, in an interface in English
- **THEN** it shows "Siena, Italien" and the coordinates 43.3188, 11.3308

#### Scenario: Photo without position
- **WHEN** the user opens the detail view of a photo without GPS data
- **THEN** no location is shown

### Requirement: Attribution of the place data
The interface SHALL name GeoNames as the source of the place names with its licence (CC BY 4.0), next to the place setting, with a link to the licence text and a note on the changes made to the data: converted, positions rounded, one German name chosen per place. The attribution SHALL be available offline, and the full source note SHALL be reachable offline from the licence menu.

#### Scenario: Settings open
- **WHEN** the user opens the caption settings without internet access
- **THEN** the attribution of GeoNames and its licence is shown next to the place setting

#### Scenario: Licence and changes
- **WHEN** the user opens the caption settings
- **THEN** the licence name links to the text of CC BY 4.0 and the attribution says how the data was changed
- **AND** the licence menu opens the full source note of the place data without internet access

### Requirement: Navigation by width
Below 600 px width the interface SHALL show its sections in a navigation bar at the bottom, each with icon and
label. From 600 px it SHALL show them in a navigation rail at the left edge, and from 1200 px in a side navigation
with labels. The current section SHALL be marked in every form.

#### Scenario: Phone
- **WHEN** the interface is open at 390 px width
- **THEN** the sections are in a bar at the bottom and the current one is marked

#### Scenario: Tablet in portrait
- **WHEN** the interface is open at 800 px width
- **THEN** the sections are in a rail at the left edge

#### Scenario: Desktop
- **WHEN** the interface is open at 1280 px width
- **THEN** the sections are in a side navigation with their labels

### Requirement: Detail views by width
The photo detail SHALL open as a full-screen layer below 600 px width, as a sheet over the list from 600 px and as
a panel next to the list from 1200 px, where the list stays usable and choosing another photo shows it in the
panel. Closing the detail SHALL return to the same place in the list.

#### Scenario: Detail as a panel
- **WHEN** the user opens a photo at 1280 px width and then chooses another photo in the grid
- **THEN** the panel shows the second photo and the grid stays visible next to it

#### Scenario: Detail on a phone
- **WHEN** the user opens a photo at 390 px width and closes it again
- **THEN** the detail covers the screen while open, and afterwards the grid shows the same photos as before

### Requirement: Layout follows width and orientation
When the width class changes, for example because a tablet is rotated or a window is resized, the interface SHALL
switch its layout without reloading. It SHALL keep the open section, an open detail, the scroll position and
input that has not been saved.

#### Scenario: Rotate a tablet
- **WHEN** the photo detail is open at 1280 × 800 px and the tablet is rotated to 800 × 1280 px
- **THEN** the same photo is shown as a sheet, and closing it shows the grid at the same place

#### Scenario: Unsaved input
- **WHEN** the user has changed the frame's name in the settings without saving and the width class changes
- **THEN** the changed name is still in the field

### Requirement: Touch targets
Every control of the interface SHALL offer a touch target of at least 44 × 44 px, also where the visible control
is smaller.

#### Scenario: Controls on a phone
- **WHEN** the interface is open at 360 px width in any section
- **THEN** no button, link, switch or tile has a touch target smaller than 44 × 44 px

### Requirement: Keyboard operation
Every function of the interface SHALL be reachable with the keyboard. The focus SHALL always be visible, and when a
layer, sheet, panel or dialog closes, the focus SHALL return to the control that opened it.

#### Scenario: Close the photo detail
- **WHEN** the user opens a photo with the keyboard and closes the detail with Escape
- **THEN** the focus is on the tile of that photo

### Requirement: Safe areas
On devices whose screen has a notch, rounded corners or a home indicator, navigation and content SHALL stay inside
the safe area of the screen.

#### Scenario: Phone with a home indicator
- **WHEN** the interface is open on a phone with a home indicator
- **THEN** the labels of the bottom navigation bar are above the home indicator

### Requirement: Theme choice
The interface SHALL follow the system's light or dark colour scheme unless the user chooses light or dark. The
choice SHALL be offered next to the language in one place reachable from every section, apply per browser and be
kept after reloading.

#### Scenario: System in dark mode
- **WHEN** the system uses the dark colour scheme and the user has not chosen a theme
- **THEN** the interface appears in the dark theme

#### Scenario: Choice is kept
- **WHEN** the user chooses the light theme and reloads the page
- **THEN** the interface appears in the light theme, also while the system uses the dark scheme

### Requirement: Quarantined photos in the interface
For a quarantined photo, the photo grid and the detail view SHALL show a placeholder without requesting the image, and it SHALL NOT be an album's cover. The section editor SHALL NOT be offered for it. Selecting, deleting and hiding SHALL stay possible as for other photos, and the detail view SHALL offer to try the photo again.

#### Scenario: Grid with a quarantined photo
- **WHEN** the overview shows a quarantined photo
- **THEN** its tile shows the placeholder and the browser requests no image for it

#### Scenario: Album with only quarantined photos
- **WHEN** every photo of an album is quarantined
- **THEN** the album list shows the album without a cover image

#### Scenario: Try again
- **WHEN** the user chooses to try a quarantined photo again in the detail view
- **THEN** the photo is no longer quarantined and its image is requested again

### Requirement: Safe mode notice
While the frame is in safe mode, the interface SHALL show a banner that explains that photos are paused after repeated crashes and offers to start the frame normally. The error codes `safe_mode`, `storage_unavailable`, `decoder_unavailable` and `image_too_large` SHALL each have an understandable message.

#### Scenario: Frame in safe mode
- **WHEN** the user opens the interface of a frame in safe mode
- **THEN** a banner explains the safe mode and offers "Start normally"

#### Scenario: Start normally from the banner
- **WHEN** the user chooses "Start normally" in the banner
- **THEN** the frame restarts in normal mode and the banner disappears once the frame reports normal mode

#### Scenario: Upload of an oversized image
- **WHEN** the user uploads an image over the size limit
- **THEN** the interface names the file and says that the image is too large

### Requirement: Software update in the interface
The settings SHALL have a section "Software" that shows the installed version, the latest version found with its release notes, the state of check, download and installation, the last check and the last error. It SHALL offer "Check now" and, while an update is ready, "Install on the frame". The switch for automatic checks SHALL name GitHub. The section SHALL follow changes as in "Current state".

#### Scenario: Update ready
- **WHEN** 0.4.0 is ready on a frame running 0.3.0 and the user opens the settings
- **THEN** the section shows 0.3.0, 0.4.0 with its notes and the button "Install on the frame"

#### Scenario: Check now
- **WHEN** the user chooses "Check now"
- **THEN** the section shows that the check runs and then its result, without reloading

#### Scenario: Own build
- **WHEN** the frame runs a build that is not signed with the official certificate
- **THEN** the section says that updates exist only for official builds and offers no check

### Requirement: Release notes as plain text
The interface SHALL show release notes as plain text, never as HTML or rendered Markdown, so that no markup or link in them takes effect.

#### Scenario: Markup in the notes
- **WHEN** the notes of a release contain `<img src=x onerror=alert(1)>` and a `javascript:` link
- **THEN** both appear as text, and nothing runs

### Requirement: Installing explained in the interface
"Install on the frame" SHALL say that the confirmation happens on the frame. While Android does not allow openframe to install apps, the button SHALL be disabled, with a notice that installing apps from unknown sources has to be allowed on the frame.

#### Scenario: Request sent
- **WHEN** the user chooses "Install on the frame"
- **THEN** the interface says that the dialog is now open on the frame and has to be confirmed there

#### Scenario: Installing not allowed
- **WHEN** an update is ready on a frame that does not allow installing apps
- **THEN** the button is disabled and the notice names the setting on the frame

### Requirement: Reload after a frame update
When the installed version the frame reports differs from the version the interface was built with, the interface SHALL reload itself if no input is unsaved, and otherwise SHALL show a notice to reload. While an installation runs, a frame that does not answer SHALL count as restarting, not as an error.

#### Scenario: After the installation
- **WHEN** the user chose "Install on the frame", someone confirmed the dialog, and the server answers again
- **THEN** within 30 seconds the interface reloads and shows the new installed version

#### Scenario: Unsaved input
- **WHEN** the frame updated itself while the user is typing a comment
- **THEN** the interface keeps the input and shows a notice to reload

### Requirement: Unknown values from the frame
A phase, error code or refusal code from the frame that the interface does not know SHALL be shown as its raw value, without breaking the view.

#### Scenario: Newer frame, older interface
- **WHEN** the frame reports an error code the interface has no text for
- **THEN** the section shows the code itself and stays usable

### Requirement: Brightness in the settings
The settings SHALL show the brightness with its mode (Android's setting, a fixed level, automatic) and the level. Automatic SHALL be offered only where the frame supports it. While the brightness cannot be changed, only Android's setting SHALL be selectable, with a notice that the permission can be granted in the device menu on the frame.

#### Scenario: Fixed level
- **WHEN** the user chooses a fixed level of 40 % and saves
- **THEN** the change is confirmed without reloading and the frame's display is at 40 %

#### Scenario: No permission
- **WHEN** the user opens the settings of a frame that may not modify system settings
- **THEN** only Android's setting can be chosen and the notice explains the entry in the device menu

#### Scenario: No light sensor
- **WHEN** the user opens the settings of a frame without a light sensor
- **THEN** automatic brightness is not offered
