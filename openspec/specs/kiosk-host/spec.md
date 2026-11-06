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
- **THEN** the display goes off, and the device does not turn it on again by itself before the schedule's next on time

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
If the display or the server crashes, the app SHALL restart itself within 30 seconds, unless it starts in safe mode. From the third early end in a row, the display restart after a Java crash or an unexplained end SHALL come only after a growing delay (30 s, 2 min, then 10 min); the server SHALL come back independently of it. At each start the app SHALL log how the previous run ended: Java crash with its cause, unexplained end, device restart, new installation or planned end.

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
The app SHALL record an intended process end before it happens, with a limited validity: installing an update, restarting from the device menu or the API, and starting a system screen. A new installation SHALL always count as planned. A planned end SHALL count neither as an early end nor as a crash; a record whose validity has passed SHALL NOT count.

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
After Android has replaced the app with a new version, the server SHALL be reachable again within 40 seconds without any interaction, and settings, pairings, library and sources SHALL be kept. Below Android 10 the slideshow SHALL appear by itself; from Android 10 on it SHALL appear when the Home button is pressed, if openframe is the chosen HOME app. If openframe is not the HOME app after the update, the log SHALL say so.

#### Scenario: Update confirmed on the frame
- **WHEN** someone confirms the installation dialog for 0.4.0 on a frame with Android 6
- **THEN** the installer closes, the slideshow appears, the server answers within 40 seconds of the installation, `GET /info` reports 0.4.0, and the paired browsers stay paired

#### Scenario: HOME preference lost
- **WHEN** another app is the HOME app after the update
- **THEN** the server answers again within 40 seconds, and the log has a warning that openframe is not the HOME app
