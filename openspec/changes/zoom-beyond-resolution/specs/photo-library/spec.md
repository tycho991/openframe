## MODIFIED Requirements

### Requirement: Largest zoom
The frame SHALL show a section with at most zoom 4, whatever the photo's resolution. Above the zoom at which one pixel of the original covers one pixel of the screen, the frame SHALL enlarge the part to the screen. The display size SHALL still have no more pixels than that part of the original.

#### Scenario: Photo of 12 megapixels
- **WHEN** a photo of 4000×3000 pixels with zoom 3 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 3: a part of about 1333×833 original pixels, enlarged to fill the screen

#### Scenario: Photo of about the screen's size
- **WHEN** a photo of 1600×1200 pixels with zoom 2 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 2: a part of 800×500 original pixels, enlarged to fill the screen

#### Scenario: Photo of 48 megapixels
- **WHEN** a photo of 8000×6000 pixels with zoom 4 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 4, and the part has more original pixels than the screen
