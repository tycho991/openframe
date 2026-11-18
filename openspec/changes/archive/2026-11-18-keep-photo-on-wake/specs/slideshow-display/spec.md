## MODIFIED Requirements

### Requirement: Operating the slideshow while the display is off
While the display is off, the frame SHALL refuse `next` and `previous` with 409 and the error code `display_off`, and SHALL leave the photo unchanged. `pause` and `resume` SHALL take effect and SHALL determine whether the photos change after the display comes on again.

#### Scenario: Next photo at night
- **WHEN** the display is off by the schedule and a client sends `next`
- **THEN** the frame answers with 409 and `display_off`, and when the display comes on the same photo is shown, as it is without the command

#### Scenario: Pause at night
- **WHEN** the display is off, a client sends `pause` and the display comes on
- **THEN** the paused photo stays on screen
