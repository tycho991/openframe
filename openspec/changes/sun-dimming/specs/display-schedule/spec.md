## MODIFIED Requirements

### Requirement: Dim periods
While the schedule is active in dim mode `fixed` and the brightness can be changed (see `display-brightness`), a dim period SHALL run from a window's dim time to the end of the on period that started that day. The frame SHALL fade to the dim level from the dim time (see Fade at the dim time) and keep it to the end of the period. For a window with equal on and off times the dim period SHALL end at the end of that day.

#### Scenario: Weekday evening
- **WHEN** the schedule is active with the default windows, the weekday dim time 20:30 and the dim level 20, and it is Wednesday 20:30
- **THEN** the display begins to fade within a minute, is at 20 % at 21:00, and goes off at 22:00

#### Scenario: Schedule off
- **WHEN** a weekday dim time is set but the schedule is off
- **THEN** the frame does not dim

#### Scenario: Without permission
- **WHEN** the schedule is active with a dim time and the permission to modify system settings is not granted
- **THEN** the display keeps its brightness and only switches on and off by the schedule

#### Scenario: Dim time ignored in mode sun
- **WHEN** the weekday dim time is 20:30, the dim mode is `sun` and it is a weekday in June at 20:30, before sunset
- **THEN** the display keeps the day brightness

### Requirement: Dimming changed during a dim period
If the schedule is switched off, or a dim time, the dim level, the dim mode or the place is changed so that the current moment is no longer in a dim period, the frame SHALL apply the stored brightness within a minute; a change that starts a dim period, or changes the dim level during one, SHALL set the brightness the dim period calls for at that moment within a minute.

#### Scenario: Dim time removed in the evening
- **WHEN** the display is dimmed at 21:00 and a client removes the weekday dim time
- **THEN** within a minute the stored brightness applies

#### Scenario: Mode sun chosen at night
- **WHEN** the dim mode is `fixed` without dim times, the sun set two hours ago at the frame's place, and a client sets the dim mode to `sun`
- **THEN** within a minute the display is at the dim level

#### Scenario: Back to fixed at night
- **WHEN** the dim mode is `sun`, the display is at the dim level at night, no window has a dim time, and a client sets the dim mode to `fixed`
- **THEN** within a minute the stored brightness applies

### Requirement: Brightness after a dim period
When a dim period ends, at the next on period in dim mode `fixed` and at sunrise in dim mode `sun`, the frame SHALL apply the stored brightness: the level in mode `manual`, the automatic brightness in mode `auto`, and in mode `system` the brightness and automatic setting that Android had when dimming began, also after a restart of the app or the device in between.

#### Scenario: Morning in mode manual
- **WHEN** the mode is `manual` at 80 % and the display goes on at 07:00 after a dimmed evening in dim mode `fixed`
- **THEN** it is at 80 %

#### Scenario: Morning in mode system
- **WHEN** the mode is `system`, Android's automatic brightness was on when dimming began, and the device restarted during the night
- **THEN** at the end of the dim period the automatic brightness is on again

#### Scenario: On before sunrise in mode sun
- **WHEN** the dim mode is `sun` and the display goes on at 07:00 in December, before sunrise
- **THEN** it is at the dim level, and the stored brightness applies at sunrise

## ADDED Requirements

### Requirement: Dim mode
The display schedule SHALL include a dim mode, `fixed` (default) or `sun`. In mode `fixed` the windows' dim times apply; in mode `sun` the sun's elevation at the frame's place does, and the dim times SHALL stay stored but SHALL NOT apply. The dim level SHALL apply in both modes. Settings stored before the dim mode existed SHALL load as mode `fixed`.

#### Scenario: Defaults
- **WHEN** a paired client fetches the settings of a frame that never had a dim mode
- **THEN** the dim mode is `fixed` and the frame has no place

#### Scenario: Dim times kept
- **WHEN** the weekday dim time is 20:30, a client sets the dim mode to `sun` and later back to `fixed`
- **THEN** the weekday dim time is still 20:30

### Requirement: Place of the frame
The display schedule SHALL include an optional place of the frame (default none): a name of 1 to 200 characters, the region and country names or `null`, the country code or `null`, a latitude of −90 to 90 and a longitude of −180 to 180 degrees. Invalid values SHALL be rejected as in `frame-settings` › Validation, naming the place field.

#### Scenario: Place chosen
- **WHEN** a client sets the place to Kassel, Hesse, Germany at 51.3127, 9.4797
- **THEN** the settings show this place with these coordinates

#### Scenario: Invalid latitude
- **WHEN** a client sets a place with the latitude 91
- **THEN** the API answers with 400, names the place field, and the schedule stays unchanged

### Requirement: Mode sun needs a place
Setting the dim mode to `sun` while the frame has no place, or removing the place while the dim mode is `sun`, SHALL be rejected with 400. A change that sets the dim mode `sun` and a place together SHALL be accepted.

#### Scenario: Sun without place
- **WHEN** the frame has no place and a client sets the dim mode to `sun`
- **THEN** the API answers with 400, names the dim mode field, and the dim mode stays `fixed`

#### Scenario: Place removed in mode sun
- **WHEN** the dim mode is `sun` and a client sets the place to `null`
- **THEN** the API answers with 400, names the place field, and the place is unchanged

