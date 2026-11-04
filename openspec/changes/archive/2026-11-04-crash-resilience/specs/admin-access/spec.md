## MODIFIED Requirements

### Requirement: Manage tokens
A paired client SHALL be able to see the list of paired devices and revoke individual pairings. A revoked token SHALL become invalid immediately. Tokens SHALL be stored on the frame only as a hash. A revocation SHALL stay effective across a restart even when the storage is full; if the list of paired devices cannot be written at all, no pairing SHALL be valid after the restart.

#### Scenario: Revoke pairing
- **WHEN** the user revokes the pairing of their old laptop
- **THEN** the old laptop receives 401 on its next request

#### Scenario: Revoke with full storage
- **WHEN** the user revokes a pairing while the storage is full, and the frame restarts afterwards
- **THEN** the revoked device still receives 401, and either the other devices keep working or every device has to pair again

### Requirement: Reset all pairings on the frame
On the frame itself there SHALL be a way to revoke all pairings without needing a paired client. The action SHALL be confirmed. A write error SHALL NOT end the app: the frame SHALL show a new pairing code in any case, and the reset SHALL hold across a restart.

#### Scenario: Access lost
- **WHEN** the user chooses "Revoke all pairings" on the frame and confirms
- **THEN** all tokens, all issued image signatures and all open invitations are invalid, and the frame shows a new pairing code and QR code

#### Scenario: Reset with full storage
- **WHEN** the user unpairs all devices while the storage is full, and the frame restarts afterwards
- **THEN** the app did not crash, and no earlier token or image signature is valid after the restart

### Requirement: Local-only mode
While local-only mode is on, the server SHALL NOT be reachable over the network, only from the device itself. The mode SHALL be stored durably and SHALL be switchable on or off ONLY on the frame itself, not through the API. This way nobody can lock themselves out from the browser. If the stored settings exist but cannot be read, local-only mode SHALL be on, even if the other settings come from the copy of the last start.

#### Scenario: Local-only on
- **WHEN** local-only mode is switched on
- **THEN** the port is not reachable from the LAN and the slideshow keeps running

#### Scenario: Turn off on the frame
- **WHEN** the user turns local-only mode off on the frame
- **THEN** the admin interface is reachable from the LAN again

#### Scenario: Via the API
- **WHEN** a paired client tries to set local-only mode through the settings
- **THEN** the API answers with 400 and the mode stays unchanged

#### Scenario: Damaged settings file
- **WHEN** the frame starts and its settings file exists but cannot be read
- **THEN** local-only mode is on, the frame shows this, and it stays on until it is turned off on the frame

## ADDED Requirements

### Requirement: Device names without control characters
When a device pairs or is renamed, control characters in its name SHALL become a space before the name is trimmed and its length is handled as today: pairing shortens it to 64 characters and uses the browser's name for an empty one, renaming accepts 1 to 64 characters.

#### Scenario: Name with a carriage return
- **WHEN** a device pairs with the name "Tablet\rX"
- **THEN** the list of paired devices shows the name "Tablet X"

#### Scenario: Pairing with only control characters
- **WHEN** a device pairs with a name that consists only of control characters
- **THEN** it is paired under the name derived from its browser

#### Scenario: Rename to only control characters
- **WHEN** a paired device is renamed to a name that consists only of control characters
- **THEN** the API answers with 400 and the name is unchanged
