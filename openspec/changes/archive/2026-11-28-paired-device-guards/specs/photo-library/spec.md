## MODIFIED Requirements

### Requirement: Delete
It SHALL be possible to delete photos through the API. The file of a local photo SHALL move into the trash (see "Trash for deleted photos"), and its index entry and all derived image sizes SHALL be removed. Photos of a source that the frame only reads (Nextcloud) SHALL NOT be deleted: the API refuses with 409 and the error code `read_only_source`, because the photo would come back with the next sync. Hiding it remains possible.

#### Scenario: Delete photo
- **WHEN** a photo is deleted
- **THEN** its file is in the trash and no longer at its path, and its index entry and rendered sizes are removed

#### Scenario: Delete a photo from Nextcloud
- **WHEN** a client deletes a photo that comes from Nextcloud
- **THEN** the API answers 409 with `read_only_source`, and the photo stays in the library

#### Scenario: Batch with Nextcloud photos
- **WHEN** a batch deletes a local photo and a Nextcloud photo together
- **THEN** the local photo is moved to the trash and the Nextcloud photo is reported as failed

### Requirement: Quarantined photos
If the decoder crashes or hangs while rendering a photo of the library, that version of the photo SHALL be quarantined: it is never rendered and never shown in the slideshow, but stays in the library with its comment, section and visibility. The photo list SHALL mark it as quarantined. It SHALL be possible to hide it, to delete it if it is a local photo, and to retry it through the API. A new version of the file SHALL lift the quarantine. Restoring it SHALL keep the quarantine.

#### Scenario: Crash while rendering
- **WHEN** the decoder crashes while rendering the display size of a photo
- **THEN** the photo is marked as quarantined, the slideshow skips it, and a request for its image answers 503 without decoding

#### Scenario: Visibility unchanged
- **WHEN** a quarantined photo was visible before
- **THEN** the photo list still reports it as visible and as quarantined

#### Scenario: Retry
- **WHEN** a paired client sets a quarantined photo back to not quarantined
- **THEN** the frame renders it again at the next request, and quarantines it again if the decoder crashes again

#### Scenario: Delete a quarantined photo
- **WHEN** a paired client deletes a quarantined local photo
- **THEN** its file is in the trash, its index entry and rendered sizes are removed, and its trash entry is marked as quarantined and has no thumbnail

#### Scenario: Restore a quarantined photo
- **WHEN** a quarantined local photo is restored from the trash
- **THEN** it is back in the library with a new image version and still quarantined, and the frame does not render it

## ADDED Requirements

### Requirement: Trash for deleted photos
A deleted local photo SHALL be kept in a hidden folder `.trash` inside the photo folder, below which the file keeps its path relative to the photo folder. The library SHALL NOT scan the trash, so a photo in it is in neither the index, the photo list nor the slideshow, and a rescan does not bring it back. The API SHALL offer no way to empty the trash.

#### Scenario: Deleted photo leaves the slideshow
- **WHEN** a local photo in the album `holiday` is deleted
- **THEN** the slideshow no longer shows it, the photo list no longer lists it, and it is still in the trash after the next scan

#### Scenario: No way to empty the trash
- **WHEN** a paired client sends `DELETE` to the trash or to one of its entries
- **THEN** the API answers 404 or 405, and the trash keeps all its entries

### Requirement: Entries of the trash
Only a folder directly in `.trash` whose name has the form of a trash entry SHALL count as one; the frame SHALL leave every other file and folder in `.trash` alone, never list, move or delete it, and SHALL NOT follow symbolic links there. An entry SHALL hold exactly one image file; an entry with none or several SHALL be listed as damaged and SHALL NOT be restorable.

#### Scenario: A folder of the user's own
- **WHEN** the user has copied a folder `.Trash` with files into `.trash` over USB
- **THEN** the trash does not list it, and no purge or restore touches it

#### Scenario: Entry with two files
- **WHEN** a second image file is copied into the folder of a trash entry over USB
- **THEN** the trash lists the entry as damaged, and restoring it reports it as failed and moves nothing

### Requirement: Retention of the trash
An entry SHALL stay in the trash for 7 days after it was deleted and SHALL then be deleted for good, checked after start and at least once an hour. An entry whose deletion time lies before a fixed date in the past, from a clock that was not yet set, SHALL get the current time as its deletion time, and with it a new ID, once the clock is past that date, instead of being deleted at once.

