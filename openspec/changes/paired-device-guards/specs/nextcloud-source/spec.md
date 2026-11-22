## ADDED Requirements

### Requirement: Confirm an account by login
A paired device SHALL be able to start a login that confirms an existing account source, on that source's server; unlike other logins, it SHALL neither add a source nor replace credentials. If the approved account is the same server and user, the login SHALL succeed for that source, and the frame SHALL revoke the new app password, and the source SHALL keep its status. Another account SHALL fail the login with `other_account` and its app password SHALL be revoked; no source is added.

#### Scenario: Same account confirmed
- **WHEN** a device starts a confirmation login for an account source and the user approves it as the same Nextcloud user
- **THEN** the login reports success for that source, the new app password is revoked on the server, and the source still syncs with its old one

#### Scenario: Another account approves
- **WHEN** a device starts a confirmation login for an account source and someone approves it as another Nextcloud user
- **THEN** the login fails with `other_account`, the new app password is revoked, and the frame has no new source

#### Scenario: Share-link source
- **WHEN** a device starts a confirmation login for a share-link source
- **THEN** the API answers 400 and no login starts

### Requirement: Login flows per device
Each paired device SHALL have at most one login flow at a time; starting a new one SHALL replace only that device's own. Only the device that started a flow SHALL learn its state or complete it; for any other device the flow SHALL count as unknown.

#### Scenario: Two devices log in at once
- **WHEN** a laptop starts a login and a phone starts another one before the laptop's is approved
- **THEN** both logins complete for the device that started them

#### Scenario: Poll from another device
- **WHEN** a device polls the flow ID of a login that another device started
- **THEN** the API answers that the flow is expired, and the starting device can still complete it
