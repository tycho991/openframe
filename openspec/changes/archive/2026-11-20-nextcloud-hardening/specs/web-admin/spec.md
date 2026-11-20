## ADDED Requirements

### Requirement: Source errors in the interface language
The interface SHALL show the error of a source's last sync and the error of a failed account login by their error
code, in the language of the interface, with the frame's English text at most as a detail. A result without an error
code SHALL show its text as before, and an unknown code SHALL be shown as it is.

#### Scenario: Sync failed in German
- **WHEN** the interface is in German and the last sync of a source failed because the server was not reachable
- **THEN** the source card shows the German text for that error and the time of the sync

#### Scenario: Login on a full frame
- **WHEN** an account login fails because the frame already has the most sources it allows
- **THEN** the interface shows the text for that code in its language

#### Scenario: Insecure login answer
- **WHEN** the frame refuses a login with `nextcloud_insecure_login`
- **THEN** the interface says in its language that the server answered with an insecure address and points to the
  server's protocol settings

### Requirement: Safe start of an account login
While an account login is being started, the interface SHALL lock the "Connect" button and the address field, so that
one click and a double click both start one login. It SHALL open the login page only when its address uses `http` or
`https`.

#### Scenario: Double click on Connect
- **WHEN** the user double-clicks "Connect"
- **THEN** the frame receives one request to start a login and one approval page opens

#### Scenario: Login page with another scheme
- **WHEN** the frame reports a login page whose address uses neither `http` nor `https`
- **THEN** the interface opens nothing and shows an error

### Requirement: Plain http is pointed out
The interface SHALL warn that a connection is not encrypted both at the address field while an `http` address is
typed and on the card of each saved source whose server address uses `http`.

#### Scenario: Saved http source
- **WHEN** a source with an `http` server address is listed
- **THEN** its card shows the warning that the connection is not encrypted
