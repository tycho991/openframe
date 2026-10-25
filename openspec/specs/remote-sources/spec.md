# remote-sources Specification

## Purpose
Shows the photos of several remote places on the frame at the same time, each as a source of its own: what a source has in common whatever it connects to, how it is kept in step in the background, and what happens when it cannot be reached, the storage is short or it is removed. The kinds of source are specified in their own capabilities, for example `nextcloud-source`.

## Requirements

### Requirement: Several sources
The frame SHALL hold up to eight remote sources at the same time. Each SHALL have its own connection, settings, status, schedule, photos and original cache. Adding, changing, syncing or removing one source SHALL NOT change the photos of another. A ninth source SHALL be refused with 409 and the code `too_many_sources`.

#### Scenario: Two sources
- **WHEN** two sources are connected
- **THEN** the photos of both are in the library and each source has its own status

#### Scenario: Remove one of two
- **WHEN** one of two sources is removed
- **THEN** only its photos go; those of the other source and the local photos stay

#### Scenario: Ninth source
- **WHEN** eight sources exist and a client adds another
- **THEN** the API answers 409 with `too_many_sources`

### Requirement: Configuration
Each source SHALL have these settings: name, enabled, folder, subfolders included (default on), remove vanished photos (default on), interval in minutes (5 to 1,440, default 20) and a budget for originals (100 to 50,000 MB, default 2,000 MB). Invalid values SHALL be rejected naming the field; changes SHALL be partial and take effect without a restart.

#### Scenario: Interval too short
- **WHEN** a client sets the interval of a source to 2 minutes
- **THEN** the API answers 400 and names the interval field

#### Scenario: Change of the folder
- **WHEN** the folder of a source is changed
- **THEN** the next sync reads the new folder and removes the photos of the old one if removal is on

### Requirement: Folder before first sync
A source whose kind needs a choice of folder (an account) SHALL NOT list or download anything until one is chosen, and a request to sync SHALL be answered with 409. A source that already names its folder (a share link) SHALL sync from the start.

#### Scenario: Account without a folder
- **WHEN** an account has been connected and no folder is chosen
- **THEN** nothing is listed or downloaded, and a request to sync is answered with 409

#### Scenario: Share link
- **WHEN** a share link has been added
- **THEN** its photos are synced without choosing a folder

### Requirement: Sync of a folder
A sync SHALL list the source's folder (and its subfolders if included) and make the photos of the source match the image files found there. Formats are those of `photo-library`. The first folder below the source's folder SHALL be the album, as for local photos, so folders of the same name in different sources are one album. Videos and other files SHALL be ignored.

#### Scenario: New photo
- **WHEN** a photo is added to the folder of a source and a sync runs
- **THEN** it is downloaded, enters the index and joins the slideshow

#### Scenario: Same album name in two sources
- **WHEN** two sources both have a folder `holiday` with photos
- **THEN** the photos of both belong to the album `holiday`

### Requirement: No repeated downloads
A photo whose ETag at the source is the same as at the last sync SHALL NOT be downloaded again. A changed ETag SHALL replace the photo, keep its ID and hidden state and raise its image version. A photo whose original was removed from the cache SHALL be fetched again when a rendering needs it, and by a sync only while the budget has room for it.

#### Scenario: Nothing changed
- **WHEN** a sync runs and no file at the source has changed
- **THEN** no photo is downloaded and the index stays the same

#### Scenario: Photo replaced at the source
- **WHEN** a photo is replaced at the source by another file of the same name
- **THEN** the frame downloads it, the photo keeps its ID and hidden state, and its image version increases

### Requirement: Vanished photos
If "remove vanished photos" is on for a source, photos that are no longer in its folder SHALL be removed from the index and from the caches after a sync that listed the folder completely. If it is off, they SHALL stay on the frame.

#### Scenario: Photo deleted at the source
- **WHEN** a photo is deleted at the source and a sync runs
- **THEN** it disappears from the frame

#### Scenario: Removal switched off
- **WHEN** "remove vanished photos" is off and a photo is deleted at the source
- **THEN** the photo stays on the frame

