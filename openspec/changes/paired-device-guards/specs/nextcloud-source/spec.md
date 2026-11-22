## MODIFIED Requirements

### Requirement: Connect with the login flow
The frame SHALL connect to a Nextcloud server through Nextcloud's Login Flow v2: the user gives the server address, approves the access in a browser tab, and the frame receives an app password. The frame SHALL accept only addresses with `http` or `https`. The user SHALL never have to type a password into the frame or its admin interface. Each approved login, except a confirmation login, SHALL add a source or, for a server and user that already are a source, replace its credentials.

#### Scenario: Approval in the browser
- **WHEN** the user gives the address of a Nextcloud server and approves the access in the page that opens
- **THEN** the frame reports that it is connected, shows the user name that Nextcloud returned and lists the account as a source

#### Scenario: Approval not given
- **WHEN** the user does not approve the access within 20 minutes
- **THEN** the frame reports that the login expired and is not connected

#### Scenario: Server not reachable
- **WHEN** the address is wrong or the server does not answer
- **THEN** the frame reports the error and nothing is stored

#### Scenario: Same account again
- **WHEN** the user logs in again to a server and user that already is a source
- **THEN** the source keeps its photos and settings and gets the new credentials

## ADDED Requirements

### Requirement: Confirm an account by login
A paired device SHALL be able to start a login that confirms one account source, on that source's server. If the approved account is the server and user of that very source, the login SHALL succeed for it, and the frame SHALL revoke the new app password; the source SHALL keep its credentials and status. Any other account, also one that is another source, SHALL fail the login with `other_account`, and its app password SHALL be revoked; no source is added or changed.

#### Scenario: Same account confirmed
- **WHEN** a device starts a confirmation login for an account source and the user approves it as the same Nextcloud user
- **THEN** the login reports success for that source, the new app password is revoked on the server, and the source still syncs with its old one

#### Scenario: Another account approves
- **WHEN** a device starts a confirmation login for an account source and someone approves it as another Nextcloud user
- **THEN** the login fails with `other_account`, the new app password is revoked, and the frame has no new source

#### Scenario: Account of another source
- **WHEN** the frame has two account sources, a device starts a confirmation login for the first, and the user approves it as the account of the second
- **THEN** the login fails with `other_account`, the new app password is revoked, and neither source changes its credentials

#### Scenario: Share-link source
- **WHEN** a device starts a confirmation login for a share-link source
- **THEN** the API answers 400 and no login starts

### Requirement: Login flows per device
Each paired device SHALL have at most one login flow at a time; starting a new one SHALL replace only that device's own. Only the device that started a flow SHALL learn its state or complete it; for any other device the flow SHALL count as unknown. The flow of a device whose pairing is revoked SHALL end.

#### Scenario: Two devices log in at once
- **WHEN** a laptop starts a login and a phone starts another one before the laptop's is approved
- **THEN** both logins complete for the device that started them

#### Scenario: Poll from another device
- **WHEN** a device polls the flow ID of a login that another device started
- **THEN** the API answers that the flow is expired, and the starting device can still complete it
