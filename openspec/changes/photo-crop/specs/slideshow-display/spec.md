# Spec Delta

## MODIFIED Requirements

### Requirement: Fill mode
The frame SHALL support the fill modes `auto` (crops at most a quarter, default), `fill` (fills the screen, cropped) and `fit` (fully visible, with borders). The fill mode SHALL apply to every photo without a section of its own. The photo's aspect ratio SHALL NOT be distorted in the process, and borders SHALL be black.

#### Scenario: Portrait with fill
- **WHEN** a portrait photo is shown on a landscape display in `fill` mode
- **THEN** the photo fills the width and is cropped centered at the top and bottom

#### Scenario: Portrait with fit
- **WHEN** the same photo is shown in `fit` mode
- **THEN** the whole photo is visible and borders remain on the left and right

#### Scenario: Portrait with auto
- **WHEN** the same photo is shown in `auto` mode
- **THEN** three quarters of its height are visible, cropped centered at the top and bottom, and black borders remain on the left and right

## ADDED Requirements

### Requirement: Automatic presentation
In the fill mode `auto`, the frame SHALL show a photo as large as possible while cropping at most 25 % of it in the direction in which it does not fit the screen; the rest of the difference SHALL become black borders. A photo that `fill` crops by at most 25 % SHALL fill the screen as in `fill`. The visible part SHALL be centered on the photo's center.

#### Scenario: Portrait photo on a landscape frame
- **WHEN** a photo of 3000×4000 pixels is shown in `auto` on a screen of 1920×1200
- **THEN** the middle 75 % of its height are visible at a height of 1200 pixels, and black borders of 360 pixels remain on the left and on the right

#### Scenario: Landscape photo close to the screen's format
- **WHEN** a photo of 4000×3000 pixels is shown in `auto` on a screen of 1920×1200
- **THEN** it fills the screen and is cropped centered at the top and bottom, as in `fill`

#### Scenario: Panorama
- **WHEN** a photo of 6000×2000 pixels is shown in `auto` on a screen of 1920×1200
- **THEN** the middle 75 % of its width are visible across the full width, and black borders remain at the top and bottom

### Requirement: Section of a photo
A photo with a section SHALL be shown with that section, whatever the fill mode. A section is a focus point (fractions of width and height) and a zoom. Zoom 1 SHALL fill the screen as `fill` does, a smaller zoom SHALL show more of the photo with black borders, down to the whole photo, and a larger zoom SHALL show a detail. The visible part SHALL be centered on the focus point.

#### Scenario: Head instead of the middle
- **WHEN** a portrait photo on a landscape frame has the section x 0.5, y 0.2, zoom 1
- **THEN** the frame fills the screen with the part around the upper fifth of the photo instead of its middle

#### Scenario: Zoom on a detail
- **WHEN** a photo of 4800×3000 pixels has the section x 0.7, y 0.6, zoom 2 on a screen of 1920×1200
- **THEN** the frame shows a part of half its width and height, centered on the focus point, across the whole screen

#### Scenario: Zoomed out
- **WHEN** a portrait photo on a landscape frame has a zoom below the one at which it is fully visible
- **THEN** the frame shows the whole photo with black borders, as in `fit`

### Requirement: Section at the edge of the photo
If the visible part centered on the focus point would reach past an edge of the photo, the frame SHALL move it inward until it touches that edge, so that no border appears where the photo could fill the screen. In a direction in which the photo is smaller than the screen, the photo SHALL be centered between two black borders.

#### Scenario: Focus point in the corner
- **WHEN** a photo of 4800×3000 pixels has the section x 0, y 0, zoom 2 on a screen of 1920×1200
- **THEN** the frame shows the top left quarter of the photo across the whole screen, without a border

#### Scenario: Borders and focus point
- **WHEN** a portrait photo on a landscape frame has the section x 0.1, y 0.5 and a zoom at which it is narrower than the screen
- **THEN** the photo stands in the middle of the screen with borders of equal width on both sides

### Requirement: Section across screen formats
A section SHALL keep its focus point when the frame's orientation or screen changes. Its zoom SHALL then be limited to the range that the new screen allows, from the whole photo visible to the largest zoom of `photo-library`. The section itself SHALL stay stored unchanged.

#### Scenario: Switch to portrait
- **WHEN** a photo of 4000×3000 pixels has the section x 0.3, y 0.5, zoom 2 on a screen of 1920×1200 and the frame switches from `landscape` to `portrait`
- **THEN** the frame shows it around the same focus point with the largest zoom the portrait screen allows, and after switching back it shows it with zoom 2 again
