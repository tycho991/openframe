# Spec Delta

## Purpose

Shows the photos of a folder on a Nextcloud server on the frame: connecting without typing a password, keeping the frame in step with the folder in the background, and what happens when the server cannot be reached or the storage is short.

## ADDED Requirements

### Requirement: Connect with the login flow
The frame SHALL connect to a Nextcloud server through Nextcloud's Login Flow v2: the user gives the server address, approves the access in a browser tab, and the frame receives an app password. The frame SHALL accept only addresses with `http` or `https`. The user SHALL never have to type a password into the frame or its admin interface.

#### Scenario: Approval in the browser
- **WHEN** the user gives the address of a Nextcloud server and approves the access in the page that opens
- **THEN** the frame reports that it is connected and shows the user name that Nextcloud returned

#### Scenario: Approval not given
- **WHEN** the user does not approve the access within 20 minutes
- **THEN** the frame reports that the login expired and is not connected

#### Scenario: Server not reachable
- **WHEN** the address is wrong or the server does not answer
- **THEN** the frame reports the error and nothing is stored

### Requirement: Secrecy of the credentials
The app password SHALL be stored only in the frame's private storage. The API SHALL NOT return it, and it SHALL NOT appear in the log, in error messages or in the list of settings.

#### Scenario: Read the configuration
- **WHEN** a paired client reads the Nextcloud configuration
- **THEN** the response names server, user and options, and contains no password

#### Scenario: Failed sync
- **WHEN** a sync fails because of wrong credentials
- **THEN** the error in the status and in the log contains no password and no authorization header

### Requirement: Configuration
The Nextcloud source SHALL have these settings: enabled, folder, subfolders included (default on), remove vanished photos (default on), interval in minutes (5 to 1,440, default 20) and a budget for originals (100 to 50,000 MB, default 2,000 MB). There is no default folder: until one is chosen nothing syncs, not even on request. Invalid values SHALL be rejected naming the field; changes SHALL be partial and take effect without a restart.

#### Scenario: Interval too short
- **WHEN** a client sets the interval to 2 minutes
- **THEN** the API answers 400 and names the interval field

#### Scenario: Connected without a folder
- **WHEN** the user has connected and has not chosen a folder
- **THEN** nothing is listed or downloaded, and a request to sync is answered with 409

#### Scenario: Change of the folder
- **WHEN** the folder is changed
- **THEN** the next sync reads the new folder and removes the photos of the old one if removal is on

### Requirement: Sync of a folder
A sync SHALL list the configured folder (and its subfolders if included) and make the photos of the source match the image files found there. Formats are those of `photo-library`. The first folder below the configured one SHALL be the album, as for local photos. Videos and other files SHALL be ignored.

#### Scenario: New photo
- **WHEN** a photo is added to the folder on the server and a sync runs
- **THEN** it is downloaded, enters the index and joins the slideshow

#### Scenario: Subfolder as album
- **WHEN** the folder holds `holiday/beach.jpg`
- **THEN** the photo belongs to the album `holiday`

### Requirement: No repeated downloads
A photo whose ETag on the server is the same as at the last sync SHALL NOT be downloaded again. A changed ETag SHALL replace the photo, keep its ID and hidden state and raise its image version. A photo whose original was removed from the cache SHALL be fetched again when a rendering needs it, and by a sync only while the budget has room for it.

#### Scenario: Nothing changed
- **WHEN** a sync runs and no file on the server has changed
- **THEN** no photo is downloaded and the index stays the same

#### Scenario: Budget smaller than the folder
- **WHEN** the originals do not all fit into the budget and a sync runs repeatedly
- **THEN** it does not download the same originals again and again

#### Scenario: Photo replaced on the server
- **WHEN** a photo is replaced on the server by another file of the same name
- **THEN** the frame downloads it, the photo keeps its ID and hidden state, and its image version increases

