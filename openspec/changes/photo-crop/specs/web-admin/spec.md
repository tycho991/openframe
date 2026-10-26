# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

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
