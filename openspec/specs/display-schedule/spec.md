# display-schedule Specification

## Purpose
Switches the frame's display really off and on again: by a schedule with one window for weekdays and one for the weekend, by a manual switch, and by waking it with the power button. It requires openframe to be an active device administrator.

## Requirements

### Requirement: Schedule settings
The settings SHALL include a display schedule: on/off (default off), a window for weekdays (Monday to Friday, default on at 07:00, off at 22:00), a window for the weekend (Saturday and Sunday, default on at 08:00, off at 23:00), each time as `HH:MM` from 00:00 to 23:59, and the minutes after waking (1 to 120, default 5). Invalid values SHALL be rejected as in `frame-settings` › Validation.

#### Scenario: Defaults
- **WHEN** a paired client fetches the settings of a frame that never had a schedule
- **THEN** the schedule is off, with the default windows and 5 minutes after waking

#### Scenario: Invalid time
- **WHEN** a client sets the weekday off time to `24:00`
- **THEN** the API answers with 400, names that field, and the schedule stays unchanged

#### Scenario: Only the weekend window
- **WHEN** a client changes only the weekend on time to 09:30
- **THEN** the weekend on time is 09:30 and all other schedule settings are unchanged

### Requirement: Periods of the schedule
While the schedule is active, the display SHALL be on from the on time to the off time of the window of the day on which that period starts, and off otherwise. An off time before the on time SHALL end the period on the next day. Equal on and off times SHALL keep the display on for that whole day. Times SHALL be the device's local time.

#### Scenario: Weekday evening
- **WHEN** the schedule is active with the defaults and it is Wednesday 22:00
- **THEN** the display goes off

#### Scenario: Friday night
- **WHEN** the weekday window is on 07:00, off 01:00 and it is Saturday 00:30
- **THEN** the display is still on, because the period started on Friday, and it goes off at 01:00

#### Scenario: Weekend morning
- **WHEN** the schedule is active with the defaults and it is Saturday 07:30
- **THEN** the display is off, and it goes on at 08:00

#### Scenario: Same on and off time
- **WHEN** the weekend window is on 08:00 and off 08:00 and the weekday window has the defaults
- **THEN** the display is on all Saturday and all Sunday, goes off on Monday at 00:00 and on at 07:00

### Requirement: Switching on time
The frame SHALL switch at a switching time within one minute, also while the display is off and nobody uses the frame. A change of the device's clock or time zone SHALL take effect within one minute, without a restart.

#### Scenario: Night without touch
- **WHEN** the schedule is active and nobody touches the frame from the evening to the morning
- **THEN** the display goes off within a minute of the off time and on within a minute of the on time

#### Scenario: Clock corrected after boot
- **WHEN** the device boots with a wrong clock and corrects it later
- **THEN** within a minute of the correction the display follows the schedule for the corrected time

### Requirement: Real display off
When the schedule, the manual switch or the end of a waking period calls for the display to be off, the frame SHALL switch the display off as the power button does, with the backlight off, and SHALL NOT merely show a black picture. Switching it on again SHALL show the slideshow without a lock screen, provided no screen lock is set up.

#### Scenario: Off at night
- **WHEN** the display goes off by the schedule
- **THEN** the backlight is off and the system reports the screen as off

#### Scenario: On in the morning
- **WHEN** the display goes on by the schedule
- **THEN** the slideshow is in front, without a lock screen

### Requirement: Device administration as precondition
Switching the display off SHALL require openframe to be an active device administrator with no policy other than locking the screen. Without it, switching the schedule on SHALL be rejected with 400 naming that field, and the manual switch with 409. No root access SHALL be needed.

#### Scenario: Schedule without device administration
- **WHEN** openframe is not a device administrator and a client switches the schedule on
- **THEN** the API answers with 400, names the schedule's on/off field, and the schedule stays off

#### Scenario: Manual switch without device administration
- **WHEN** openframe is not a device administrator and a client switches the display off
- **THEN** the API answers with 409 and the display stays on

### Requirement: Losing device administration
If device administration ends while the schedule is on, the stored schedule SHALL be kept but SHALL NOT switch the display, and a display that is off SHALL be switched on within a minute. Once device administration is active again, the stored schedule SHALL apply again.

