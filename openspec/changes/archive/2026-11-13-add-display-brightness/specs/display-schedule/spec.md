# Spec Delta

## ADDED Requirements

### Requirement: Dim settings
The display schedule SHALL include an optional dim time per window (`HH:MM`, default none) and one dim level of 1 to 100 % (default 20 %). A dim time SHALL lie after the on time and before the off time along the window's period; for a window with equal on and off times, any time but the on time. Otherwise the change SHALL be rejected with 400 naming that window's dim field.

#### Scenario: Defaults
- **WHEN** a paired client fetches the settings of a frame that never had dimming
- **THEN** neither window has a dim time and the dim level is 20

#### Scenario: Dim time outside the window
- **WHEN** the weekday window is on 07:00, off 22:00 and a client sets the weekday dim time to 23:00
- **THEN** the API answers with 400, names the weekday dim field, and the schedule stays unchanged

#### Scenario: Window moved past the dim time
- **WHEN** the weekday dim time is 21:00 and a client sets only the weekday off time to 20:00
- **THEN** the API answers with 400 and names the weekday dim field

#### Scenario: Window across midnight
- **WHEN** the weekday window is on 07:00, off 01:00 and a client sets the weekday dim time to 23:30
- **THEN** the change is accepted

### Requirement: Dim periods
While the schedule is active and the brightness can be changed (see `display-brightness`), the frame SHALL set the dim level from a window's dim time to the end of the on period that started that day, within one minute. For a window with equal on and off times the dim period SHALL end at the end of that day.

#### Scenario: Weekday evening
- **WHEN** the schedule is active with the default windows, the weekday dim time 20:30 and the dim level 20, and it is Wednesday 20:30
- **THEN** within a minute the display is at 20 %, and at 22:00 it goes off

#### Scenario: Schedule off
- **WHEN** a weekday dim time is set but the schedule is off
- **THEN** the frame does not dim

#### Scenario: Without permission
- **WHEN** the schedule is active with a dim time and the permission to modify system settings is not granted
- **THEN** the display keeps its brightness and only switches on and off by the schedule

### Requirement: Dimmed during the night
While the display is on during an off period that follows a dim period, because it was woken or switched on by hand, the frame SHALL use the dim level, unless a client set a brightness during that time.

#### Scenario: Power button at night
- **WHEN** the display went off at 22:00 after dimming and someone presses the power button at 23:00
- **THEN** the slideshow appears at the dim level

### Requirement: Brightness after a dim period
When the next on period begins after a dim period, the frame SHALL apply the stored brightness: the level in mode `manual`, the automatic brightness in mode `auto`, and in mode `system` the brightness and automatic setting that Android had when dimming began, also after a restart of the app or the device in between.

#### Scenario: Morning in mode manual
- **WHEN** the mode is `manual` at 80 % and the display goes on at 07:00 after a dimmed evening
- **THEN** it is at 80 %

#### Scenario: Morning in mode system
- **WHEN** the mode is `system`, Android's automatic brightness was on when dimming began, and the device restarted during the night
- **THEN** at the on time the automatic brightness is on again

### Requirement: Dimming changed during a dim period
If the schedule is switched off, or a dim time or the dim level is changed so that the current moment is no longer in a dim period, the frame SHALL apply the stored brightness within a minute; a change that starts a dim period, or changes the dim level during one, SHALL set the dim level within a minute.

#### Scenario: Dim time removed in the evening
- **WHEN** the display is dimmed at 21:00 and a client removes the weekday dim time
- **THEN** within a minute the stored brightness applies
