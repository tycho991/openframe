## MODIFIED Requirements

### Requirement: Schedule and retry
The frame SHALL sync every enabled source shortly after start and then every configured interval. After a failed sync of a source it SHALL try that source again after 60 seconds, then after doubling delays, never later than its interval, except for rejected access and for a server that asks to wait. A success SHALL end the delays. A sync of a source SHALL NOT start while another of the same source is running, and a failing source SHALL NOT delay the others.

#### Scenario: Frame starts before the network is up
- **WHEN** the first sync after start fails because the network is not ready
- **THEN** the frame tries again after 60 seconds, not after the whole interval

#### Scenario: One source fails
- **WHEN** one of two sources cannot be reached
- **THEN** the other keeps syncing on its schedule

#### Scenario: Server asks to wait
- **WHEN** a server answers with HTTP 429 and `Retry-After`
- **THEN** the frame waits as long as asked, at least 15 minutes and at most 24 hours, also when the network changes
  meanwhile, before it tries that source again

### Requirement: Status
The API SHALL report for every source whether a sync is running or waits for the sync of another source, the time and the result of the last one, its error text, an error code from a fixed list, and the numbers of photos added, updated and removed. A request to sync a source that waits SHALL NOT start another.

#### Scenario: After a sync
- **WHEN** a sync of a source has finished
- **THEN** its status shows the time, `ok` and the three numbers, and no error code

#### Scenario: After a failure
- **WHEN** a sync of a source has failed
- **THEN** its status shows `ok` false, a readable error text and the error code of the failure

#### Scenario: Result from an older version
- **WHEN** the frame starts with a stored result of a failed sync that has no error code
- **THEN** its status shows the stored error text and no error code

#### Scenario: Waiting for another source
- **WHEN** a sync of a source is due while another source syncs
- **THEN** its status shows that it waits, not that it syncs, and its sync follows when the other one ends

### Requirement: No repeated downloads
A photo whose ETag at the source is the same as at the last sync SHALL NOT be downloaded again. A changed ETag SHALL replace the photo, keep its ID and hidden state and raise its image version. A photo whose original was removed from the cache SHALL be fetched again when a rendering needs it, unless its source is in the state of rejected access, and by a sync only while the budget has room for it.

#### Scenario: Nothing changed
- **WHEN** a sync runs and no file at the source has changed
- **THEN** no photo is downloaded and the index stays the same

#### Scenario: Photo replaced at the source
- **WHEN** a photo is replaced at the source by another file of the same name
- **THEN** the frame downloads it, the photo keeps its ID and hidden state, and its image version increases

### Requirement: Storage of the originals
Downloaded originals SHALL stay within the budget of their source. When it is exceeded, the originals of that source unused for the longest time SHALL be removed. A removed original SHALL be fetched again when a new rendering needs it, unless its source is in the state of rejected access. A download SHALL NOT leave less than 100 MB of free storage, whatever the budgets are.

#### Scenario: Budget exceeded
- **WHEN** the downloaded originals of a source exceed its budget
- **THEN** the ones of that source unused longest are removed, the photos stay in the index and in the slideshow

#### Scenario: Budgets larger than the storage
- **WHEN** the budgets of several sources together are more than the free storage
- **THEN** a download that would leave less than 100 MB free fails with a readable error and nothing is removed from the library

### Requirement: Remove a source
The user SHALL be able to remove a source. The frame SHALL then delete its stored credentials, ask the server to revoke them if the kind has any to revoke and the server has not already refused them, stop syncing it and remove its photos and downloaded originals. Other sources and local photos SHALL be untouched.

#### Scenario: Remove
- **WHEN** the user removes a source
- **THEN** its credentials and photos are gone and the local photos and the other sources are untouched

### Requirement: Access to the sources
All endpoints for sources SHALL require a valid token. For its sources, the frame SHALL send requests only to the servers of its sources, while a login is being set up to the address given for it, and to revoke a leftover app password of version 0.1.0 to the server named with it. Requests for app updates are governed by `app-update`.

#### Scenario: Without token
- **WHEN** a client without a token lists the sources
- **THEN** the API answers 401

## ADDED Requirements

### Requirement: Rejected access
After a sync failed because the server refused the credentials for the folder, the share was withdrawn or the share
allows no reading, the frame SHALL try that source again automatically at most once a day, also across a restart; a
network change SHALL NOT shorten this. A new login, a change of the source's configuration or "Sync now" SHALL try at
once. Until a sync succeeds, the frame SHALL NOT fetch originals of that source for a rendering.

#### Scenario: Password changed on the server
- **WHEN** the app password of an account was revoked on the server and a sync fails with refused credentials
- **THEN** the frame makes no further attempt for that source before a day has passed, also after a restart of the
  app or a network change, and the status names the reason

