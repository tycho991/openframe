# Spec Delta

## ADDED Requirements

### Requirement: Software update in the interface
The settings SHALL have a section "Software" that shows the installed version, the latest version found with its release notes, the state of check, download and installation, the last check and the last error. It SHALL offer "Check now" and, while an update is ready, "Install on the frame". The switch for automatic checks SHALL name GitHub. The section SHALL follow changes as in "Current state".

#### Scenario: Update ready
- **WHEN** 0.4.0 is ready on a frame running 0.3.0 and the user opens the settings
- **THEN** the section shows 0.3.0, 0.4.0 with its notes and the button "Install on the frame"

#### Scenario: Check now
- **WHEN** the user chooses "Check now"
- **THEN** the section shows that the check runs and then its result, without reloading

#### Scenario: Own build
- **WHEN** the frame runs a build that is not signed with the official certificate
- **THEN** the section says that updates exist only for official builds and offers no check

### Requirement: Release notes as plain text
The interface SHALL show release notes as plain text, never as HTML or rendered Markdown, so that no markup or link in them takes effect.

#### Scenario: Markup in the notes
- **WHEN** the notes of a release contain `<img src=x onerror=alert(1)>` and a `javascript:` link
- **THEN** both appear as text, and nothing runs

### Requirement: Installing explained in the interface
"Install on the frame" SHALL say that the confirmation happens on the frame. While Android does not allow openframe to install apps, the button SHALL be disabled, with a notice that installing apps from unknown sources has to be allowed on the frame.

#### Scenario: Request sent
- **WHEN** the user chooses "Install on the frame"
- **THEN** the interface says that the dialog is now open on the frame and has to be confirmed there

#### Scenario: Installing not allowed
- **WHEN** an update is ready on a frame that does not allow installing apps
- **THEN** the button is disabled and the notice names the setting on the frame

### Requirement: Reload after a frame update
When the installed version the frame reports differs from the version the interface was built with, the interface SHALL reload itself if no input is unsaved, and otherwise SHALL show a notice to reload. While an installation runs, a frame that does not answer SHALL count as restarting, not as an error.

#### Scenario: After the installation
- **WHEN** the user chose "Install on the frame", someone confirmed the dialog, and the server answers again
- **THEN** within 30 seconds the interface reloads and shows the new installed version

#### Scenario: Unsaved input
- **WHEN** the frame updated itself while the user is typing a comment
- **THEN** the interface keeps the input and shows a notice to reload

### Requirement: Unknown values from the frame
A phase, error code or refusal code from the frame that the interface does not know SHALL be shown as its raw value, without breaking the view.

#### Scenario: Newer frame, older interface
- **WHEN** the frame reports an error code the interface has no text for
- **THEN** the section shows the code itself and stays usable
