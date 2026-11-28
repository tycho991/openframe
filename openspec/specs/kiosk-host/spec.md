# kiosk-host Specification

## Purpose
Runs openframe on an Android device as an unattended picture frame. The app starts by itself, stays in the foreground and keeps the display and the server running. It runs on old devices from Android 5.0 without root.

## Requirements

### Requirement: Supported devices
The app SHALL run on Android 5.0 (API 21) and newer, on the architectures armeabi-v7a, arm64-v8a, x86 and x86_64. It SHALL NOT require root access for basic operation.

#### Scenario: Lenovo Yoga Tablet 2
- **WHEN** the APK is installed on the Lenovo Yoga Tablet 2 1050F (Android 5.0.1, x86)
- **THEN** it starts and shows the slideshow

#### Scenario: Frameo
- **WHEN** the APK is installed on the Frameo `PFF_1024` (Android 6.0.1, armeabi-v7a)
- **THEN** it starts and shows the slideshow

### Requirement: Autostart
The app SHALL start without intervention after the device boots and show the slideshow. It SHALL offer itself as a HOME launcher so that the Home button leads to the slideshow.

#### Scenario: Restart
- **WHEN** the device restarts
- **THEN** it shows the slideshow without intervention, provided no screen lock is set up

#### Scenario: Home button
- **WHEN** the app is the HOME launcher and someone presses the Home button
- **THEN** the slideshow appears

### Requirement: Fullscreen
The slideshow SHALL run in immersive fullscreen without status and navigation bars. After the system shows its bars, it SHALL restore fullscreen by itself.

#### Scenario: Bar shown
- **WHEN** someone swipes from the edge and the system bars appear
- **THEN** the app hides them again after a few seconds

### Requirement: Keep the display awake
While the slideshow is running, the device SHALL NOT go into standby or turn the display off, except when the display schedule, the manual switch or the end of a waking period calls for the display to be off (see `display-schedule`).

#### Scenario: Hours without touch
- **WHEN** nobody touches the device for 12 hours and the display schedule is off
- **THEN** the display is still on and the slideshow is running

#### Scenario: Off by the schedule
- **WHEN** the display schedule calls for the display to be off
- **THEN** the display goes off, and the device does not turn it on again by itself before the schedule's next on time, except for a dark wake while the network is missing (see `display-schedule` › Dark wake for the network) and after device administration ends (see `display-schedule` › Losing device administration)

### Requirement: Server independent of the display
The server SHALL run in a foreground service so that it stays reachable while the display is in the background or restarting. The display SHALL load only once the core in the service is ready, that is, settings and library are loaded. It SHALL NOT depend on the HTTP server or on the network.

#### Scenario: Cold start
- **WHEN** the app starts
- **THEN** the display shows no error about a core that is not ready yet, but the first photo or the empty state, even without Wi-Fi

#### Scenario: Display in the background
- **WHEN** another app comes to the foreground
- **THEN** the admin interface stays reachable from the LAN

#### Scenario: App ended by the firmware
- **WHEN** the device's firmware ends the app because another activity started (observed on the Frameo)
- **THEN** the server answers again within 40 seconds without any interaction, and the display returns when the Home button is pressed

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

### Requirement: Network is optional
The slideshow SHALL keep running without a network. If Wi-Fi drops and comes back, the server SHALL be reachable again without a restart.

#### Scenario: Wi-Fi outage
- **WHEN** Wi-Fi is down for an hour
- **THEN** the slideshow keeps running and once Wi-Fi returns the admin interface is reachable again

#### Scenario: Same network reported again
- **WHEN** Android reports the Wi-Fi the frame is on as available again without losing it, as Android 5 does after roaming
- **THEN** the server keeps running and open connections of the admin interface stay open

### Requirement: Diagnostics
The app SHALL keep a rotating log of limited size on the device. A paired client SHALL be able to fetch it through the API. A failure to write the log SHALL NOT end the app; the fetched log then contains the entries kept in memory. An error repeated with the same message SHALL appear once with its stack trace and afterwards at most every 10 minutes as one line with a counter. Control characters in entries SHALL be escaped.

#### Scenario: Fetch log
- **WHEN** a paired client fetches the log
- **THEN** it receives the latest entries with timestamp and level

#### Scenario: Log not writable
- **WHEN** the log file cannot be written, for example because the storage is full
- **THEN** the app keeps running and the fetched log contains the entries since then

#### Scenario: Same error every few seconds
- **WHEN** the same error occurs 100 times within 10 minutes
- **THEN** the log contains its stack trace once and a line with the number of repetitions

