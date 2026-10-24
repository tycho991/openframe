# Spec Delta

## ADDED Requirements

### Requirement: Nextcloud in the interface
The interface SHALL have a section for the Nextcloud source: connect through the login flow, show server and user, edit folder, subfolders, interval, removal of vanished photos and the storage budget, start a sync, show the status of the last sync with its error in readable form, and disconnect after a confirmation. Validation errors SHALL appear at the affected field.

#### Scenario: Connect
- **WHEN** the user gives a server address and chooses "Connect"
- **THEN** the interface opens the approval page of Nextcloud in a new tab and shows that it waits for the approval

#### Scenario: Connected
- **WHEN** the approval has been given
- **THEN** the interface shows the user name and the settings of the source

#### Scenario: Sync now
- **WHEN** the user chooses "Sync now"
- **THEN** the interface shows that a sync is running and then its result with the three numbers

#### Scenario: Error of the last sync
- **WHEN** the last sync failed
- **THEN** the interface shows the time and a readable error, and no password

#### Scenario: Disconnect
- **WHEN** the user disconnects and confirms
- **THEN** the interface shows the source as not connected and the Nextcloud photos disappear from the overview

### Requirement: Photos from Nextcloud in the overview
The photo overview SHALL show photos from Nextcloud like local ones, with albums and hiding. For them the interface SHALL NOT offer deleting, but point to hiding.

#### Scenario: Select Nextcloud photos
- **WHEN** the user selects a Nextcloud photo
- **THEN** hiding is offered and deleting is not