#### Scenario: After 7 days
- **WHEN** a photo was deleted 7 days and 1 hour ago
- **THEN** its file is gone from the trash and the trash no longer lists it

#### Scenario: Clock not yet set at the deletion
- **WHEN** a photo is deleted while the device's clock still shows a date years in the past, and the clock is set to the right date later
- **THEN** the photo stays in the trash for 7 days from when the clock was set

### Requirement: Trash under storage pressure
Under storage pressure the frame SHALL delete only trash entries deleted more than 24 hours ago, the oldest first, and only as many as the need requires. If deleting all such entries would still not meet the need, it SHALL delete none and refuse as before. Entries deleted within the last 24 hours SHALL stay.

#### Scenario: Upload makes room
- **WHEN** an upload would leave less free storage than the storage reserve and the trash holds photos deleted two days ago that free enough storage
- **THEN** those photos are deleted from the trash, oldest first, only as many as needed, and the upload succeeds

#### Scenario: Not enough to gain
- **WHEN** an upload would leave less free storage than the storage reserve, and deleting every trash entry older than 24 hours would not free enough
- **THEN** the API answers 507 and the trash keeps all its entries

#### Scenario: Only recent entries
- **WHEN** an upload would leave less free storage than the storage reserve and every photo in the trash was deleted within the last 24 hours
- **THEN** the API answers 507 and the trash keeps all its entries

### Requirement: Where storage pressure frees the trash
Storage pressure SHALL mean: an upload that would leave less than the storage reserve free while it is written; the render cache or a download of an original below the storage reserve after the render cache has trimmed itself; and an update that would be refused for lack of storage. The trash SHALL be used only when it is on the same volume as the storage that is short.

#### Scenario: Update on a full frame
- **WHEN** an update is found, the free storage is short of what its download needs, and trash entries older than 24 hours would free enough
- **THEN** those entries are deleted and the update is downloaded

#### Scenario: Announced size of an upload
- **WHEN** an upload announces a body larger than the free storage above the storage reserve
- **THEN** no trash entry is deleted before the body is written

### Requirement: Trash list through the API
Every paired device SHALL be able to list the trash. Each entry SHALL contain its ID, the photo's former ID and path (`null` when damaged), album, size, deletion time, the time after which it is deleted (`null` while the clock is not yet set), the deleting device's ID or `null`, whether it is quarantined or damaged, and a signed thumbnail URL or `null`. The list SHALL give the total size. While the photo folder is unavailable, listing SHALL answer 503 `storage_unavailable`.

#### Scenario: List after a delete
- **WHEN** a phone deletes the local photo `holiday/beach.jpg` and a laptop lists the trash
- **THEN** the list has an entry with the path `holiday/beach.jpg`, the album `holiday`, the phone's ID, the deletion time and a time 7 days later

#### Scenario: Storage unavailable
- **WHEN** a client lists the trash while the storage of the photo folder is not mounted
- **THEN** the API answers 503 with `storage_unavailable`

### Requirement: Thumbnails in the trash
A trash entry SHALL have a thumbnail URL signed like the image URLs of the photo list, valid for that entry only. An entry that is quarantined, damaged or without its saved state SHALL have no thumbnail URL, and a request for its thumbnail SHALL answer 503 without decoding. If the decoder crashes or hangs while rendering a trash thumbnail, the entry SHALL become quarantined.

#### Scenario: Thumbnail of a deleted photo
- **WHEN** a paired client lists the trash and fetches the thumbnail URL of an entry without a token
- **THEN** the frame serves a thumbnail of the deleted photo as long as the signature is valid

#### Scenario: Signature of a photo for a trash entry
- **WHEN** a client attaches the signature of a photo's image URL to the thumbnail URL of a trash entry
- **THEN** the API answers 401

#### Scenario: Crash on a trash thumbnail
- **WHEN** the decoder crashes while rendering the thumbnail of a trash entry
- **THEN** the entry is listed as quarantined without a thumbnail URL, its thumbnail is not rendered again, and restoring it gives a quarantined photo

