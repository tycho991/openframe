## ADDED Requirements

### Requirement: Android settings from the device menu
The device menu on the frame SHALL offer an entry that opens the start page of Android's settings each time it is chosen, also in safe mode. The entry SHALL be shown only if the device has an activity for Android's settings and is not a device whose firmware ends the app when another activity starts. The app SHALL NOT bring the slideshow back over the settings by itself while their system-screen record is valid.

#### Scenario: Open the settings
- **WHEN** someone chooses the entry for Android's settings in the device menu
- **THEN** the start page of Android's settings appears, and the admin interface stays reachable from the LAN

#### Scenario: Start page again
- **WHEN** someone left Android's settings on a subpage, went back to the slideshow and chooses the entry again
- **THEN** the start page of Android's settings appears, not the subpage

#### Scenario: Settings left open
- **WHEN** Android's settings were opened from the device menu and nobody touches the device for 30 minutes
- **THEN** the settings are still in front, not the slideshow

#### Scenario: In safe mode
- **WHEN** the app runs in safe mode on a device that offers the entry and someone opens the device menu
- **THEN** the menu offers the entry for Android's settings

#### Scenario: No settings activity
- **WHEN** the device has no activity for Android's settings
- **THEN** the device menu has no entry for them

### Requirement: Android settings hidden where the firmware ends the app
The app SHALL recognize a device whose firmware ends the app when another activity starts by its firmware, the Frameo's frame app being known to the system whether enabled or not, or by a start of the same installation that came within a minute after a system-screen record from the device menu was made or renewed, with no Java crash before it. On such a device the menu SHALL NOT offer Android's settings; its other entries SHALL stay as they are.

#### Scenario: On the Frameo
- **WHEN** someone opens the device menu on the Frameo `PFF_1024`
- **THEN** the menu has no entry for Android's settings, and the entries for device administration and the brightness permission are offered as before

#### Scenario: Firmware not recognized
- **WHEN** on a device not recognized by its firmware the app ended without a Java crash after it opened a system screen from the device menu, it started again within a minute of the opening, and someone opens the device menu
- **THEN** the menu has no entry for Android's settings, until a new installation of the app

#### Scenario: On the Lenovo
- **WHEN** someone opens the device menu on the Lenovo Yoga Tablet 2 1050F
- **THEN** the menu offers the entry for Android's settings

### Requirement: Way back from Android's settings
While openframe is the HOME app, the Home button SHALL lead from Android's settings to the slideshow. Back on the start page of the settings SHALL lead to the slideshow also when openframe is not the HOME app. When the slideshow is back after the settings and openframe is not the HOME app, the log SHALL say so.

#### Scenario: Home button
- **WHEN** openframe is the HOME app, Android's settings are open from the device menu and someone presses the Home button
- **THEN** the slideshow appears

#### Scenario: Back on the start page
- **WHEN** Android's settings are open from the device menu and someone presses Back on their start page
- **THEN** the slideshow appears

#### Scenario: HOME app changed in the settings
- **WHEN** someone chooses another HOME app in Android's settings and then goes back to the slideshow with Back
- **THEN** the log has a warning that openframe is not the HOME app

### Requirement: System screen from the device menu
Before the device menu opens a system screen (device administration, the brightness permission or Android's settings), the app SHALL record a planned end. The record SHALL stay valid across further process ends while the screen is in front, renewed for 10 minutes at each start, and the app SHALL withdraw it when the slideshow is back in front. A Java crash SHALL count as a crash despite the record.

#### Scenario: Several ends under a system screen
- **WHEN** the app process ends three times in a row without a Java crash, each time less than 10 minutes after its start, while a system screen opened from the device menu stays in front
- **THEN** none of these ends counts as an early end, and the app does not start in safe mode

#### Scenario: Back from a system screen
- **WHEN** the slideshow is back in front after Android's settings in the same process, and the process ends without a Java crash a minute later
- **THEN** the next start logs an unexplained end, not a planned one, and counts it as an early end if the process ran less than 10 minutes

#### Scenario: Java crash under a system screen
- **WHEN** the app has a Java crash less than 10 minutes after its start while Android's settings opened from the device menu are in front
- **THEN** the next start logs a Java crash with its cause, not a planned end, and counts it as an early end

### Requirement: Display while a system screen is open
While a system screen opened from the device menu is in front, the display SHALL stay on in the process that opened it until 10 minutes after the opening, also in an off period of the schedule and with a short screen timeout of the system; a screen-off meanwhile SHALL NOT count as switching off by hand. Afterwards the display SHALL follow the schedule again, with the minutes after waking as after the power button.

#### Scenario: Short screen timeout under the settings
- **WHEN** the system's screen timeout is 30 seconds and Android's settings stay open from the device menu for 5 minutes without touch
- **THEN** the display stays on with the settings for the whole 5 minutes

#### Scenario: Off period under the settings
- **WHEN** device administration is active, an off period of the display schedule begins 2 minutes after Android's settings were opened from the device menu, and nobody touches the device
- **THEN** the display stays on until 10 minutes after the opening, and goes off by the schedule after the minutes after waking, with the settings still in front

## MODIFIED Requirements

### Requirement: Self-healing
If the display or the server crashes, the app SHALL restart within 30 seconds, unless it starts in safe mode; the display SHALL NOT return while a system screen from the device menu has a valid record. From the third early end in a row the display restart SHALL wait a growing delay (30 s, 2 min, then 10 min); the server SHALL NOT wait for it. At each start the app SHALL log how the previous run ended: Java crash with its cause, unexplained end, device restart, new installation or planned end.

#### Scenario: Display crash
- **WHEN** the display is terminated because of an error
- **THEN** the slideshow is running again after 30 seconds at the latest

#### Scenario: Unexplained end in the log
- **WHEN** the app process is killed without a Java crash, for example by a native crash or by the system
- **THEN** after the restart the log contains an entry saying that the previous run ended unexplained

#### Scenario: Display after an unexplained end
- **WHEN** the app process is killed without a Java crash and the system brings back only the server
- **THEN** the display comes back on its own, from the third early end in a row after the growing delay

#### Scenario: Repeated early ends
- **WHEN** the app ends by a Java crash for the third time in a row, each time less than 10 minutes after its start
- **THEN** the server is reachable again within 40 seconds, and the display comes back only after the growing delay

#### Scenario: Crash under a system screen
- **WHEN** the app has a Java crash while Android's settings opened from the device menu are in front
- **THEN** the settings stay in front, and the server is reachable again within 40 seconds

### Requirement: Planned process end
The app SHALL record an intended process end before it happens, with a limited validity: installing an update, restarting from the device menu or the API, and starting a system screen, whose record is renewed and withdrawn as "System screen from the device menu" says. A new installation SHALL always count as planned. A planned end SHALL count neither as an early end nor as a crash; a record whose validity has passed SHALL NOT count.

#### Scenario: Restart for an update
- **WHEN** the app ends because an update it started is being installed
- **THEN** the next start logs a planned end and the count of early ends is unchanged

#### Scenario: System screen ends the app
- **WHEN** the device's firmware ends the app right after it opened the device administration screen
- **THEN** this end does not count as an early end
