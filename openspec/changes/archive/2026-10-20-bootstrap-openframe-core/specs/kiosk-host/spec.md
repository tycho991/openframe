# Spec Delta

## Purpose

Runs openframe on an Android device as an unattended picture frame. The app starts by itself, stays in the foreground and keeps the display and the server running. It runs on old devices from Android 5.0 without root.

## ADDED Requirements

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
While the slideshow is running, the device SHALL NOT go into standby or turn the display off.

#### Scenario: Hours without touch
- **WHEN** nobody touches the device for 12 hours
- **THEN** the display is still on and the slideshow is running

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
If the display or the server crashes, the app SHALL restart itself within 30 seconds. The cause SHALL be logged.

#### Scenario: Display crash
- **WHEN** the display is terminated because of an error
- **THEN** the slideshow is running again after 30 seconds at the latest

### Requirement: Network is optional
The slideshow SHALL keep running without a network. If Wi-Fi drops and comes back, the server SHALL be reachable again without a restart.

#### Scenario: Wi-Fi outage
- **WHEN** Wi-Fi is down for an hour
- **THEN** the slideshow keeps running and once Wi-Fi returns the admin interface is reachable again

### Requirement: Diagnostics
The app SHALL keep a rotating log of limited size on the device. A paired client SHALL be able to fetch it through the API.

#### Scenario: Fetch log
- **WHEN** a paired client fetches the log
- **THEN** it receives the latest entries with timestamp and level

### Requirement: Side-by-side operation with pictureframe
The app SHALL have its own package ID so that it can be installed next to `com.pictureframe.kiosk` without touching its data.

#### Scenario: Both installed
- **WHEN** openframe is installed on a device where pictureframe is running
- **THEN** pictureframe and its data remain unchanged
