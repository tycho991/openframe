# Delta for display-schedule

## ADDED Requirements

### Requirement: Asking for the network while the display is off
When the network is lost while the display is off by the schedule or the manual switch, the frame SHALL ask the system once a minute to reconnect to the Wi-Fi, without switching the display on, until the network is back or the display goes on.

#### Scenario: Router drops the frame at night
- **WHEN** the display is off by the schedule and the access point drops the frame's Wi-Fi
- **THEN** the frame asks for a reconnect within a minute and again every minute, and the display stays dark

#### Scenario: Network back
- **WHEN** the network comes back after a reconnect request
- **THEN** the frame asks for nothing more and the admin interface answers again

### Requirement: Dark wake for the network
If the network is still missing ten minutes after it was lost while the display was off by the schedule or the manual switch, the frame SHALL switch the display on with a black picture at the lowest brightness it can set, and off again once the network is back or after one minute; then it SHALL try again ten minutes later. The display state SHALL report the display as off meanwhile; a touch SHALL count as waking it.

#### Scenario: Still no network after ten minutes
- **WHEN** the display is off by the schedule and the network has been missing for ten minutes
- **THEN** the display goes on with a black picture and no slideshow, and `GET /display` still reports it off by the schedule

#### Scenario: Network back during the dark wake
- **WHEN** the network comes back 15 seconds into a dark wake
- **THEN** the display goes off again at once

#### Scenario: Network missing for long
- **WHEN** the network stays missing for 25 minutes while the display is off by the schedule
- **THEN** the display was on with a black picture twice, for one minute each, and is off in between

#### Scenario: Touch during the dark wake
- **WHEN** someone touches the black display during a dark wake
- **THEN** the slideshow appears, and the display goes off again the minutes after waking after the last touch

### Requirement: Schedule only with a set clock
While the device's clock shows a time before 1 January 2026, the frame SHALL treat the clock as not set and SHALL NOT switch the display by the schedule: the display stays on and the slideshow runs, and the display state reports no schedule. Once the clock shows a later time, the schedule SHALL apply as after a change of the schedule.

#### Scenario: Boot without network at night
- **WHEN** the frame boots without network and its clock shows 15 May 2021 00:00
- **THEN** the display stays on with the slideshow and `GET /display` reports `cause: none`

#### Scenario: Clock set at night
- **WHEN** the clock is set by the network to 23:00 on a weekday with the default windows
- **THEN** the display goes off 5 minutes after the last touch, not at once

#### Scenario: Clock set by day
- **WHEN** the clock is set by the network to 10:00 on a weekday with the default windows
- **THEN** the display stays on and `GET /display` reports it on by the schedule until 22:00

## MODIFIED Requirements

### Requirement: Switching on time
The frame SHALL switch at a switching time within one minute, also while the display is off and nobody uses the frame. A change of the device's clock or time zone SHALL take effect within one minute, without a restart.

#### Scenario: Night without touch
- **WHEN** the schedule is active and nobody touches the frame from the evening to the morning
- **THEN** the display goes off within a minute of the off time and on within a minute of the on time

#### Scenario: Clock corrected after boot
- **WHEN** the device boots with a clock that is wrong but later than 1 January 2026 and corrects it later
- **THEN** within a minute of the correction the display follows the schedule for the corrected time