### Requirement: Restore from the trash
Every paired device SHALL be able to restore one or several trash entries. A restored photo SHALL return to its path with its ID, a new image version, its hidden state, comment, section and quarantine, and SHALL be in the index at once. A missing album folder SHALL be created again. On a taken path the photo SHALL get a unique name and so a new ID, keeping its state. An entry that cannot be restored SHALL stay in the trash. In safe mode restoring SHALL answer 503 `safe_mode`.

#### Scenario: Restore a hidden photo with a comment
- **WHEN** a hidden photo with a comment and a section is deleted and then restored
- **THEN** it has its old ID, a new image version, is hidden, and has its comment and section, and the slideshow never showed it in between

#### Scenario: Path taken meanwhile
- **WHEN** `holiday/beach.jpg` is deleted, a new `holiday/beach.jpg` is uploaded, and the deleted one is restored
- **THEN** the upload keeps its name, and the restored photo is `holiday/beach (1).jpg` with a new ID and its old state

#### Scenario: Album folder gone
- **WHEN** the only photo of the album `trip/2024` is deleted, the empty folder is removed over USB, and the photo is restored
- **THEN** the folder `trip/2024` exists again and the photo belongs to the album `trip/2024`

#### Scenario: Album folder in another letter case
- **WHEN** the only photo of `Trip` is deleted, the folder is removed, a photo is uploaded into the album `trip`, and the deleted photo is restored
- **THEN** it lies in the folder `trip` and belongs to the album `trip`

#### Scenario: Restore after a restart
- **WHEN** a photo is deleted and the frame restarts before it is restored
- **THEN** it is still in the trash and is restored with its ID and state

#### Scenario: Safe mode
- **WHEN** a client restores a trash entry while the frame is in safe mode
- **THEN** the API answers 503 with `safe_mode` and the entry stays in the trash

### Requirement: Album state of a restored photo
A restored photo SHALL take the current state of its album, by the album's own setting or by inheritance, as a photo copied there would. Restoring SHALL NOT change any album setting. An album that lost its last photo to the trash SHALL keep its own setting while it is empty.

#### Scenario: Hidden album that lost its last photo
- **WHEN** the album `holiday/2024` is shown inside the hidden album `holiday`, which holds other photos, and the only photo of `holiday/2024` is deleted and then restored
- **THEN** `holiday/2024` is still shown with a setting of its own, and the restored photo is visible

#### Scenario: Parent switched while the photo was in the trash
- **WHEN** `holiday/2024` is shown inside the hidden `holiday`, which holds other photos, the only photo of `holiday/2024` is deleted, `holiday` is shown and hidden again, and the photo is restored
- **THEN** `holiday/2024` has no setting of its own and the restored photo is not visible

### Requirement: Restore during a scan
A scan that runs while a photo is restored or uploaded SHALL NOT replace the entry the frame has just put in place: an entry in the index whose file size and modification time match the file SHALL be kept as it is, with its version, state and quarantine.

#### Scenario: Restore while the folder is scanned
- **WHEN** a quarantined photo is restored while a scan of the photo folder is running
- **THEN** after the scan the photo has the version the restore gave it and is still quarantined

### Requirement: Trash without its saved state
The files in the trash SHALL be the source of truth. If the saved state of an entry is missing, the entry SHALL still be listed, purged and restorable; restoring it SHALL check the file like a photo copied over USB and add it as a hidden photo without comment or section. A file removed from the trash over USB SHALL leave the list without an error.

#### Scenario: State file lost
- **WHEN** a photo is deleted and the frame's record of the trash is lost before the next start
- **THEN** the trash still lists the entry without a deleting device and without a thumbnail, and restoring it adds the photo as a hidden photo

#### Scenario: Deleted from the trash over USB
- **WHEN** the file of a trash entry is deleted over USB
- **THEN** the trash no longer lists it after the next check at the latest, and restoring it reports it as missing

### Requirement: Log of trash actions
The frame SHALL write one info line to its log for each photo moved to the trash and each photo restored, naming the photo's path and the ID of the device that acted, and one line for each purge with the number of entries and bytes deleted.

#### Scenario: Deleted and restored by two devices
- **WHEN** a phone deletes a photo and a laptop restores it
- **THEN** the log has a line with the path and the phone's ID, and one with the path and the laptop's ID