### Requirement: Vanished photos
If "remove vanished photos" is on, photos that are no longer in the folder SHALL be removed from the index and from the caches after a sync that listed the folder completely. If it is off, they SHALL stay on the frame.

#### Scenario: Photo deleted on the server
- **WHEN** a photo is deleted in Nextcloud and a sync runs
- **THEN** it disappears from the frame

#### Scenario: Removal switched off
- **WHEN** "remove vanished photos" is off and a photo is deleted in Nextcloud
- **THEN** the photo stays on the frame

### Requirement: A failed sync loses nothing
A sync that fails, for example without network, with wrong credentials or with a server error, SHALL leave the photos on the frame as they were. A listing that was not read completely SHALL NOT remove any photo. Photos that were downloaded before the failure MAY stay.

#### Scenario: Wi-Fi off
- **WHEN** the frame has no network while a sync is due
- **THEN** the slideshow keeps running with the photos it has and the status shows the error

#### Scenario: Server error in the middle of the listing
- **WHEN** the server answers with an error for a subfolder
- **THEN** no photo is removed and the status shows the error

### Requirement: Schedule and retry
The frame SHALL sync once shortly after start and then every configured interval while the source is enabled. After a failed sync it SHALL try again after 60 seconds, then after doubling delays, never later than the interval. A success SHALL end the delays. A sync SHALL NOT start while another is running.

#### Scenario: Frame starts before the network is up
- **WHEN** the first sync after start fails because the network is not ready
- **THEN** the frame tries again after 60 seconds, not after the whole interval

### Requirement: Status
The API SHALL report whether a sync is running, the time and the result of the last one, its error text and the numbers of photos added, updated and removed.

#### Scenario: After a sync
- **WHEN** a sync has finished
- **THEN** the status shows its time, `ok` and the three numbers

#### Scenario: After a failure
- **WHEN** a sync has failed
- **THEN** the status shows `ok` false and a readable error text

### Requirement: Sync on request
The API SHALL start a sync on request and answer at once. If one is already running, the request SHALL NOT start another and SHALL say so.

#### Scenario: Sync now
- **WHEN** a client asks for a sync
- **THEN** the API answers 202 and the status shows that a sync is running

### Requirement: Storage of the originals
Downloaded originals SHALL stay within their budget. When it is exceeded, the originals unused for the longest time SHALL be removed. A removed original SHALL be fetched again when a new rendering needs it. A download SHALL NOT leave less than 100 MB of free storage.

#### Scenario: Budget exceeded
- **WHEN** the downloaded originals exceed the budget
- **THEN** the ones unused longest are removed, the photos stay in the index and in the slideshow

#### Scenario: Rendering after eviction
- **WHEN** a photo whose original was removed has to be rendered again
- **THEN** the frame fetches the original and renders it

### Requirement: Trusted connections on old devices
Connections to `https` servers SHALL work on Android 5 and 6, including servers whose certificate is issued by Let's Encrypt. The frame SHALL accept the certificates that the system accepts and, in addition, those issued under the root certificates delivered with the app.

#### Scenario: Server with a Let's Encrypt certificate
- **WHEN** the frame on Android 6 connects to a server with a certificate from Let's Encrypt
- **THEN** the connection is established

#### Scenario: Unknown certificate
- **WHEN** the server's certificate is issued by an authority neither the system nor the app knows
- **THEN** the connection is refused and the status shows the error

### Requirement: Access to the Nextcloud settings
All endpoints for the Nextcloud source SHALL require a valid token. The frame SHALL not send requests to servers other than the configured one, except to the address given for the login.

#### Scenario: Without token
- **WHEN** a client without a token reads the Nextcloud configuration
- **THEN** the API answers 401

### Requirement: Disconnect
The user SHALL be able to disconnect the source. The frame SHALL then delete the stored credentials, try to revoke the app password on the server, stop syncing and remove the photos of the source and their downloaded originals.

#### Scenario: Disconnect
- **WHEN** the user disconnects
- **THEN** the credentials and the Nextcloud photos are gone and the local photos are untouched
