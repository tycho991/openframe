## MODIFIED Requirements

### Requirement: Restart after an update
After Android has replaced the app with a new version, the server SHALL be reachable again within 40 seconds without any interaction, and settings, pairings, library and sources SHALL be kept. Below Android 10 the slideshow SHALL appear by itself; from Android 10 on it SHALL appear when the Home button is pressed, if openframe is the chosen HOME app. If openframe is not the HOME app after the update, the log SHALL say so, and the frame SHALL act as "HOME choice after an update" says.

#### Scenario: Update confirmed on the frame
- **WHEN** someone confirms the installation dialog for 0.4.0 on a frame with Android 6
- **THEN** the installer closes, the slideshow appears, the server answers within 40 seconds of the installation, `GET /info` reports 0.4.0, and the paired browsers stay paired

#### Scenario: HOME preference lost
- **WHEN** Android drops openframe as the chosen HOME app while it installs an update, on a frame with Android 5 and a second HOME app
- **THEN** the server answers again within 40 seconds, the log has a warning that openframe is not the HOME app, and Android's HOME choice appears over the slideshow once the slideshow is in front with the display on and outside a dark wake

## ADDED Requirements

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
