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
- **WHEN** the device boots with a clock that is wrong but later than 1 January 2026 and corrects it later
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
