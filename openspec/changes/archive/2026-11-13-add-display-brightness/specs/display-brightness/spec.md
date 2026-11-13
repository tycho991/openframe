# Spec Delta

## Purpose

Sets how bright the frame's display is: a fixed level, Android's automatic brightness, or Android's own setting left alone, changed through the settings or at once through the API, with the permission to modify system settings as its precondition.

## ADDED Requirements

### Requirement: Brightness setting
The settings SHALL include the brightness: a mode, `system` (default), `manual` or `auto`, and a level of 1 to 100 % for `manual` (default 80 %). Invalid values SHALL be rejected as in `frame-settings` › Validation; settings stored before the brightness existed SHALL load with the defaults.

#### Scenario: Defaults
- **WHEN** a paired client fetches the settings of a frame that never had a brightness
- **THEN** the mode is `system` and the level is 80

#### Scenario: Invalid level
- **WHEN** a client sets the level to 0
- **THEN** the API answers with 400, names the level field, and the brightness stays unchanged

### Requirement: Modes of the brightness
In mode `manual` the frame SHALL set Android's system brightness to manual with the stored level; in mode `auto` it SHALL switch on Android's automatic brightness. In mode `system` the frame SHALL NOT change Android's brightness, except while the display schedule dims. Switching to `system` SHALL leave the brightness as it is at that moment.

#### Scenario: Manual level
- **WHEN** a client sets the mode to `manual` with the level 40
- **THEN** within a second Android's brightness is manual at 40 % and the display is visibly dimmer

#### Scenario: Back to system
- **WHEN** the mode is `manual` at 40 % and a client sets it to `system`
- **THEN** the display stays at 40 % until someone changes it on the device

### Requirement: Level scale
The level SHALL map linearly onto Android's brightness scale, 100 % to its highest value and 1 % to its lowest value above zero, so that no level switches the backlight off. Levels read from Android SHALL be converted back the same way and rounded to whole percent.

#### Scenario: Lowest level
- **WHEN** a client sets the level to 1 %
- **THEN** the backlight is on at its lowest brightness and the photo is still recognisable in a dark room

### Requirement: Permission as precondition
Changing the brightness SHALL require the permission to modify system settings. Without it, setting the mode to `manual` or `auto` SHALL be rejected with 400 naming the mode field, and a brightness through `PUT /display` with 409 `brightness_unavailable`. Setting only the level, or the mode `system`, SHALL be accepted. No root access SHALL be needed.

#### Scenario: Manual without permission
- **WHEN** the permission is not granted and a client sets the mode to `manual`
- **THEN** the API answers with 400, names the mode field, and the mode stays `system`

#### Scenario: Brightness now without permission
- **WHEN** the permission is not granted and a client sends a brightness to `PUT /display`
- **THEN** the API answers with 409 and `brightness_unavailable`, and the brightness is unchanged

### Requirement: Automatic brightness needs a light sensor
The mode `auto` SHALL be accepted only on a device with a light sensor; elsewhere it SHALL be rejected with 400 naming the mode field.

#### Scenario: No light sensor
- **WHEN** a client sets the mode to `auto` on a device without a light sensor
- **THEN** the API answers with 400, names the mode field, and the mode stays as it was

### Requirement: Losing the permission
If the permission is withdrawn, the stored brightness settings SHALL be kept but SHALL NOT be applied. Once the permission is granted again, the frame SHALL apply them within a minute.

#### Scenario: Withdrawn and granted again
- **WHEN** the mode is `manual` at 40 %, the permission is withdrawn, someone sets the brightness to 100 % on the device and the permission is granted again
- **THEN** the settings still show `manual` at 40 %, and within a minute of granting the display is at 40 % again

### Requirement: Permission on the frame
On Android 6 and later the device menu on the frame SHALL offer an entry that opens the system screen for allowing openframe to modify system settings, and SHALL say whether it is allowed. On Android 5 the permission is granted with the installation and the entry SHALL NOT be shown.

#### Scenario: Allow on Android 6
- **WHEN** the user chooses the entry on the Frameo and allows modifying system settings
- **THEN** within a minute `GET /display` reports the brightness as available

#### Scenario: Android 5
- **WHEN** openframe is installed on the Lenovo
- **THEN** the brightness is available without any step and the device menu has no entry for it

### Requirement: Brightness now
A paired client SHALL be able to set a level of 1 to 100 % at once through `PUT /display`, together with `on` or alone. Outside a dim period of the schedule the level SHALL become the stored level with mode `manual`; during a dim period it SHALL hold until that period ends, and the stored settings SHALL stay unchanged.

#### Scenario: By day
- **WHEN** the mode is `auto` and a client sends the brightness 60 at noon
- **THEN** the display is at 60 % at once, and the settings show mode `manual` with level 60

#### Scenario: During a dim period
- **WHEN** the schedule dims to 20 % from 21:00 and a client sends the brightness 50 at 21:30
- **THEN** the display is at 50 % until the off time, the settings keep their level, and the next morning the stored level applies

#### Scenario: On and brightness together
- **WHEN** the display is off by hand and a client sends `on` true with the brightness 30
- **THEN** the display goes on at 30 %

### Requirement: Atomic display requests
A request to `PUT /display` SHALL be validated completely before it acts: a body with neither `on` nor `brightness`, a level outside 1 to 100, or a part that is not available SHALL be rejected without switching or changing anything.

#### Scenario: Display not available
- **WHEN** openframe is not a device administrator and a client sends `on` false with the brightness 30
- **THEN** the API answers with 409 and `display_unavailable`, and the brightness is unchanged

### Requirement: Changes on the device
A brightness changed on the device, for example in Android's quick settings, SHALL hold until the frame next applies its own: at start, when the brightness or the schedule's dimming is changed through the API, and when a dim period begins or ends. The frame SHALL NOT undo it at any other time.

#### Scenario: Quick settings by day
- **WHEN** the mode is `manual` at 40 % and someone drags the brightness to 90 % in the quick settings at noon
- **THEN** the display stays at 90 %, and `GET /display` reports 90 within 30 seconds

#### Scenario: Next dim period
- **WHEN** someone set 90 % on the device at noon and the schedule dims from 21:00
- **THEN** at 21:00 the display goes to the dim level

### Requirement: Brightness in the display state
`GET /display` SHALL report whether the brightness can be changed, whether automatic brightness is supported, Android's current level in percent and whether its automatic brightness is on, and whether the schedule dims at the moment. Level and automatic brightness SHALL be reported also without the permission.

#### Scenario: State in the evening
- **WHEN** a client reads the display state at 21:30 while the schedule dims to 20 %
- **THEN** it learns that the brightness is available, at 20 %, not automatic, and dimmed by the schedule

#### Scenario: Without permission
- **WHEN** the permission is not granted and Android's brightness is at 50 %
- **THEN** `GET /display` reports the brightness as not available and its level as 50
