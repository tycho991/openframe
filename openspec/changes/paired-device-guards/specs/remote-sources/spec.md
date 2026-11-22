## ADDED Requirements

### Requirement: Widening the folder of an account
For a source whose kind needs a choice of folder (an account), a change SHALL count as widening when it sets the first folder, switches subfolders on, sets a folder that is neither the old one nor below it, or sets a folder below the old one while subfolders were off. Folders SHALL be compared by path segment and with exact case. Every other change, and every change of a share-link source, SHALL NOT count as widening.

#### Scenario: Whole account
- **WHEN** a client changes the folder of an account from `/Photos` to `/`
- **THEN** the change counts as widening

#### Scenario: Deeper folder with subfolders on
- **WHEN** the folder of an account is `/Photos` with subfolders on and a client sets `/Photos/2024`
- **THEN** the change does not count as widening

#### Scenario: Deeper folder with subfolders off
- **WHEN** the folder of an account is `/Photos` with subfolders off and a client sets `/Photos/Private`
- **THEN** the change counts as widening

#### Scenario: Other case
- **WHEN** the folder of an account is `/Photos` and a client sets `/photos`
- **THEN** the change counts as widening

#### Scenario: Share link
- **WHEN** a client changes the folder of a share-link source to `/`
- **THEN** the change does not count as widening

### Requirement: Proof for widening an account folder
A widening change SHALL succeed only if the device that sends it completed a login for exactly this account within the last 30 minutes: one that added the source, renewed its credentials or confirmed it. Otherwise the frame SHALL refuse the whole change with 403 `account_login_required` naming the widening fields, after checking the fields. The proof SHALL live in memory only.

#### Scenario: Visitor widens to the whole account
- **WHEN** a device that never logged in to the account sets the folder of an account source to `/`
- **THEN** the API answers 403 with `account_login_required`, the source keeps its folder, and no sync of `/` starts

#### Scenario: After a confirmation login
- **WHEN** a device confirms the account by a login and sets the folder to `/` 10 minutes later
- **THEN** the change is applied and the next sync reads the whole account

#### Scenario: Proof expired
- **WHEN** a device completed a login for the account 31 minutes ago and now widens its folder
- **THEN** the API answers 403 with `account_login_required`

#### Scenario: Proof of another device
- **WHEN** a laptop confirmed the account and a phone widens the folder within the 30 minutes
- **THEN** the API answers 403 with `account_login_required` to the phone

#### Scenario: First folder after connecting
- **WHEN** a device connects a new account by the login flow and chooses its first folder
- **THEN** the folder is stored without a further login

#### Scenario: Invalid value and widening together
- **WHEN** a client sets an invalid interval and a wider folder in one change without a proof
- **THEN** the API answers 400 naming the interval, and nothing changes

#### Scenario: Narrowing and other settings
- **WHEN** a device without a proof narrows the folder of an account, renames it or changes its interval
- **THEN** the change is applied
