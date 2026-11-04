## MODIFIED Requirements

### Requirement: Skip faulty files
If a photo cannot be decoded, the frame SHALL skip it and show the next one. The faulty photo SHALL NOT stop the slideshow. This SHALL also hold for a memory error and for a crash of the decoding process while rendering. Quarantined photos SHALL never be shown.

#### Scenario: Broken file
- **WHEN** the next photo is a damaged JPEG file
- **THEN** the frame shows the photo after it instead and logs the error

#### Scenario: Out of memory while rendering
- **WHEN** rendering the next photo runs out of memory
- **THEN** the frame shows the photo after it instead, and the app keeps running

#### Scenario: Quarantined photo in the order
- **WHEN** the next photo in the order is quarantined
- **THEN** the frame shows the photo after it without trying to render the quarantined one