#### Scenario: Both at once
- **WHEN** the frame has no place and a client sets the dim mode `sun` together with a place in one change
- **THEN** the change is accepted

### Requirement: Fade at the dim time
In dim mode `fixed`, from a window's dim time the frame SHALL fade from the day brightness (see Day brightness) to the dim level over 30 minutes, and SHALL keep the dim level from then on. If the dim period ends earlier, the fade SHALL end with it.

#### Scenario: Half way
- **WHEN** the mode is `manual` at 80 %, the dim time is 20:30 and the dim level 20
- **THEN** at 20:45 the display is between 80 % and 20 %, and at 21:00 it is at 20 %

#### Scenario: Off time inside the fade
- **WHEN** the dim time is 21:50 and the off time 22:00
- **THEN** the display fades from 21:50 and goes off at 22:00 before it reaches the dim level

#### Scenario: Woken later that night
- **WHEN** the fade was cut off by the off time at 22:00 and someone presses the power button at 23:00
- **THEN** the slideshow appears at the dim level

### Requirement: Brightness by the sun
While the schedule is active in dim mode `sun` and the brightness can be changed, the frame SHALL set the day brightness while the sun at the frame's place is at −0.833° or higher, the dim level while it is at −6° or lower, and in between fade between the two by the sun's elevation, falling in the evening and rising in the morning. This SHALL apply whenever the display is on.

#### Scenario: Winter evening
- **WHEN** the dim mode is `sun` with a place at 51° N, 10° E, the mode is `manual` at 80 %, the dim level is 20, and it is 21 December
- **THEN** the display is at 80 % until about 16:16, fades until about 16:56, and is at 20 % from then on

#### Scenario: Winter morning
- **WHEN** the same frame goes on by the schedule at 07:00 on 22 December
- **THEN** it is at 20 %, begins to brighten at about 07:42, and is at 80 % from about 08:22

#### Scenario: Summer evening
- **WHEN** the same frame is on until 22:00 on 21 June
- **THEN** it begins to fade at about 21:39 and goes off at 22:00 before it reaches the dim level

#### Scenario: Sun does not set
- **WHEN** the frame's place is north of the polar circle and the sun stays above −0.833° all day
- **THEN** the display keeps the day brightness all day

### Requirement: Dim period by the sun
In dim mode `sun` the dim period SHALL run from the moment the sun sinks below −0.833° to the moment it rises to −0.833° again, across the night, including off periods. A display woken or switched on by hand in it SHALL use the brightness the sun calls for. A brightness a client sets in it SHALL hold until it ends, and SHALL end a running fade.

#### Scenario: Power button at night
- **WHEN** the dim mode is `sun`, the display went off at 22:00 and someone presses the power button at 23:00 in December
- **THEN** the slideshow appears at the dim level

#### Scenario: Home Assistant during the evening fade
- **WHEN** the display fades by the sun at 16:30 in December and a client sets the brightness 35
- **THEN** the display is at 35 % at once, does not fade on, stays at 35 % through the night, and in the morning the day brightness applies from sunrise

#### Scenario: Light-sensor automation by day
- **WHEN** the dim mode is `sun` and a client sets the brightness 60 at noon
- **THEN** the display is at 60 %, the stored brightness becomes mode `manual` with level 60, and the evening fade starts from 60 %

### Requirement: Day brightness
The day brightness, from which a fade falls and to which the morning fade rises, SHALL be the stored level in mode `manual`, and in modes `auto` and `system` Android's brightness when the dim period began, kept across restarts. When the morning fade ends, the frame SHALL apply the stored brightness as after a dim period (see Brightness after a dim period).

#### Scenario: Automatic by day
- **WHEN** the mode is `auto`, Android's automatic brightness is at 55 % at sunset, and the dim mode is `sun`
- **THEN** the display fades from 55 % to the dim level, and at sunrise the next morning Android's automatic brightness is on again

#### Scenario: Restart at night
- **WHEN** the mode is `system`, Android's brightness was 70 % at sunset, and the device restarts at 02:00
- **THEN** the morning fade rises towards 70 %, and Android's setting from before the evening is back at sunrise

### Requirement: Smooth fades
During a fade the frame SHALL change the brightness at least every 5 seconds whenever the value the fade calls for has changed, so that no step is seen as a jump. The fade SHALL change the brightness by equal ratios in equal times, not by equal percentage points, so that it does not seem to drop near its low end.

#### Scenario: Fade near the low end
- **WHEN** the display fades from 80 % to 5 %
- **THEN** half way through the fade it is at 20 %, and no step between two writes exceeds the step the fade calls for in 5 seconds

#### Scenario: Restart during a fade
- **WHEN** the app restarts half way through a fade
- **THEN** within a minute the display is at the value the fade calls for at that moment, and the fade goes on from there

### Requirement: Sun times in the display state
In dim mode `sun`, `GET /display` SHALL report the times of today's sunrise and sunset at the frame's place, or `null` for each that does not occur that day. In mode `fixed` it SHALL report no sun times.

#### Scenario: Sun times in December
- **WHEN** a client reads the display state on 21 December with the dim mode `sun` and a place at 51° N, 10° E
- **THEN** it learns that the sun rises at about 08:21 and sets at about 16:16 local time

#### Scenario: Mode fixed
- **WHEN** a client reads the display state with the dim mode `fixed`
- **THEN** the sun times are `null`