#### Scenario: Line break in a device name
- **WHEN** a device pairs with a name that contains a carriage return
- **THEN** the log entry about the pairing stays one complete entry

### Requirement: Side-by-side operation with pictureframe
The app SHALL have its own package ID so that it can be installed next to `com.pictureframe.kiosk` without touching its data. pictureframe is the author's earlier kiosk app for the same frames; openframe replaces it device by device.

#### Scenario: Both installed
- **WHEN** openframe is installed on a device where pictureframe is running
- **THEN** pictureframe and its data remain unchanged

### Requirement: Decoding in a separate process
The app SHALL check, render and read the metadata of photos in a separate process. A crash of that process SHALL NOT end the display or the server. The display SHALL only load images that the app rendered itself. While no decoding is needed, for example in safe mode, the separate process SHALL NOT run.

#### Scenario: Decoder crashes on a photo
- **WHEN** the decoding process crashes while rendering a photo
- **THEN** the server stays reachable, the slideshow shows the next photo, and the next decoding starts a new decoding process

#### Scenario: Decoder hangs
- **WHEN** a decoding does not finish within 60 seconds
- **THEN** the app ends the decoding process and treats the file as if the decoder had crashed on it

#### Scenario: Decoder cannot be started
- **WHEN** the decoding process cannot be started while new files are being checked
- **THEN** no file is listed as skipped because of it, the files are checked at a later scan or sync, and an upload answers 503 with `decoder_unavailable`

### Requirement: Safe mode after repeated crashes
After three early ends in a row, the app SHALL start in safe mode. An early end is a run of less than 10 minutes that ended by a Java crash or unexplained. In safe mode the server, pairing, settings, display schedule, log and device menu SHALL run; slideshow, scan, sync and every image decoding SHALL rest. The frame SHALL show a static notice with the admin address.

#### Scenario: Third crash in a row
- **WHEN** the app crashes three times in a row, each time within 10 minutes of its start
- **THEN** after the next start the frame shows the safe mode notice, the admin interface is reachable and no photo is decoded

#### Scenario: Images in safe mode
- **WHEN** a paired client requests a photo's image, uploads a photo or starts a sync in safe mode
- **THEN** the API answers 503 with the error code `safe_mode`

#### Scenario: Source added in safe mode
- **WHEN** a paired client adds or changes a source in safe mode
- **THEN** the source is stored and syncs only after the frame has left safe mode

#### Scenario: Restart of the device
- **WHEN** the device restarts three times in a row within a few minutes
- **THEN** the app does not count these as early ends and starts normally

### Requirement: Leaving safe mode
Safe mode SHALL end with a new installation of the app, with "Start normally" in the device menu, when a paired client requests it through the API, or by an automatic probe 1 hour after it began, with the interval doubling up to 24 hours. Leaving restarts the app in normal mode; an early end during the first run afterwards SHALL lead straight back into safe mode.

#### Scenario: Update installed
- **WHEN** a new version is installed while the frame is in safe mode
- **THEN** the app starts in normal mode

#### Scenario: Start normally on the frame
- **WHEN** the user chooses "Start normally" in the device menu
- **THEN** the app restarts and shows the slideshow

#### Scenario: Start normally from a paired device
- **WHEN** a paired client asks the frame to leave safe mode
- **THEN** the API confirms, the app restarts in normal mode, and a frame not in safe mode answers 409 instead

#### Scenario: Crash during the probe
- **WHEN** the app ends early in the first run after the automatic probe
- **THEN** it starts in safe mode again and the next probe follows after twice the interval

### Requirement: Planned process end
The app SHALL record an intended process end before it happens, with a limited validity: installing an update, restarting from the device menu or the API, and starting a system screen, whose record is renewed and withdrawn as "System screen from the device menu" says. A new installation SHALL always count as planned. A planned end SHALL count neither as an early end nor as a crash; a record whose validity has passed SHALL NOT count.

#### Scenario: Restart for an update
- **WHEN** the app ends because an update it started is being installed
- **THEN** the next start logs a planned end and the count of early ends is unchanged

#### Scenario: System screen ends the app
- **WHEN** the device's firmware ends the app right after it opened the device administration screen
- **THEN** this end does not count as an early end

### Requirement: Server port unavailable
If the server cannot bind its port, the app SHALL keep running, the slideshow included. It SHALL retry with a growing interval from 30 seconds to 5 minutes and at once after a change of the network or of local-only mode, also in safe mode. The frame SHALL show that the admin interface is not reachable instead of an address.

