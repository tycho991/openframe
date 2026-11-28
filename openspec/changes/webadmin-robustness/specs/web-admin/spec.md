## MODIFIED Requirements

### Requirement: Pairing in the interface
Without a valid token the interface SHALL show the pairing dialog, unless it was opened through a pairing link. After successful pairing it SHALL store the token in the browser. On a 401 response it SHALL return to the pairing dialog. While pairing by code is locked, the dialog SHALL show the waiting time the frame reports in its answer, in minutes and seconds.

#### Scenario: First visit
- **WHEN** a browser opens the interface for the first time without a pairing link
- **THEN** the user sees the pairing dialog with the hint to scan the QR code on the frame or to enter the code below it

#### Scenario: Returning visit
- **WHEN** a paired browser opens the interface again
- **THEN** the user sees their photos directly

#### Scenario: Locked for longer
- **WHEN** the frame refuses a code because pairing by code is locked and reports a waiting time of 1,200 seconds
- **THEN** the dialog shows a waiting time of 20:00, whatever the text of the frame's message

### Requirement: Choose a section
From the detail view the user SHALL be able to choose a photo's section in an editor. It SHALL show the photo in a viewport of the frame's aspect ratio as the frame would show it, move it by dragging and with the arrow keys and zoom it with two fingers, the mouse wheel, buttons and keys, within the zoom range of the frame. "Automatic" SHALL remove the section. Nothing SHALL be stored before "Save".

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

#### Scenario: Move with the keyboard
- **WHEN** the user moves the focus to the editor's viewport with the Tab key, presses the up arrow key several times and saves, using no pointer
- **THEN** the shown part moves with each key press, and the frame and the detail view show the part the editor showed

### Requirement: Zoom buttons stop at the sharp limit
The editor's zoom buttons and its zoom keys SHALL stop at the largest zoom at which the part is still sharp before they zoom past it, in both directions. Two fingers and the mouse wheel SHALL zoom across this limit without stopping.

#### Scenario: Stop before enlarging
- **WHEN** a photo of 4000×3000 pixels is at zoom 1.8 in the editor of a frame with a screen of 1920×1200 and the user presses the zoom-in button
- **THEN** the zoom becomes about 2.08, one original pixel per screen pixel, without a warning, and the next press zooms further and shows the warning

#### Scenario: Stop with the plus key
- **WHEN** the same photo is at zoom 1.8 and the user presses `+` in the focused viewport
- **THEN** the zoom becomes about 2.08 without a warning, as with the button

### Requirement: Upload in the interface
It SHALL be possible to upload photos by file selection and by drag and drop, optionally into an existing album at any depth or into a new album, also below an existing one. Progress SHALL be visible per file. Errors SHALL be reported per file without aborting the other uploads. With "New album" chosen, the upload SHALL NOT start before the new album has a name, and the name field SHALL say so.

#### Scenario: Partial failure
- **WHEN** the user uploads five files, one of which is a text file
- **THEN** four are uploaded and an error message appears for the text file

#### Scenario: New sub-album
- **WHEN** the user uploads a photo into a new album `2025` below `family`
- **THEN** the photo appears in the album `family/2025`

#### Scenario: New album without a name
- **WHEN** the user chooses "New album", adds a photo and leaves the name empty
- **THEN** "Start upload" is disabled, the name field says that a name is needed, and nothing is uploaded

### Requirement: Edit settings
The interface SHALL be able to edit all settings that the API offers and SHALL show validation errors at the affected field. Saved changes SHALL be confirmed without reloading. Settings loaded from the frame SHALL NOT overwrite input the user has not saved. A saved frame name SHALL appear in the header without reloading.

#### Scenario: Error at the field
- **WHEN** the user saves an invalid interval
- **THEN** the error message appears at the interval field and nothing is saved

#### Scenario: Settings change elsewhere while typing
- **WHEN** the user has typed a new interval without saving and the settings change on another device
- **THEN** the interval field keeps the typed value

#### Scenario: Rename the frame
- **WHEN** the user changes the frame name to "Holiday frame" and saves
- **THEN** the header shows "Holiday frame" without reloading

### Requirement: Language
The interface SHALL support German and English and take the language from the browser as long as the user does not choose another. The choice applies per browser and is independent of the language on the frame. Numbers SHALL be written with the digit grouping and decimal separator of the interface language.

#### Scenario: German browser
- **WHEN** the browser reports German as its preferred language
- **THEN** the interface appears in German

#### Scenario: German numbers
- **WHEN** the interface is in German, the library holds 10,234 photos, a photo has 3.4 MB and the user saves an interval of 1 second
- **THEN** the count reads "10.234 Fotos", the detail view "3,4 MB" and the error at the field "Erlaubt: 5 bis 86.400"

### Requirement: Keyboard operation
Every function of the interface SHALL be reachable with the keyboard. The focus SHALL always be visible, and when a
layer, sheet, panel or dialog closes, the focus SHALL return to the control that opened it. A dialog that opened without such a control, as the upload dialog after a drop, SHALL return the focus to the control that otherwise opens it.

