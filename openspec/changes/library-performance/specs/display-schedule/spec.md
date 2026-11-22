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

#### Scenario: Renderings queued before the night
- **WHEN** the display goes off while display sizes of upcoming photos wait to be rendered
- **THEN** none of them is rendered and none of their originals is downloaded until the display goes on again
