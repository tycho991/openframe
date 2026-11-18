## MODIFIED Requirements

### Requirement: Slideshow rests while the display is off
While the display is off, the slideshow SHALL NOT change photos and SHALL NOT render display images for upcoming photos. When the display goes on, the photo that was on screen when it went off SHALL stay and the interval SHALL start over, whatever switched the display off and on. A paused slideshow SHALL stay paused with that photo.

#### Scenario: Morning
- **WHEN** the display goes on in the morning after a night off by the schedule
- **THEN** the photo from the evening is shown, and the next photo follows after one full interval

#### Scenario: Off and on by hand
- **WHEN** a client switches the display off with `PUT /display` and on again a minute later
- **THEN** the same photo is shown as before, and the next photo follows after one full interval

#### Scenario: Paused before the night
- **WHEN** the user paused the slideshow in the evening and the display goes on in the morning
- **THEN** the same photo is shown and the slideshow is still paused

## ADDED Requirements

### Requirement: Photo gone while the display is off
If the photo that was on screen was deleted or hidden while the display was off, the next visible photo SHALL appear at once when the display goes on, also while the slideshow is paused. If no visible photo is left, the frame SHALL show its empty state.

#### Scenario: Photo deleted at night
- **WHEN** the photo on screen is deleted while the display is off and the display goes on
- **THEN** the next visible photo appears at once and the interval starts over

#### Scenario: Photo hidden at night
- **WHEN** the photo on screen is hidden while the display is off and the display goes on
- **THEN** the next visible photo appears at once

#### Scenario: All photos gone at night
- **WHEN** every photo is deleted while the display is off and the display goes on
- **THEN** the frame shows its empty state

### Requirement: Presentation changed while the display is off
If the fill mode or the orientation changed while the display was off, the photo that stays SHALL appear in the new fill mode and orientation when the display goes on, not in the presentation it had before.

#### Scenario: Orientation changed at night
- **WHEN** a client sets the orientation to portrait while the display is off and the display goes on
- **THEN** the same photo is shown, laid out for portrait
