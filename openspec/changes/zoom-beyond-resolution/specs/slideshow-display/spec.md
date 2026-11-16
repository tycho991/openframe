## MODIFIED Requirements

### Requirement: Section across screen formats
A section SHALL keep its focus point when the frame's orientation or screen changes. Its zoom SHALL then be limited to the range that the new screen allows, from the whole photo visible to the largest zoom of `photo-library`. The section itself SHALL stay stored unchanged.

#### Scenario: Switch to landscape
- **WHEN** a photo of 4000×3000 pixels has the section x 0.3, y 0.5, zoom 0.5, set on a screen of 1920×1200 in `portrait`, and the frame switches to `landscape`
- **THEN** the frame shows the whole photo, the smallest zoom the landscape screen allows, and after switching back it shows it with zoom 0.5 again

#### Scenario: Switch to portrait
- **WHEN** a photo of 4000×3000 pixels has the section x 0.3, y 0.5, zoom 2 on a screen of 1920×1200 and the frame switches from `landscape` to `portrait`
- **THEN** the frame shows it around the same focus point with zoom 2