#### Scenario: User logs in again
- **WHEN** the user logs in again to an account in this state, or chooses "Sync now", also right before a network
  change
- **THEN** the frame syncs that source at once

#### Scenario: One file refused
- **WHEN** the folder can be listed but the server refuses the download of one file
- **THEN** the sync fails as before and the frame tries again after 60 seconds, not after a day

#### Scenario: Removed original wanted
- **WHEN** a rendering needs an original of a source in this state that is no longer stored
- **THEN** the rendering fails without a request to the server, and the slideshow shows other photos

### Requirement: Bounded downloads
A download SHALL end when it delivers more bytes than the listing gave, or, without a listed size, than the server
announced or a fixed limit allows. It SHALL also end when it would leave less free storage than the reserve, when it
stays below a minimum speed or takes longer than a fixed time, or when its source is removed. A download that ends
this way SHALL leave no partial file and SHALL NOT hold up the sync of other sources for longer than that time.

#### Scenario: Server sends more than listed
- **WHEN** a server sends more bytes for a new file than its listing said
- **THEN** the download ends, no partial file stays, the file is reported as too large and not downloaded again while
  its size and time stay the same, and the sync goes on with the other files

#### Scenario: Known photo sends more than listed
- **WHEN** the original of a photo already in the library is fetched again and the server sends more bytes than listed
- **THEN** the download ends and the photo stays in the library with its hidden state, comment and section

#### Scenario: File replaced after the listing
- **WHEN** the server announces a size for a file that is clearly larger than the listing gave, because it was
  replaced in between
- **THEN** that file is skipped in this sync without failing it, and the next sync fetches the new version

#### Scenario: Download too slow
- **WHEN** a server delivers a file slower than the minimum speed, or so slowly that it would take longer than the
  fixed time
- **THEN** the download ends with an error that says so, and other sources sync on their schedule

#### Scenario: Source removed during a sync
- **WHEN** the user removes a source while its listing or one of its downloads runs
- **THEN** the listing or download ends, no partial file stays, the slideshow keeps running, and the next source can
  sync

### Requirement: Time limit of a sync
A sync of a source SHALL end after a fixed time, also while it lists the folder or downloads a file, with a readable
error that names the limit. What it downloaded before SHALL stay, no photo SHALL be removed, the other sources SHALL
sync afterwards, and the next sync of the source SHALL go on.

#### Scenario: Slow server with many folders
- **WHEN** a server answers every folder of a large tree so slowly that the sync would take longer than the limit
- **THEN** the sync ends at the limit with an error that says so, the photos downloaded so far stay, and the next
  source syncs

### Requirement: Rendering does not wait for downloads
An original of a remote photo that is not stored SHALL be fetched outside the rendering of other photos. While it
downloads, local photos and remote photos whose original is stored SHALL keep rendering.

#### Scenario: Slow server
- **WHEN** a rendering needs an original from a server that answers slowly
- **THEN** the renderings of local photos and of photos with a stored original finish meanwhile, and the slideshow
  keeps changing photos

#### Scenario: Two sizes of one photo
- **WHEN** two sizes of the same remote photo are wanted while its original is missing
- **THEN** the original is downloaded once and both renderings use it

### Requirement: Refetched original matches the index
When an original fetched for a rendering has, after its rotation, other dimensions than the photo in the library, or
the server announces it as clearly larger than listed, the frame SHALL discard it, the rendering SHALL fail, and the
source SHALL sync soon. An entity tag that differs on its own SHALL NOT discard an original whose dimensions match.

#### Scenario: Replaced on the server
- **WHEN** a photo was replaced on the server by an image of other dimensions or of a clearly larger size, and its
  original is fetched again for a rendering before the next sync
- **THEN** the image is discarded, nothing distorted is shown, and the source syncs within a minute

#### Scenario: Proxy changes the entity tag
- **WHEN** a proxy returns another entity tag for an unchanged file
- **THEN** the original is used for the rendering

### Requirement: Undecodable formats are not downloaded
When the first bytes of a file show a format the device cannot decode, such as HEIC before Android 9, the frame SHALL
end its download and remember the file with its size and time as not supported. The format SHALL be told by the
content, not by the file name.

#### Scenario: HEIC on Android 6
- **WHEN** a source on a frame with Android 6 lists HEIC photos
- **THEN** each is ended after its first bytes, it is reported as not supported, and it is not downloaded again while
  its size and time stay the same

#### Scenario: JPEG with a HEIC name
- **WHEN** a file named `.heic` holds a JPEG
- **THEN** it is downloaded and shown
