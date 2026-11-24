## MODIFIED Requirements

### Requirement: Configuration
Each source SHALL have these settings: name, enabled, folder, subfolders included (default on), remove vanished photos (default on), interval in minutes (5 to 1,440, default 20) and a budget for originals (100 to 50,000 MB, default 2,000 MB). Invalid values SHALL be rejected naming the field; changes SHALL be partial and take effect without a restart. A value of the wrong JSON type, such as the string "true" for a switch, SHALL count as invalid.

#### Scenario: Interval too short
- **WHEN** a client sets the interval of a source to 2 minutes
- **THEN** the API answers 400 and names the interval field

#### Scenario: Change of the folder
- **WHEN** the folder of a source is changed
- **THEN** the next sync reads the new folder and removes the photos of the old one if removal is on

#### Scenario: Switch sent as text
- **WHEN** a client sets `enabled` of a source to the string "true"
- **THEN** the API answers 400 `validation_failed` naming the field `enabled`, and the source is unchanged

#### Scenario: Valid and invalid field together
- **WHEN** a client changes the name of a source and sends `includeSubfolders` as the string "false" in the same request
- **THEN** the API answers 400 naming `includeSubfolders`, and neither the name nor the switch changes

## ADDED Requirements

### Requirement: Source names on one line
When a source is added or renamed, control characters in its name, line breaks included, SHALL become a space before the name is trimmed and its length is checked, as for the frame name and device names. A name that is empty afterwards SHALL be refused naming the field, as an empty name is today.

#### Scenario: Name with a line break
- **WHEN** a client renames a source to "Summer" followed by a line break and "2026"
- **THEN** the source is named "Summer 2026"

#### Scenario: Only control characters
- **WHEN** a client renames a source to a name that consists only of control characters
- **THEN** the API answers 400 naming the field `name`, and the source keeps its name
