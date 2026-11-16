## ADDED Requirements

### Requirement: Android settings from the device menu
The device menu on the frame SHALL offer an entry that opens the start page of Android's settings, also in safe mode. The entry SHALL be shown only if the device has an activity for Android's settings. Before the settings open, the app SHALL record a planned process end. The app SHALL NOT bring the slideshow back over the settings by itself; the Home button or Back SHALL lead back to it.

#### Scenario: Open the settings
- **WHEN** someone chooses the entry for Android's settings in the device menu
- **THEN** the start page of Android's settings appears, and the admin interface stays reachable from the LAN

#### Scenario: Back to the slideshow
- **WHEN** Android's settings are open from the device menu and someone presses the Home button
- **THEN** the slideshow appears

#### Scenario: Settings left open
- **WHEN** Android's settings were opened from the device menu and nobody touches the device for 10 minutes
- **THEN** the settings are still on screen

#### Scenario: Firmware ends the app
- **WHEN** the device's firmware ends the app right after it opened Android's settings
- **THEN** this end does not count as an early end, and the server answers again within 40 seconds

#### Scenario: In safe mode
- **WHEN** the app runs in safe mode and someone opens the device menu
- **THEN** the menu offers the entry for Android's settings

#### Scenario: No settings activity
- **WHEN** the device has no activity for Android's settings
- **THEN** the device menu has no entry for them