#### Scenario: Port taken at start
- **WHEN** another app holds port 8080 when openframe starts
- **THEN** the slideshow runs, the frame says the admin interface is not reachable, and the admin interface is reachable within 5 minutes after the port is free

### Requirement: Restart after an update
After Android has replaced the app with a new version, the server SHALL be reachable again within 40 seconds without any interaction, and settings, pairings, library and sources SHALL be kept. Below Android 10 the slideshow SHALL appear by itself; from Android 10 on it SHALL appear when the Home button is pressed, if openframe is the chosen HOME app. If openframe is not the HOME app after the update, the log SHALL say so, and the frame SHALL act as "HOME choice after an update" says.

#### Scenario: Update confirmed on the frame
- **WHEN** someone confirms the installation dialog for 0.4.0 on a frame with Android 6
- **THEN** the installer closes, the slideshow appears, the server answers within 40 seconds of the installation, `GET /info` reports 0.4.0, and the paired browsers stay paired

#### Scenario: HOME preference lost
- **WHEN** Android drops openframe as the chosen HOME app while it installs an update, on a frame with Android 5 and a second HOME app
- **THEN** the server answers again within 40 seconds, the log has a warning that openframe is not the HOME app, and Android's HOME choice appears over the slideshow once the slideshow is in front with the display on and outside a dark wake

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

### Requirement: HOME choice after an update
After a new installation of openframe has replaced an earlier one, if openframe is a HOME app but Android has no chosen HOME app and would ask, the frame SHALL open Android's HOME choice, the first time the slideshow is in front with the display on, not in a dark wake and outside safe mode. It SHALL open it at most once per installation, whatever the answer, also across restarts. It SHALL NOT open it while openframe or another app is the chosen HOME app, nor after a first installation.

#### Scenario: Choice after the update
- **WHEN** Android dropped the HOME preference during an update on a frame with a second HOME app, and the slideshow appears with the display on
- **THEN** Android's HOME choice appears over the slideshow and offers openframe

#### Scenario: openframe chosen for always
- **WHEN** the person at the frame chooses openframe in it for always
- **THEN** the slideshow is in front, the Home button leads to the slideshow from then on, and the log has no new warning about the HOME app

#### Scenario: Chosen just once or dismissed
- **WHEN** the person at the frame chooses openframe just once, or closes the choice without choosing
- **THEN** the slideshow is in front, the log has a warning that openframe is not the HOME app, and the choice does not open again for this installation, also not after a restart of the device

#### Scenario: Preference kept
- **WHEN** openframe is still the chosen HOME app after the update, or it is the only HOME app
- **THEN** no choice opens

#### Scenario: Another app chosen
- **WHEN** another app is the chosen HOME app after the update
- **THEN** no choice opens and the log has a warning that openframe is not the HOME app

#### Scenario: Dark wake after the update
- **WHEN** the frame restarts after the update during an off period and the display wakes dark for the network
- **THEN** no choice opens, and the display goes off again as without an update

#### Scenario: Display off after the update
- **WHEN** the frame restarts after the update in an off period of the display schedule, with the display off
- **THEN** no choice opens and the display stays off, and the choice opens once the display is on again with the slideshow in front, even after a device restart in between

#### Scenario: Safe mode after the update
- **WHEN** the app runs in safe mode after the update
- **THEN** no choice opens until a run of the same installation in normal mode

#### Scenario: Restart without an update
- **WHEN** the device restarts while Android has no chosen HOME app and the choice was already opened for this installation, or no update came before
- **THEN** no choice opens, and the log has a warning that openframe is not the HOME app

### Requirement: HOME choice as a system screen
The HOME choice the frame opens SHALL count as a system screen from the device menu wherever this spec names one: a planned end recorded before it opens, the display held, no self-healing restart over it, the record withdrawn when the slideshow is back, and learning that the firmware ends the app. When the slideshow is back and openframe is still not the HOME app, the log SHALL say so.

#### Scenario: Firmware ends the app under the choice
- **WHEN** the app process ends without a Java crash while the HOME choice opened by the frame is in front
- **THEN** the next start counts a planned end, not an early end, and does not open the choice again

#### Scenario: Short screen timeout under the choice
- **WHEN** the system's screen timeout is 30 seconds and nobody answers the HOME choice for 5 minutes
- **THEN** the display stays on with the choice in front for the whole 5 minutes

#### Scenario: Firmware ends the app right after the choice opens
- **WHEN** the app process ends without a Java crash within a minute after the frame opened the HOME choice, and the app starts again
- **THEN** the device menu no longer offers Android's settings, until the next installation of the app