### Requirement: A failed sync loses nothing
A sync that fails, for example without network, with wrong credentials, with a withdrawn share or with a server error, SHALL leave the photos on the frame as they were. A listing that was not read completely SHALL NOT remove any photo. Photos that were downloaded before the failure MAY stay.

#### Scenario: Wi-Fi off
- **WHEN** the frame has no network while a sync is due
- **THEN** the slideshow keeps running with the photos it has and the status of the source shows the error

#### Scenario: Share withdrawn
- **WHEN** the owner deletes a share link that the frame uses and a sync runs
- **THEN** the photos stay on the frame and the status of the source says that the share was not found

### Requirement: Schedule and retry
The frame SHALL sync every enabled source shortly after start and then every configured interval. After a failed sync of a source it SHALL try that source again after 60 seconds, then after doubling delays, never later than its interval. A success SHALL end the delays. A sync of a source SHALL NOT start while another of the same source is running, and a failing source SHALL NOT delay the others.

#### Scenario: Frame starts before the network is up
- **WHEN** the first sync after start fails because the network is not ready
- **THEN** the frame tries again after 60 seconds, not after the whole interval

#### Scenario: One source fails
- **WHEN** one of two sources cannot be reached
- **THEN** the other keeps syncing on its schedule

### Requirement: Status
The API SHALL report for every source whether a sync is running or waits for the sync of another source, the time and the result of the last one, its error text and the numbers of photos added, updated and removed. A request to sync a source that waits SHALL NOT start another.

#### Scenario: After a sync
- **WHEN** a sync of a source has finished
- **THEN** its status shows the time, `ok` and the three numbers

#### Scenario: After a failure
- **WHEN** a sync of a source has failed
- **THEN** its status shows `ok` false and a readable error text

#### Scenario: Waiting for another source
- **WHEN** a sync of a source is due while another source syncs
- **THEN** its status shows that it waits, not that it syncs, and its sync follows when the other one ends

### Requirement: Sync on request
The API SHALL start a sync of one source on request and answer at once. If one is already running for that source, the request SHALL NOT start another and SHALL say so.

#### Scenario: Sync now
- **WHEN** a client asks for a sync of a source
- **THEN** the API answers 202 and the status of that source shows that a sync is running

### Requirement: Storage of the originals
Downloaded originals SHALL stay within the budget of their source. When it is exceeded, the originals of that source unused for the longest time SHALL be removed. A removed original SHALL be fetched again when a new rendering needs it. A download SHALL NOT leave less than 100 MB of free storage, whatever the budgets are.

#### Scenario: Budget exceeded
- **WHEN** the downloaded originals of a source exceed its budget
- **THEN** the ones of that source unused longest are removed, the photos stay in the index and in the slideshow

#### Scenario: Budgets larger than the storage
- **WHEN** the budgets of several sources together are more than the free storage
- **THEN** a download that would leave less than 100 MB free fails with a readable error and nothing is removed from the library

### Requirement: Remove a source
The user SHALL be able to remove a source. The frame SHALL then delete its stored credentials, ask the server to revoke them if the kind has any to revoke, stop syncing it and remove its photos and downloaded originals. Other sources and local photos SHALL be untouched.

#### Scenario: Remove
- **WHEN** the user removes a source
- **THEN** its credentials and photos are gone and the local photos and the other sources are untouched

### Requirement: Access to the sources
All endpoints for sources SHALL require a valid token. The frame SHALL send requests only to the servers of its sources and, while a login is being set up, to the address given for it.

#### Scenario: Without token
- **WHEN** a client without a token lists the sources
- **THEN** the API answers 401

### Requirement: Existing connection after the update
A frame that has a Nextcloud account connected before this change SHALL keep it as a source after the update, with its settings, status, photos, photo IDs, hidden state and downloaded originals, without connecting again.

#### Scenario: Update
- **WHEN** a frame with a connected Nextcloud account and hidden photos is updated
- **THEN** the source is listed with its settings, the same photos are in the library with the same IDs and hidden state, and no new login is needed
