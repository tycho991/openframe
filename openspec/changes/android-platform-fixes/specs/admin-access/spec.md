## MODIFIED Requirements

### Requirement: Code only while shown on the frame
The frame SHALL create a pairing code only while it visibly shows the pairing hint or the empty state and its display is on, not during a dark wake, and not while local-only mode is on, in which neither of them shows a code. A request to show a code while the display is off SHALL create none until then. A code once shown SHALL stay valid for its 5 minutes, also after the hint was hidden.

#### Scenario: Display off
- **WHEN** the display is off by the schedule and a client posts to `/api/v1/pairing/code`
- **THEN** the API answers 204, the display stays dark and no code exists that a client could guess

#### Scenario: Code entered after the hint was hidden
- **WHEN** the frame showed a code and its hint was hidden again a minute later
- **THEN** entering that code within its 5 minutes pairs the device

#### Scenario: Local-only mode
- **WHEN** local-only mode is on, no device is paired, and the frame shows the empty state or the hint
- **THEN** neither shows a code or QR code, and the frame creates no new pairing code while the mode stays on
