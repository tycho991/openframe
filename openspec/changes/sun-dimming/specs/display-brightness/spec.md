## MODIFIED Requirements

### Requirement: Changes on the device
A brightness changed on the device, for example in Android's quick settings, SHALL hold until the frame next applies its own: at start, when the brightness or the schedule's dimming is changed through the API, when a dim period begins or ends, and when a phase of a fade begins or ends (see `display-schedule`). During a fade, once Android's brightness differs from the value the frame set last, the frame SHALL stop fading until that phase ends. The frame SHALL NOT undo it at any other time.

#### Scenario: Quick settings by day
- **WHEN** the mode is `manual` at 40 % and someone drags the brightness to 90 % in the quick settings at noon
- **THEN** the display stays at 90 %, and `GET /display` reports 90 within 30 seconds

#### Scenario: Next dim period
- **WHEN** the mode is `manual` at 40 %, someone set 90 % on the device at noon, and the schedule dims from 21:00
- **THEN** at 21:00 the display goes to 40 % and fades from there to the dim level

#### Scenario: Quick settings during the evening fade
- **WHEN** the display fades by the sun at 16:30 in December and someone drags the brightness to 50 % in the quick settings
- **THEN** the display stays at 50 % until the end of civil dusk, and then goes to the dim level

#### Scenario: Quick settings during a fixed fade
- **WHEN** the display fades from the dim time 20:30 and someone sets 60 % on the device at 20:40
- **THEN** the display stays at 60 % until 21:00, and then goes to the dim level
