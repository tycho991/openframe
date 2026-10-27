# frame-settings Specification

## Purpose
Holds the settings and the identity of a frame. They are validated, survive restarts and updates, and can be read and changed through the API.

## Requirements

### Requirement: Settings model with defaults
The frame SHALL have a documented default value for every setting. If a setting is missing from the stored data, for example after an update that adds settings, the default value SHALL apply without losing existing values.

#### Scenario: First start
- **WHEN** the app starts without stored settings
- **THEN** all default values apply and the slideshow runs

#### Scenario: Update with a new setting
- **WHEN** a new app version introduces an additional setting
- **THEN** the frame keeps all previous values and the new setting has its default value

### Requirement: Scope of the settings
The display settings SHALL include at least: interval (5 s to 24 h, default 30 s), order, transition and transition duration (0 to 5000 ms), fill mode, orientation (`landscape` default, `portrait`) and clock (on/off, position, size 50 to 300 %, 12/24 h).

#### Scenario: Read display settings
- **WHEN** a paired client fetches the settings
- **THEN** the response contains all the display settings named above with their current values

### Requirement: Frame settings
The settings SHALL also include: language on the frame (`auto` for the system language, `de`, `en`) for the clock's date and all texts on the frame, the frame's name, and the budget for cached image sizes (100 to 10,000 MB, default 1,000 MB). Local-only mode is not a setting of the API, see `admin-access`.

#### Scenario: Read frame settings
- **WHEN** a paired client fetches the settings
- **THEN** the response contains language, name and cache budget with their current values

### Requirement: Validation
Changes to the settings SHALL be validated completely before they are stored. Invalid changes SHALL be rejected with 400 and an error entry per field. No setting SHALL be applied partially.

#### Scenario: Invalid interval
- **WHEN** a client sets the interval to 1 second and the clock to off
- **THEN** the API answers with 400, names the interval field, and the clock also stays unchanged

#### Scenario: Unknown field
- **WHEN** a client sends an unknown field
- **THEN** the API answers with 400 and names the field

### Requirement: Partial changes
Clients SHALL be able to change individual settings without sending all settings. Settings that are not mentioned SHALL remain unchanged.

#### Scenario: Change only the interval
- **WHEN** a client sets only the interval to 60 s
- **THEN** the interval is 60 s and all other settings are unchanged

### Requirement: Durable, atomic storage
Stored settings SHALL survive restarts and app updates. A power loss while saving SHALL NOT lead to corrupted or empty settings.

#### Scenario: Restart after a change
- **WHEN** a setting is changed and the device is restarted afterwards
- **THEN** the changed value applies after startup

#### Scenario: Power loss while writing
- **WHEN** the write is interrupted
- **THEN** at the next start either the old or the new state applies, completely

### Requirement: Changes take effect immediately
Stored settings SHALL take effect without a restart. The API SHALL provide a settings version that clients can use to detect changes.

#### Scenario: Transition changed
- **WHEN** the transition is changed from `fade` to `slide`
- **THEN** the slideshow uses `slide` at the next change

### Requirement: Frame identity
Every frame SHALL receive a random, durable ID on first start that never changes. It SHALL have a changeable name, defaulting to the device model. ID, name, app version, screen resolution and Android version SHALL be retrievable without pairing.

#### Scenario: Fetch info
- **WHEN** a client without a token fetches the frame's info
- **THEN** it receives ID, name, app version, screen resolution and Android version, but no settings

#### Scenario: Change name
- **WHEN** the name is changed to "Living room"
- **THEN** the info shows the new name, and the ID is unchanged

### Requirement: Default fill mode
The default value of the fill mode SHALL be `auto`. A frame that already has stored settings when it is updated SHALL keep its stored fill mode.

#### Scenario: New frame
- **WHEN** the app starts without stored settings
- **THEN** the fill mode is `auto`

#### Scenario: Update of a frame in fill
- **WHEN** a frame whose stored fill mode is `fill` is updated to a version with `auto`
- **THEN** its fill mode is still `fill`

### Requirement: Caption settings
The display settings SHALL include the caption: album title on or off (default off), place on or off (default off) and the corner (default bottom left). The corner SHALL take the same four values as the clock's position. The comment itself SHALL have no switch: a photo's comment is always shown. Caption and clock SHALL be allowed to share a corner.

#### Scenario: Defaults
- **WHEN** a frame starts without stored settings, or with settings from before the caption existed
- **THEN** album title and place are off, the caption's corner is bottom left, and all other settings keep their values

#### Scenario: Same corner as the clock
- **WHEN** a client sets the caption's corner to the corner of the clock
- **THEN** the change is stored

#### Scenario: Invalid corner
- **WHEN** a client sets the caption's corner to `middle`
- **THEN** the API answers with 400 and names the field `caption.position`
