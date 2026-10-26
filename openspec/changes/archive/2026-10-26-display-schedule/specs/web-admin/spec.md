# Delta for web-admin

## ADDED Requirements

### Requirement: Display schedule in the interface
The settings SHALL show the display schedule with its on/off switch, the on and off time for weekdays and for the weekend, and the minutes after waking. While device administration is not active, the switch SHALL be disabled, with a notice that it can be activated in the device menu on the frame.

#### Scenario: Set the schedule
- **WHEN** the user switches the schedule on, sets the weekend on time to 09:00 and saves
- **THEN** the change is confirmed without reloading and the frame follows the new times

#### Scenario: No device administration
- **WHEN** the user opens the settings of a frame on which device administration is not active
- **THEN** the schedule's switch is disabled and the notice explains the entry in the device menu

### Requirement: Display state and manual switch
The interface SHALL show whether the display is on, why, and when it changes next by itself, and SHALL offer a button to switch it off or on now. The button SHALL be disabled while device administration is not active. The state SHALL follow changes made elsewhere as in "Current state".

#### Scenario: Switch off now
- **WHEN** the display is on by day and the user chooses "Turn display off"
- **THEN** the display goes off, and the interface shows it as off until the next switching time

#### Scenario: Changed on the frame
- **WHEN** the interface is open and someone wakes the frame with the power button at night
- **THEN** the interface shows the display as on after 30 seconds at the latest
