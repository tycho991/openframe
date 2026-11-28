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

### Requirement: Skipped files
If the photo folder contains files that the frame could not accept, the interface SHALL point this out and list them with path and reason. The reasons `crashed` and `too_large` SHALL have their own texts.

#### Scenario: HEIC over USB on an old device
- **WHEN** a HEIC file has been copied into the photo folder over USB on a frame with Android 6
- **THEN** the interface shows a hint, and the list names the file with the reason "not supported"

#### Scenario: File that crashed the decoder
- **WHEN** a file was skipped because the decoder crashed on it
- **THEN** the list names it with a reason saying that it made the decoder crash

### Requirement: Edit photos
In the interface it SHALL be possible to hide, unhide and delete individual or several selected photos. Deleting SHALL be confirmed beforehand, saying that the photos go to the trash for 7 days, where every paired device can see and restore them. A detail view SHALL show the photo larger, as the frame shows it (section, fill mode and black borders in the screen's aspect ratio), with album, capture date and dimensions.

#### Scenario: Hide several
- **WHEN** the user selects three photos and chooses "Hide"
- **THEN** all three are marked as hidden and no longer appear in the slideshow

#### Scenario: Confirm delete
- **WHEN** the user chooses "Delete"
- **THEN** the interface asks for confirmation, saying that the photos stay in the trash for 7 days and that every paired device can see them there, and moves them only afterwards

#### Scenario: Detail view of a portrait photo
- **WHEN** the user opens a portrait photo without a section on a landscape frame in the fill mode `auto`
- **THEN** the detail view shows it in the frame's aspect ratio with the same black borders and the same part as the frame

### Requirement: Choose a section
From the detail view the user SHALL be able to choose a photo's section in an editor. It SHALL show the photo in a viewport of the frame's aspect ratio as the frame would show it, move it by dragging and zoom it with two fingers, the mouse wheel and buttons, within the zoom range of the frame. "Automatic" SHALL remove the section. Nothing SHALL be stored before "Save".

#### Scenario: Move the section
- **WHEN** the user drags a portrait photo down in the editor of a landscape frame and saves
- **THEN** the frame shows the upper part of the photo at once if it is on screen, otherwise when it appears next, and the detail view shows the same part

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

### Requirement: Edit settings
The interface SHALL be able to edit all settings that the API offers and SHALL show validation errors at the affected field. Saved changes SHALL be confirmed without reloading.

#### Scenario: Error at the field
- **WHEN** the user saves an invalid interval
- **THEN** the error message appears at the interval field and nothing is saved

### Requirement: Paired devices
The interface SHALL list the paired devices and allow revoking a pairing and renaming a device. The current device SHALL be recognizable in the list. For each device the list SHALL show how it paired: with the code, with the QR code, by an invitation of a named device or of a removed device, or that this is unknown.

#### Scenario: Remove old device
- **WHEN** the user chooses "Revoke pairing" for another device
- **THEN** it disappears from the list and the current device stays paired

#### Scenario: Rename device
- **WHEN** the user chooses "Rename" for the current device and saves a new name
- **THEN** the list shows the new name without reloading

#### Scenario: Invited device
- **WHEN** a device was paired by an invitation from the phone "Anna's phone"
- **THEN** the list shows that "Anna's phone" invited it, and after a rename of the phone its new name

#### Scenario: Inviting device removed
- **WHEN** the device that invited another one has been revoked
- **THEN** the list shows that a removed device invited it

#### Scenario: Pairing from before the update
- **WHEN** a device paired before the frame recorded the origin
- **THEN** the list shows that its origin is unknown

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
The interface SHALL be able to create an invitation from the list of paired devices. It SHALL show the invitation as a QR code and as a selectable link, together with the remaining validity, and SHALL say that the invited device gets the same rights as every paired device, including deleting photos, changing sources and settings and revoking other devices. After expiry it SHALL offer to create a new invitation.

#### Scenario: Show invitation
- **WHEN** the user chooses "Invite device" in the list of paired devices
- **THEN** the interface shows a QR code, the link, the remaining time and the note on the invited device's rights

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
The photo overview SHALL show photos from Nextcloud like local ones, with albums and hiding. For them the interface SHALL NOT offer deleting, but point to hiding. This SHALL also hold for a selected photo whose album heading has been folded since it was selected.

#### Scenario: Select Nextcloud photos
- **WHEN** the user selects a Nextcloud photo
- **THEN** hiding is offered and deleting is not

#### Scenario: Selection in a folded album
- **WHEN** the user selects a Nextcloud photo and a local photo in two open albums and then folds the album of the Nextcloud photo
- **THEN** both photos stay selected, hiding is offered and deleting is not

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

### Requirement: Warning for an enlarged section
While the part shown in the section editor has fewer original pixels than the frame's screen, so that the frame would enlarge it, the editor SHALL show a warning that names the share of the screen's resolution the part has. The warning SHALL disappear as soon as the part is sharp again, and it SHALL NOT prevent saving.

#### Scenario: Zoom into a phone photo
- **WHEN** the user zooms a photo of 1600×1200 pixels to zoom 2 in the editor of a frame with a screen of 1920×1200 and saves
- **THEN** the editor showed a warning that the part has about 42 % of the screen's resolution, and the frame shows the photo with zoom 2

#### Scenario: Sharp again
- **WHEN** the editor shows the warning and the user zooms out until the part has at least as many original pixels as the screen
- **THEN** the warning disappears

#### Scenario: Small photo without a section
- **WHEN** the user opens the editor for a photo of 1280×960 pixels without a section in the fill mode `fill` on a frame with a screen of 1920×1200
- **THEN** the editor shows the warning with about 67 %, because the frame already enlarges this photo

### Requirement: Zoom buttons stop at the sharp limit
The editor's zoom buttons SHALL stop at the largest zoom at which the part is still sharp before they zoom past it, in both directions. Two fingers and the mouse wheel SHALL zoom across this limit without stopping.

#### Scenario: Stop before enlarging
- **WHEN** a photo of 4000×3000 pixels is at zoom 1.8 in the editor of a frame with a screen of 1920×1200 and the user presses the zoom-in button
- **THEN** the zoom becomes about 2.08, one original pixel per screen pixel, without a warning, and the next press zooms further and shows the warning

### Requirement: Source errors in the interface language
The interface SHALL show the error of a source's last sync and the error of a failed account login by their error
code, in the language of the interface, with the frame's English text at most as a detail. A result without an error
code SHALL show its text as before, and an unknown code SHALL be shown as it is.

#### Scenario: Sync failed in German
- **WHEN** the interface is in German and the last sync of a source failed because the server was not reachable
- **THEN** the source card shows the German text for that error and the time of the sync

#### Scenario: Login on a full frame
- **WHEN** an account login fails because the frame already has the most sources it allows
- **THEN** the interface shows the text for that code in its language

#### Scenario: Insecure login answer
- **WHEN** the frame refuses a login with `nextcloud_insecure_login`
- **THEN** the interface says in its language that the server answered with an insecure address and points to the
  server's protocol settings

### Requirement: Safe start of an account login
While an account login is being started, the interface SHALL lock the "Connect" button and the address field, so that
one click and a double click both start one login. It SHALL open the login page only when its address uses `http` or
`https`.

#### Scenario: Double click on Connect
- **WHEN** the user double-clicks "Connect"
- **THEN** the frame receives one request to start a login and one approval page opens

#### Scenario: Login page with another scheme
- **WHEN** the frame reports a login page whose address uses neither `http` nor `https`
- **THEN** the interface opens nothing and shows an error

### Requirement: Plain http is pointed out
The interface SHALL warn that a connection is not encrypted both at the address field while an `http` address is
typed and on the card of each saved source whose server address uses `http`.

#### Scenario: Saved http source
- **WHEN** a source with an `http` server address is listed
- **THEN** its card shows the warning that the connection is not encrypted

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

### Requirement: Trash in the interface
When the trash holds photos, the photo overview SHALL say so with their number and size and SHALL list them with a thumbnail, name, album, deletion time, the deleting device and the date until which each is kept. A quarantined or damaged entry, or one without a thumbnail, SHALL show a placeholder without requesting an image, and a damaged one SHALL offer no restore. It SHALL be possible to restore single entries and all of them.

#### Scenario: Restore a deleted photo
- **WHEN** the user deletes a photo, opens the trash in the overview and chooses "Restore" for it
- **THEN** the photo is back in its album in the overview and the trash no longer lists it

#### Scenario: Quarantined entry
- **WHEN** the trash holds a quarantined photo
- **THEN** its row shows the placeholder and the browser requests no image for it

#### Scenario: Restore all
- **WHEN** the trash holds three photos and the user chooses "Restore all"
- **THEN** all three are back in the overview and the trash is no longer shown

### Requirement: Storage full and the trash
When an upload is refused because the storage is full, the interface SHALL say that photos deleted within the last day stay in the trash and that older ones are removed for room only when that frees enough.

#### Scenario: Upload refused with a recent trash
- **WHEN** an upload is refused with 507
- **THEN** the interface explains the full storage and the role of the trash in its language

### Requirement: Confirm a wider folder
When the frame refuses a change of a source with `account_login_required`, the source card SHALL say that a wider folder needs a Nextcloud login on this device and SHALL offer it. After a successful confirmation login it SHALL save the change again; after a failed one it SHALL show the error and keep the form as it is.

#### Scenario: Whole account confirmed
- **WHEN** the user sets the folder of an account to `/`, saves, chooses to confirm and approves the login as the same Nextcloud user
- **THEN** the card saves the folder `/` and shows it as saved

#### Scenario: Other account
- **WHEN** the confirmation login is approved as another Nextcloud user
- **THEN** the card shows the error for `other_account` in the interface language, and the folder is not saved

### Requirement: Folder of an account explained
At the folder of an account source the interface SHALL say that `/` shows the whole account, that every paired device sees the photos of the folder, that a wider folder needs a Nextcloud login on the device that sets it, and that a share link suits a frame others use.

#### Scenario: Folder field of an account
- **WHEN** the user edits the folder of an account source
- **THEN** the hint below the field says these four things in the interface language
