# Spec Delta

## ADDED Requirements

### Requirement: Restart after an update
After Android has replaced the app with a new version, the server SHALL be reachable again within 40 seconds without any interaction, and settings, pairings, library and sources SHALL be kept. Below Android 10 the slideshow SHALL appear by itself; from Android 10 on it SHALL appear when the Home button is pressed, if openframe is the chosen HOME app. If openframe is not the HOME app after the update, the log SHALL say so.

#### Scenario: Update confirmed on the frame
- **WHEN** someone confirms the installation dialog for 0.4.0 on a frame with Android 6
- **THEN** the installer closes, the slideshow appears, the server answers within 40 seconds of the installation, `GET /info` reports 0.4.0, and the paired browsers stay paired

#### Scenario: HOME preference lost
- **WHEN** another app is the HOME app after the update
- **THEN** the server answers again within 40 seconds, and the log has a warning that openframe is not the HOME app
