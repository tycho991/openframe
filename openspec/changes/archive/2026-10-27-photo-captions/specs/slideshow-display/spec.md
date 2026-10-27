# Spec Delta

## ADDED Requirements

### Requirement: Caption over the photo
The frame SHALL show a caption over the photo with the photo's comment. If switched on in the settings, the comment SHALL be prefixed by the album title, and the place name SHALL follow in a line of its own. A photo with nothing to show SHALL have no caption. The caption SHALL be legible on bright and dark photos alike and SHALL NOT show the capture date.

#### Scenario: Comment only
- **WHEN** a photo with the comment "Grandma's 80th birthday" is shown and album title and place are switched off
- **THEN** the caption shows "Grandma's 80th birthday"

#### Scenario: Comment, album title and place
- **WHEN** a photo in `holiday/2024/Tuscany` with the comment "Grandma's 80th birthday" and a position in Siena is shown, album title and place are switched on, and the frame's language is English
- **THEN** the caption shows "Tuscany · Grandma's 80th birthday" and below it "Siena, Italy"

#### Scenario: Nothing to show
- **WHEN** a photo without comment is shown and album title and place are switched off
- **THEN** no caption is shown

### Requirement: Album title in the caption
The album title SHALL be the last level of the photo's album path, followed by " · " and the comment. A photo without a comment SHALL show the album title alone. A photo without an album SHALL show no album title.

#### Scenario: Album title without comment
- **WHEN** a photo in `holiday/2024/Tuscany` without comment is shown and the album title is switched on
- **THEN** the caption shows "Tuscany"

#### Scenario: Photo without album
- **WHEN** a photo directly in the folder of its source without comment and without position is shown and album title and place are switched on
- **THEN** no caption is shown

### Requirement: Length of the caption
The line with album title and comment SHALL take at most two lines and the place at most one line. Text that does not fit SHALL end with an ellipsis. The caption SHALL take at most 60 % of the screen's width.

#### Scenario: Long comment
- **WHEN** a photo with a comment of 500 characters is shown
- **THEN** the comment takes two lines that end with an ellipsis, and the place line below it stays visible

### Requirement: Corner of the caption
The caption SHALL stand in the corner set for it. Its text SHALL be aligned to the left in the left corners and to the right in the right corners.

#### Scenario: Caption top right
- **WHEN** the caption's corner is set to top right
- **THEN** the caption appears in the top right corner with its lines aligned to the right

### Requirement: Caption and clock in the same corner
If the caption and the switched-on clock share a corner, the clock SHALL stay in the corner and the caption SHALL stand next to it towards the middle of the screen: above the clock in the bottom corners, below it in the top corners. Caption and clock SHALL NOT overlap, in either format and at every clock size.

#### Scenario: Both bottom right
- **WHEN** clock and caption are both set to bottom right
- **THEN** the clock stands in the bottom right corner and the caption directly above it, without overlap

#### Scenario: Large clock in portrait
- **WHEN** the frame runs in `portrait`, the clock is at 300 % and the caption shares its corner with a comment of two lines and a place
- **THEN** clock and caption are fully visible without overlap

#### Scenario: Clock switched off
- **WHEN** the clock is switched off and the caption is set to the clock's corner
- **THEN** the caption stands in the corner itself

### Requirement: Caption and hint
While the hint is shown, the caption SHALL step aside like the clock and SHALL come back when the hint disappears.

#### Scenario: Tap on the frame
- **WHEN** the user taps the frame while a caption is shown
- **THEN** the caption disappears for as long as the hint is shown and appears again afterwards

### Requirement: Caption changes with the photo
The caption SHALL change together with the photo: with a transition it SHALL fade over with the photo, without one it SHALL change at the same moment as the photo. A caption SHALL NOT be shown with a photo it does not belong to.

#### Scenario: Crossfade
- **WHEN** the frame fades from a photo with a caption to one without
- **THEN** the caption fades out during the transition, and at no time is it shown over the new photo alone

### Requirement: Caption changes without a photo change
A changed comment of the photo on screen, changed caption settings and a changed language on the frame SHALL show in the caption within 5 seconds, without the photo changing and without restarting the app.

#### Scenario: Comment edited while the photo is shown
- **WHEN** the user saves a new comment for the photo on screen
- **THEN** the caption shows the new comment within 5 seconds, and the photo is still on screen

#### Scenario: Place switched on
- **WHEN** the place is switched on in the settings while a photo with a position is shown
- **THEN** the caption shows its place within 5 seconds
