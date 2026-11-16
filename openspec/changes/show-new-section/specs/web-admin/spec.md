## MODIFIED Requirements

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
