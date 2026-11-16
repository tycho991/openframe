## MODIFIED Requirements

### Requirement: Section of a photo
It SHALL be possible to set and remove a photo's section through the API: a focus point `x`, `y` (fractions of width and height after EXIF rotation, 0 to 1) and a zoom (greater than 0, at most 4). Invalid values SHALL be rejected with 400 and the field. The section SHALL survive restarts and rescans and SHALL be possible for photos of read-only sources too, without writing to the source.

#### Scenario: Set a section
- **WHEN** a client sets the section x 0.5, y 0.2, zoom 1.5 on a photo
- **THEN** the photo carries this section, and the slideshow shows it with it at once if the photo is on screen, otherwise when it appears next

#### Scenario: Invalid focus point
- **WHEN** a client sets a section with x 1.2
- **THEN** the API answers with 400 and names `crop.x`, and the photo keeps its previous section

#### Scenario: Remove a section
- **WHEN** a client removes a photo's section
- **THEN** the photo has no section, and the fill mode applies to it again

#### Scenario: Photo of a share
- **WHEN** a client sets a section on a photo of a remote source
- **THEN** the photo carries the section, and nothing is written to the source