#### Scenario: Close the photo detail
- **WHEN** the user opens a photo with the keyboard and closes the detail with Escape
- **THEN** the focus is on the tile of that photo

#### Scenario: Close the upload dialog
- **WHEN** the user drops files on the photo list and closes the upload dialog with Escape or its button
- **THEN** the focus is on "Upload"

#### Scenario: Close the section editor
- **WHEN** the user opens the section editor from the detail view and saves or cancels
- **THEN** the focus is on the control that opened the editor

### Requirement: Grids follow the layout
Every open grid SHALL show the thumbnails of its rows that are on screen, without the user scrolling, also right after a heading above it was folded or opened, a grid above it loaded more photos, a notice, panel or message above the overview appeared, closed or changed its height, or the window changed its size. Returning from the detail view SHALL bring the tile of that photo into view at its current place.

#### Scenario: Fold a heading above an open album
- **WHEN** `family` and `holiday` are open, the user has scrolled to the grid of `holiday` and folds `family`
- **THEN** the grid of `holiday` shows its thumbnails at its new place at once, without empty rows

#### Scenario: Grid above grows
- **WHEN** the grid of an upper open album loads more photos while the user looks at a lower one
- **THEN** the lower grid keeps showing the thumbnails of the rows on screen

#### Scenario: Notice above the overview closes
- **WHEN** an album is open, the user closes the notice above the overview that tells under which name the browser was paired, and scrolls
- **THEN** the grid shows the thumbnails of the rows on screen, without empty rows

#### Scenario: Panel above the overview closes without scrolling
- **WHEN** an album is open, the user opens the list of skipped files above the overview and closes it again, without scrolling
- **THEN** the grid shows the thumbnails of the rows on screen at once, without empty rows

## ADDED Requirements

### Requirement: Polling while visible
The interface SHALL ask the frame for changes only while its tab is visible, and at once when the tab becomes visible again. It SHALL run at most one request of each kind of repeated request at a time, and SHALL give up on such a request after a time limit and ask again later.

#### Scenario: Hidden tab
- **WHEN** the tab with the sources section stays hidden for five minutes
- **THEN** the interface sends no repeated request to the frame meanwhile, and asks at once when the tab is shown again

#### Scenario: Request that hangs
- **WHEN** the frame accepts the connection of a state request but never answers
- **THEN** no second state request starts while it hangs, and after the time limit the interface asks again

### Requirement: Recovery after an outage
When a load in an open view failed because the frame could not be reached, the interface SHALL load it again within 30 seconds after the frame answers again, without the user reloading, also when the library and the settings did not change meanwhile. A successful load SHALL remove the error of its own failed load; an error of the user's action SHALL stay until the next action.

#### Scenario: Frame restarts while the settings are open
- **WHEN** the settings section is opened while the frame does not answer, and the frame then answers again
- **THEN** within 30 seconds the settings form appears and the error message is gone

#### Scenario: Photo overview after a restart
- **WHEN** the album list, the skipped files and the trash of the photo overview could not be loaded, and the frame answers again with the same library
- **THEN** within 30 seconds the overview shows its albums, skipped files and trash, and no error

#### Scenario: Action error stays
- **WHEN** revoking a device's pairing failed and a later load of the device list succeeds
- **THEN** the error of the revocation stays visible until the user's next action

### Requirement: Unreachable frame in the interface language
When a request cannot reach the frame, or its answer cannot be read, the interface SHALL say that the frame cannot be reached, in the interface language, and SHALL NOT show the browser's own error text.

#### Scenario: Network gone
- **WHEN** the interface is in German, the network connection to the frame is gone and the user saves the settings
- **THEN** the error says in German that the frame cannot be reached, and no English text of the browser appears

### Requirement: Late answers
An answer that the frame gives to a request started before a newer change of the same thing, before a change of filter or sort order, or for a photo that is no longer open SHALL NOT replace what the interface shows. A late answer to a save SHALL NOT open, switch or close the photo detail.

#### Scenario: Display switched during a poll
- **WHEN** a poll of the display state is on its way and the user switches the display off before it answers
- **THEN** the interface shows the display as off, also after the poll's answer arrives

#### Scenario: Device renamed during a poll
- **WHEN** a load of the device list is on its way and the user renames a device before it answers
- **THEN** the list keeps showing the new name

#### Scenario: Album switched during a load
- **WHEN** a load of the album list is on its way and the user hides an album before it answers
- **THEN** the album's switch stays off

#### Scenario: Another photo opened during a save
- **WHEN** the user hides a photo in the detail view and opens another photo before the frame answers
- **THEN** the detail keeps showing the other photo, and the grid shows the first one as hidden

#### Scenario: Detail closed during a save
- **WHEN** the user saves a comment and closes the detail before the frame answers
- **THEN** the detail stays closed