#### Scenario: Deactivated at night
- **WHEN** the display is off by the schedule and device administration is deactivated
- **THEN** the display goes on within a minute and stays on, and the settings still show the schedule as on

### Requirement: Device administration on the frame
The device menu on the frame SHALL offer an entry that activates device administration through the system's confirmation, and, while it is active, an entry that deactivates it. The entry SHALL say whether device administration is active.

#### Scenario: Activate
- **WHEN** the user chooses the entry in the device menu and confirms the system dialog
- **THEN** openframe is a device administrator and the schedule can be switched on

#### Scenario: Deactivate
- **WHEN** device administration is active and the user chooses to deactivate it in the device menu
- **THEN** openframe is no longer a device administrator and can be uninstalled

### Requirement: Waking during an off period
If the display is switched on while the schedule calls for it to be off, for example by the power button, it SHALL stay on until the configured minutes after waking have passed since the last touch, and then go off again, unless an on period has begun in the meantime.

#### Scenario: Power button at night
- **WHEN** the display is off by the schedule and someone presses the power button
- **THEN** the slideshow appears and the display goes off again 5 minutes after the last touch

#### Scenario: Touches extend the time
- **WHEN** the frame was woken at night and someone swipes through photos for 10 minutes
- **THEN** the display stays on and goes off 5 minutes after the last swipe

### Requirement: Grace at start and on changes
If the app starts, or the schedule is switched on or changed, while the display is on and the schedule calls for off, the display SHALL go off only after the minutes after waking, as after waking. If the schedule is switched off or changed so that it calls for on, a display that is off by the schedule SHALL go on within a minute.

#### Scenario: Schedule switched on in the evening
- **WHEN** a user switches the schedule on at 23:00 with the default windows
- **THEN** the display goes off 5 minutes after the last touch, not at once

#### Scenario: Schedule switched off at night
- **WHEN** the display is off by the schedule and a client switches the schedule off
- **THEN** the display goes on within a minute

#### Scenario: Reboot at night
- **WHEN** the device restarts during an off period
- **THEN** the slideshow appears, and the display goes off again 5 minutes after the last touch

### Requirement: Manual switch
A paired client SHALL be able to switch the display on or off. The choice SHALL hold until the schedule's next switching time. Without an active schedule, off SHALL hold until a client switches it on or someone wakes the frame, and waking SHALL end a manual off. The choice SHALL NOT survive a restart of the app.

#### Scenario: Off by day
- **WHEN** the schedule is active with the defaults and a client switches the display off on Wednesday at 15:00
- **THEN** the display stays off through the night and goes on on Thursday at 07:00

#### Scenario: On at night
- **WHEN** the display is off by the schedule and a client switches it on at 23:30
- **THEN** it stays on until the next switching time, the off time of the following evening

#### Scenario: Off without schedule, then the power button
- **WHEN** the schedule is off, a client switches the display off and someone later presses the power button
- **THEN** the display stays on

### Requirement: Display state in the API
A paired client SHALL be able to read whether display control is available, whether the display is on, why (schedule, manual switch, waking or no schedule), and when it changes next by itself, or that it does not. Reading and switching SHALL require a token.

#### Scenario: State at night
- **WHEN** a client reads the display state on Wednesday at 23:00 with the default schedule active
- **THEN** it learns that the display is off by the schedule and goes on on Thursday at 07:00

#### Scenario: Without token
- **WHEN** a client without a token reads or switches the display
- **THEN** the API answers with 401

### Requirement: Slideshow rests while the display is off
While the display is off, the slideshow SHALL NOT change photos and SHALL NOT render display images for upcoming photos. When the display goes on, the next photo SHALL appear at once, unless the user had paused the slideshow, in which case the paused photo stays.

#### Scenario: Morning
- **WHEN** the display goes on in the morning after a night off
- **THEN** a new photo appears at once and the interval starts over

#### Scenario: Paused before the night
- **WHEN** the user paused the slideshow in the evening and the display goes on in the morning
- **THEN** the same photo is shown and the slideshow is still paused

### Requirement: Reachable while the display is off
While the display is off, the admin interface SHALL stay reachable from the LAN, and the remote sources SHALL keep syncing on their schedule.

#### Scenario: Admin interface at night
- **WHEN** the display has been off for six hours
- **THEN** the admin interface answers from the LAN at once, and a photo uploaded then appears in the slideshow after the display goes on
