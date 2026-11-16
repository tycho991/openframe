## ADDED Requirements

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
