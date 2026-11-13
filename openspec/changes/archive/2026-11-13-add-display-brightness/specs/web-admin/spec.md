# Spec Delta

## ADDED Requirements

### Requirement: Brightness in the settings
The settings SHALL show the brightness with its mode (Android's setting, a fixed level, automatic) and the level. Automatic SHALL be offered only where the frame supports it. While the brightness cannot be changed, only Android's setting SHALL be selectable, with a notice that the permission can be granted in the device menu on the frame.

#### Scenario: Fixed level
- **WHEN** the user chooses a fixed level of 40 % and saves
- **THEN** the change is confirmed without reloading and the frame's display is at 40 %

#### Scenario: No permission
- **WHEN** the user opens the settings of a frame that may not modify system settings
- **THEN** only Android's setting can be chosen and the notice explains the entry in the device menu

#### Scenario: No light sensor
- **WHEN** the user opens the settings of a frame without a light sensor
- **THEN** automatic brightness is not offered

## MODIFIED Requirements

### Requirement: Display schedule in the interface
The settings SHALL show the display schedule with its on/off switch, the on and off time for weekdays and for the weekend, the minutes after waking, an optional dim time for each window and the dim level. While device administration is not active, the switch SHALL be disabled, with a notice that it can be activated in the device menu on the frame. While the brightness cannot be changed, the dim fields SHALL carry the brightness notice.

#### Scenario: Set the schedule
- **WHEN** the user switches the schedule on, sets the weekend on time to 09:00 and saves
- **THEN** the change is confirmed without reloading and the frame follows the new times

#### Scenario: No device administration
- **WHEN** the user opens the settings of a frame on which device administration is not active
- **THEN** the schedule's switch is disabled and the notice explains the entry in the device menu

#### Scenario: Dim in the evening
- **WHEN** the user sets the weekday dim time to 20:30 and the dim level to 25 % and saves
- **THEN** the change is confirmed without reloading, and an invalid dim time shows its error at the weekday dim field

### Requirement: Display state and manual switch
The interface SHALL show whether the display is on, why, and when it changes next by itself, and SHALL offer a button to switch it off or on now. The button SHALL be disabled while device administration is not active. It SHALL also show the current brightness, whether the schedule dims, and a slider that sets the brightness at once, disabled while the brightness cannot be changed. The state SHALL follow changes made elsewhere as in "Current state".

#### Scenario: Switch off now
- **WHEN** the display is on by day and the user chooses "Turn display off"
- **THEN** the display goes off, and the interface shows it as off until the next switching time

#### Scenario: Changed on the frame
- **WHEN** the interface is open and someone wakes the frame with the power button at night
- **THEN** the interface shows the display as on after 30 seconds at the latest

#### Scenario: Brightness now
- **WHEN** the user drags the brightness slider to 60 % and lets go
- **THEN** the frame's display is at 60 % at once and the interface shows 60 %
