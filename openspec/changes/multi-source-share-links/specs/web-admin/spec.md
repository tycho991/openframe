# Delta for web-admin

## RENAMED Requirements

- FROM: `### Requirement: Nextcloud in the interface`
- TO: `### Requirement: Sources in the interface`

## MODIFIED Requirements

### Requirement: Sources in the interface
The interface SHALL have a section for sources: list them with name, kind and status, add one by the Nextcloud login flow or by a share link (with an optional password), and for each edit name, folder, subfolders, interval, removal of vanished photos and the storage budget, start a sync, show the status of the last sync with its error in readable form, and remove it after a confirmation. Validation errors SHALL appear at the affected field.

#### Scenario: Add a share link
- **WHEN** the user pastes the link of a share and chooses "Add"
- **THEN** the source appears in the list and shows that its first sync is running

#### Scenario: Share needs a password
- **WHEN** the share is protected and no password was given
- **THEN** the interface asks for the password at the password field

#### Scenario: Connect
- **WHEN** the user gives a server address and chooses "Connect"
- **THEN** the interface opens the approval page of Nextcloud in a new tab and shows that it waits for the approval

#### Scenario: Connected
- **WHEN** the approval has been given
- **THEN** the interface lists the account with its user name and shows its settings

#### Scenario: Several sources
- **WHEN** two sources exist
- **THEN** the list shows both with their own status, and syncing or removing one leaves the other as it is

#### Scenario: Sync now
- **WHEN** the user chooses "Sync now" for a source
- **THEN** the interface shows that a sync is running and then its result with the three numbers

#### Scenario: Error of the last sync
- **WHEN** the last sync of a source failed
- **THEN** the interface shows the time and a readable error, and no password and no share link token

#### Scenario: Disconnect
- **WHEN** the user removes a source and confirms
- **THEN** the source leaves the list and its photos disappear from the overview
