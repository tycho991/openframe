## ADDED Requirements

### Requirement: Bounded answers of a server
The frame SHALL read the answers of a Nextcloud server only up to fixed limits: the size of a listing after
decompression, the text of one element, the number of entries in one answer, the size of a login answer, and the time
to read one answer. It SHALL refuse a listing that declares a document type. An answer beyond a limit SHALL fail the sync or the login with a
readable error that names the limit; the photos SHALL stay and the frame SHALL keep running.

#### Scenario: Listing with a DOCTYPE
- **WHEN** a server answers a listing with a document that declares a `DOCTYPE`, for example one with nested entities
- **THEN** the sync fails with an error that names the cause, the photos of the source stay, and the app neither
  restarts nor runs out of memory

#### Scenario: Listing too large
- **WHEN** a server sends a listing larger than the limit, or one element with text beyond its limit
- **THEN** the sync fails with an error that names the limit, and no photo of the source is removed

#### Scenario: Large but legitimate folder
- **WHEN** a folder holds 20,000 photos
- **THEN** its listing is read completely and the sync succeeds

#### Scenario: Login answer too large
- **WHEN** the server answers the start or the poll of a login with more than the limit
- **THEN** the login fails with a readable error and nothing is stored

#### Scenario: Listing that never ends
- **WHEN** a server sends a listing so slowly that reading it would take longer than the time limit
- **THEN** the sync fails with an error that says so, and other sources sync on their schedule

### Requirement: Listings stay inside the folder
A listing SHALL accept only entries that are direct children of the folder listed. An entry named `.` or `..`, with an
empty name, or outside the folder SHALL fail the listing; it SHALL NOT be dropped silently. The depth of subfolders and
the number of folders and files read in one sync SHALL be limited. A failed listing SHALL NOT remove any photo.

#### Scenario: Entry outside the folder
- **WHEN** a server lists, inside the folder of a source, an entry whose path lies outside that folder or is named `..`
- **THEN** the sync fails with an error that names the cause, no file outside the folder is read, and no photo is
  removed

#### Scenario: Too deep or too many
- **WHEN** a folder has more levels of subfolders, or more folders or files, than the limits allow
- **THEN** the sync fails with an error that names the limit, and no photo is removed

### Requirement: No downgrade during the login
When the address given for a login uses `https`, the frame SHALL use for the login only `https` addresses on the same
host and port. Otherwise it SHALL stop the login with its own error, before the login page opens. The login page SHALL
be an `http` or `https` address on the server's host. An address the server reports SHALL NOT replace `https` with
`http`, also not when an `https` account logs in again.

#### Scenario: Server reports an http endpoint
- **WHEN** the user starts a login with an `https` address and the server answers with a poll endpoint or login page
  on `http` or on another port
- **THEN** the login stops with the error `nextcloud_insecure_login`, which points to the server's protocol settings,
  and no login page opens

#### Scenario: Login page on another host
- **WHEN** the server answers with a login page on another host or with a scheme other than `http` and `https`
- **THEN** the login stops with an error and no page opens

#### Scenario: http account moves to https
- **WHEN** an account connected over `http` logs in again and the server now reports `https` on the same host
- **THEN** the source keeps its ID, photos and settings and uses `https` from then on

#### Scenario: https account and an http address
- **WHEN** the user logs in again to an `https` account and types the address with `http`
- **THEN** the login runs over `https` from its first request, and the source stays on `https`

#### Scenario: Server moves to https on another port
- **WHEN** an account connected over `http` on one port logs in again and the server reports `https` on another port
  of the same host
- **THEN** the existing source gets the new address and credentials, and no second source appears

### Requirement: No orphaned app passwords
When a new login replaces the app password of a source, or a login fails after the user approved it, the frame SHALL
ask the server in the background to revoke the app password it no longer uses. It SHALL NOT do so for a password the
server already refused. An app password left from version 0.1.0 SHALL be revoked the same way if it differs from the
one in use, and its file SHALL be deleted.

#### Scenario: Log in again
- **WHEN** an account that is a source logs in again and gets a new app password
- **THEN** the old app password is revoked on the server, and the source works with the new one

#### Scenario: Failure after the approval
- **WHEN** the user approved the access but the login fails afterwards, for example because the user name could not be
  read
- **THEN** the new app password is revoked on the server and the login reports the error

#### Scenario: Refused password
- **WHEN** the last sync failed because the server refused the app password, and the user logs in again or removes
  the source
- **THEN** the frame does not send the refused password to the server again

#### Scenario: Leftover of version 0.1.0
- **WHEN** the frame starts and still has the account file of version 0.1.0
- **THEN** its app password is revoked if it is not the one the source uses, and the file is deleted

#### Scenario: List of sources unreadable
- **WHEN** the frame starts with the account file of version 0.1.0 and a list of sources that cannot be read
- **THEN** no app password is revoked and the account file stays

### Requirement: Login names beyond ASCII
Login, sync and revocation of an account SHALL work with a login name that contains characters beyond ASCII.

#### Scenario: Name with an umlaut
- **WHEN** the user connects an account whose login name contains an umlaut, such as a directory user `jürgen`
- **THEN** the login succeeds, the source syncs and its removal revokes the app password

### Requirement: Plain http on every Android version
A Nextcloud address with `http` that the user enters deliberately SHALL work on every supported Android version,
including Android 9 and later.

#### Scenario: http server on Android 9 or later
- **WHEN** the user connects a Nextcloud server on an `http` address on a frame with Android 9 or later
- **THEN** the login and the sync work as with `https`, and the interface warns that the connection is not encrypted
