# Spec Delta

## MODIFIED Requirements

### Requirement: Operation on the frame
Swiping left or right SHALL switch to the next or previous photo. A tap SHALL show a hint for a few seconds, with pause/resume, the frame's name, the address of the admin interface, a valid pairing code and the matching QR code.

#### Scenario: Swipe back
- **WHEN** the user swipes right
- **THEN** the frame shows the previous photo and the interval starts over

#### Scenario: Pause
- **WHEN** the user pauses
- **THEN** the current photo stays until the user resumes playback

#### Scenario: Hint with QR code
- **WHEN** the user taps the frame
- **THEN** the hint shows a QR code next to name, address and code, which a phone's camera app reads from one meter away

### Requirement: Empty state
If there are no visible photos, the frame SHALL show a hint on how to add photos. It SHALL include the address of the admin interface, and the pairing code and QR code as long as no device is paired yet.

#### Scenario: New frame
- **WHEN** the app starts without photos for the first time
- **THEN** the frame shows the address of the admin interface, the pairing code and the QR code

## ADDED Requirements

### Requirement: Display of the pairing QR code
The frame SHALL draw the pairing QR code dark on a light background with a quiet zone, regardless of the photo and theme. While pairing by code is locked, the frame SHALL keep showing the QR code and SHALL name the lockout in place of the code.

#### Scenario: Code locked
- **WHEN** pairing by code is locked after five wrong codes while the hint is visible
- **THEN** the hint shows the QR code and "Code locked" instead of the code