### Requirement: Photo pages stay complete
A reload of an open album's photos that becomes due while one for the same filter and sort order runs SHALL NOT discard the running one: the running reload SHALL complete and be followed by one more. A grid SHALL show each photo at most once. When renewing a page after an image error fails, a later image error on that page SHALL try again.

#### Scenario: Library changes every few seconds
- **WHEN** an open album has 1,000 loaded photos and the library version changes every 10 seconds while the reload takes longer than that
- **THEN** each reload completes, and the grid shows every photo of the album

#### Scenario: Photo moves between pages
- **WHEN** a photo moves from a later page to an earlier one between the loads of two pages
- **THEN** the grid shows it once

#### Scenario: Renewal failed
- **WHEN** an image of a page fails, the renewal of that page fails as well, and later another image of that page fails
- **THEN** the interface renews the page again

### Requirement: Cancel and retry uploads
While files are uploaded, the upload dialog SHALL offer to cancel: files not fully sent SHALL be aborted, and files fully sent SHALL finish. A file that failed or was cancelled before it was fully sent SHALL be retryable without choosing it again. A file that was fully sent without an answer SHALL NOT be retried, automatically or by a button, and SHALL say that the frame may have taken it.

#### Scenario: Cancel during an upload
- **WHEN** the user uploads ten photos and chooses Cancel while the third is being sent
- **THEN** the photos already uploaded stay, the third and the rest are not uploaded, and each of them offers to try again

#### Scenario: Retry after a network error
- **WHEN** the connection drops while a photo is being sent, comes back, and the user chooses to try that photo again
- **THEN** the photo is uploaded once and appears in the overview

#### Scenario: Sent without an answer
- **WHEN** a photo was fully sent and the frame's answer does not arrive
- **THEN** the file says that the frame may have taken it and to check the overview, and offers no retry

### Requirement: Drafts until reload
Unsaved input in the settings form, in a source's settings and in a photo's comment SHALL stay as a draft when the user leaves the section, closes the detail or opens another photo, until the page reloads. A draft SHALL count as unsaved input for the reload after a frame update, also while its view is not shown. Saving SHALL send only the fields the user changed.

#### Scenario: Back to the settings
- **WHEN** the user changes the interval in the settings without saving, goes to the photos and back to the settings
- **THEN** the interval field shows the changed value, and Save is offered

#### Scenario: Comment of another photo
- **WHEN** the user types a comment for one photo without saving, opens another photo and then the first one again
- **THEN** the comment field of the first photo shows the typed text

#### Scenario: Source draft and a frame update
- **WHEN** the user changes a source's interval without saving, goes to the photos, and the frame updates itself
- **THEN** the interface does not reload by itself and shows the notice to reload

#### Scenario: Other field changed on the frame
- **WHEN** the user has a draft with a changed interval, the language is changed on another device, and the user saves
- **THEN** the frame stores the new interval and keeps the language set on the other device

### Requirement: Discard a draft
Every form that holds a draft SHALL show a "Discard changes" control next to its Save button. Choosing it SHALL drop the draft and show the values the frame has.

#### Scenario: Discard the settings draft
- **WHEN** the user has changed the interval without saving and chooses "Discard changes"
- **THEN** the interval field shows the frame's interval again, and neither Save nor "Discard changes" is offered

### Requirement: Accepted invitation
"Invite device" SHALL be offered only after the list of paired devices has loaded. The interface SHALL show an invitation as accepted only for a device that the current device invited and that was not in the list of paired devices loaded right after the invitation was created. A pairing by code or by the frame's QR code SHALL NOT count as accepted.

#### Scenario: Invite before the list has loaded
- **WHEN** the devices section is opened and the list of paired devices has not loaded yet
- **THEN** "Invite device" is not offered

#### Scenario: Another device pairs by code meanwhile
- **WHEN** an invitation is shown and another device pairs with the code on the frame
- **THEN** the new device appears in the list, and the invitation keeps showing its QR code and link

#### Scenario: Invitation redeemed
- **WHEN** an invitation is shown and another device redeems it
- **THEN** within 30 seconds the invitation shows that this device was paired, under its name

### Requirement: Selection across filter and sort
A change of the visibility filter or the sort order SHALL keep the selected photos selected, also those the overview no longer shows. The selection bar SHALL count all selected photos, and a batch action SHALL apply to all of them.

#### Scenario: Filter changed with a selection
- **WHEN** the user selects two visible photos and one hidden photo and then chooses to see only visible photos
- **THEN** the selection bar still says three photos are selected, and "Hide" applies to all three

### Requirement: Limits shared with the frame
Every limit the interface checks or states (the allowed ranges of numbers, the longest names, comments and album names) SHALL be the frame's own. A text field SHALL accept no more characters than the frame stores for it.

#### Scenario: Long frame name
- **WHEN** the user types a frame name of 70 characters
- **THEN** the field holds the first 64, and saving it is accepted by the frame
