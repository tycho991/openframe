## MODIFIED Requirements

### Requirement: Display of the pairing QR code
The frame SHALL draw the pairing QR code dark on a light background with a quiet zone, regardless of the photo and theme. While pairing by code is locked or paused, the frame SHALL keep showing the QR code and SHALL name the lockout in place of the code.

#### Scenario: Code locked
- **WHEN** pairing by code is locked after five wrong codes while the hint is visible
- **THEN** the hint shows the QR code and "Code locked" instead of the code

#### Scenario: Code paused
- **WHEN** pairing by code is paused after 20 wrong codes within 24 hours while the hint is visible
- **THEN** the hint shows the QR code and "Code locked" instead of the code
