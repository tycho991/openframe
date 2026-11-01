## MODIFIED Requirements

### Requirement: Durable, atomic storage
Stored settings SHALL survive restarts and app updates. A power loss while saving SHALL NOT lead to corrupted or empty settings. A stored file that cannot be read SHALL NOT be discarded: a read error SHALL be retried, the copy read at the last start SHALL serve as fallback, and before the file is first overwritten it SHALL be kept as a broken copy; the last three broken copies are kept.

#### Scenario: Restart after a change
- **WHEN** a setting is changed and the device is restarted afterwards
- **THEN** the changed value applies after startup

#### Scenario: Power loss while writing
- **WHEN** the write is interrupted
- **THEN** at the next start either the old or the new state applies, completely

#### Scenario: Short read error
- **WHEN** the settings file cannot be read once at start but can be read again later
- **THEN** it is not renamed or replaced, and the next start applies its values

#### Scenario: Damaged settings file
- **WHEN** the settings file is damaged and a copy from the last start exists
- **THEN** the frame starts with the values of that copy, and the damaged file is kept as a broken copy when the settings are next saved

### Requirement: Frame identity
Every frame SHALL receive a random, durable ID on first start that never changes. It SHALL have a changeable name, defaulting to the device model. ID, name, app version, screen resolution and Android version SHALL be retrievable without pairing. Control characters in the name SHALL become a space, and the length check SHALL apply after this.

#### Scenario: Fetch info
- **WHEN** a client without a token fetches the frame's info
- **THEN** it receives ID, name, app version, screen resolution and Android version, but no settings

#### Scenario: Change name
- **WHEN** the name is changed to "Living room"
- **THEN** the info shows the new name, and the ID is unchanged

#### Scenario: Name with a line break
- **WHEN** the name is changed to "Living\nroom"
- **THEN** the info shows the name "Living room"
