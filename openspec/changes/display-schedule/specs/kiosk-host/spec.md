# Delta for kiosk-host

## MODIFIED Requirements

### Requirement: Keep the display awake
While the slideshow is running, the device SHALL NOT go into standby or turn the display off, except when the display schedule, the manual switch or the end of a waking period calls for the display to be off (see `display-schedule`).

#### Scenario: Hours without touch
- **WHEN** nobody touches the device for 12 hours and the display schedule is off
- **THEN** the display is still on and the slideshow is running

#### Scenario: Off by the schedule
- **WHEN** the display schedule calls for the display to be off
- **THEN** the display goes off, and the device does not turn it on again by itself before the schedule's next on time
