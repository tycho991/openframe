## MODIFIED Requirements

### Requirement: Display schedule in the interface
The settings SHALL show the display schedule with its on/off switch, the on and off time for weekdays and for the weekend, the minutes after waking, the dim mode, the dim level, and for mode `fixed` an optional dim time for each window, for mode `sun` the frame's place. While device administration is not active, the switch SHALL be disabled, with a notice that it can be activated in the device menu on the frame. While the brightness cannot be changed, the dim fields SHALL carry the brightness notice.

#### Scenario: Set the schedule
- **WHEN** the user switches the schedule on, sets the weekend on time to 09:00 and saves
- **THEN** the change is confirmed without reloading and the frame follows the new times

#### Scenario: No device administration
- **WHEN** the user opens the settings of a frame on which device administration is not active
- **THEN** the schedule's switch is disabled and the notice explains the entry in the device menu

#### Scenario: Dim in the evening
- **WHEN** the user sets the weekday dim time to 20:30 and the dim level to 25 % and saves
- **THEN** the change is confirmed without reloading, and an invalid dim time shows its error at the weekday dim field

#### Scenario: Dim by the sun
- **WHEN** the user chooses the dim mode "By the sun", picks a place and saves
- **THEN** the change is confirmed without reloading, the dim time fields are hidden, and today's sunrise and sunset at that place are shown

### Requirement: Attribution of the place data
The interface SHALL name GeoNames as the source of the place names with its licence (CC BY 4.0), next to each place setting (the place in the captions and the frame's place in the display schedule), with a link to the licence text and a note on the changes made to the data: converted, positions rounded, one German name chosen per place. The attribution SHALL be available offline, and the full source note SHALL be reachable offline from the licence menu.

#### Scenario: Settings open
- **WHEN** the user opens the caption settings without internet access
- **THEN** the attribution of GeoNames and its licence is shown next to the place setting

#### Scenario: Licence and changes
- **WHEN** the user opens the caption settings
- **THEN** the licence name links to the text of CC BY 4.0 and the attribution says how the data was changed
- **AND** the licence menu opens the full source note of the place data without internet access

#### Scenario: Frame's place
- **WHEN** the user opens the display schedule in mode "By the sun"
- **THEN** the attribution of GeoNames and its licence is shown next to the frame's place

## ADDED Requirements

### Requirement: Place search in the interface
The frame's place SHALL be chosen by typing at least 2 characters of its name. The interface SHALL ask the frame's place search after a pause in typing, show up to 10 places with name, region and country, and take the chosen one with its coordinates. It SHALL show the chosen place and SHALL load nothing from another server.

#### Scenario: Pick a place
- **WHEN** the user types "kass" into the place field and chooses "Kassel, Hesse, Germany"
- **THEN** the field shows that place, and saving stores it with its coordinates

#### Scenario: Equal names
- **WHEN** the user types "neustadt"
- **THEN** the list shows each place with its region and country, so that equal names can be told apart

#### Scenario: Nothing found
- **WHEN** the user types a name that matches no place
- **THEN** the list says that no place was found, and the stored place is unchanged

#### Scenario: Mode sun without a place
- **WHEN** the user chooses "By the sun" on a frame without a place and has not picked one
- **THEN** saving is not possible, and the place field says that a place is needed
