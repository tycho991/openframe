## MODIFIED Requirements

### Requirement: Secrecy of the credentials
The app password, the password of a share and the token of a share link SHALL be stored only in the frame's private storage. The API SHALL NOT return them, and they SHALL NOT appear in the log, in error messages or in the list of settings.

#### Scenario: Read the configuration
- **WHEN** a paired client reads a source
- **THEN** the response names server, user or name, and options, and contains no password and no share token

#### Scenario: Failed sync
- **WHEN** a sync fails because of wrong credentials or a wrong share password
- **THEN** the error in the status and in the log contains no password, no share token and no authorization header

#### Scenario: Unreadable list of sources
- **WHEN** the stored list of sources is damaged so that it cannot be read at start
- **THEN** the log entry names the file and the kind and position of the error, but quotes nothing from the file, so no password and no share token
