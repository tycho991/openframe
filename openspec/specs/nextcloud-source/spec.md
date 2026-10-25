# nextcloud-source Specification

## Purpose
Shows the photos of a folder on a Nextcloud server on the frame: connecting without typing a password, keeping the frame in step with the folder in the background, and what happens when the server cannot be reached or the storage is short.

## Requirements

### Requirement: Connect with the login flow
The frame SHALL connect to a Nextcloud server through Nextcloud's Login Flow v2: the user gives the server address, approves the access in a browser tab, and the frame receives an app password. The frame SHALL accept only addresses with `http` or `https`. The user SHALL never have to type a password into the frame or its admin interface. Each approved login SHALL add a source; a login for a server and user that already is a source SHALL replace that source's credentials.

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

### Requirement: Secrecy of the credentials
The app password, the password of a share and the token of a share link SHALL be stored only in the frame's private storage. The API SHALL NOT return them, and they SHALL NOT appear in the log, in error messages or in the list of settings.

#### Scenario: Read the configuration
- **WHEN** a paired client reads a source
- **THEN** the response names server, user or name, and options, and contains no password and no share token

#### Scenario: Failed sync
- **WHEN** a sync fails because of wrong credentials or a wrong share password
- **THEN** the error in the status and in the log contains no password, no share token and no authorization header

### Requirement: Connect with a share link
The frame SHALL accept the link of a Nextcloud folder share as a source without an account: an `http` or `https` address that ends in `/s/<token>`, also behind `/index.php` or in a sub-path. It SHALL read the share once before storing it. A link that is already a source SHALL be refused with 409 and the code `source_exists`.

#### Scenario: Valid link
- **WHEN** the user adds the link of a folder share
- **THEN** the share is stored as a source and its photos join the slideshow after the first sync

#### Scenario: Not a share
- **WHEN** the address is no share or the share does not exist
- **THEN** the API answers 400 naming the link field, and nothing is stored

#### Scenario: Share of a single file
- **WHEN** the link is the share of one file
- **THEN** it is refused with a readable error and nothing is stored

#### Scenario: Same link twice
- **WHEN** the user adds a link that already is a source
- **THEN** the API answers 409 with `source_exists`

### Requirement: Password of a share
A share protected by a password SHALL be added only with that password. A missing or wrong password SHALL be refused naming the password field and storing nothing; a password that no longer fits at sync time SHALL show as an error in the status of the source without removing any photo. The password is only given when adding the share.

#### Scenario: Password missing
- **WHEN** the user adds a protected share without a password
- **THEN** the API answers 400 naming the password field as required

#### Scenario: Wrong password
- **WHEN** the user adds a protected share with a wrong password
- **THEN** the API answers 400 naming the password field as wrong, and nothing is stored

#### Scenario: Password changed later
- **WHEN** the owner changes the password of a share that the frame uses
- **THEN** the next sync fails with a readable error and the photos stay

### Requirement: Trusted connections on old devices
Connections to `https` servers SHALL work on Android 5 and 6, including servers whose certificate is issued by Let's Encrypt. The frame SHALL accept the certificates that the system accepts and, in addition, those issued under the root certificates delivered with the app.

#### Scenario: Server with a Let's Encrypt certificate
- **WHEN** the frame on Android 6 connects to a server with a certificate from Let's Encrypt
- **THEN** the connection is established

#### Scenario: Unknown certificate
- **WHEN** the server's certificate is issued by an authority neither the system nor the app knows
- **THEN** the connection is refused and the status shows the error
