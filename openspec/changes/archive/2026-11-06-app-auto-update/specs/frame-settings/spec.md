# Spec Delta

## MODIFIED Requirements

### Requirement: Frame settings
The settings SHALL also include: language on the frame (`auto` for the system language, `de`, `en`) for the clock's date and all texts on the frame, the frame's name, the budget for cached image sizes (100 to 10,000 MB, default 1,000 MB), and automatic update checks on or off (default on, see `app-update`). Local-only mode is not a setting of the API, see `admin-access`.

#### Scenario: Read frame settings
- **WHEN** a paired client fetches the settings
- **THEN** the response contains language, name, cache budget and automatic update checks with their current values

#### Scenario: Update from a version without the switch
- **WHEN** a frame with stored settings from before automatic update checks existed is updated
- **THEN** automatic update checks are on and all other settings keep their values
