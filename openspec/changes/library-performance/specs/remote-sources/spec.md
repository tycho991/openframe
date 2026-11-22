## MODIFIED Requirements

### Requirement: Storage of the originals
Downloaded originals SHALL stay within the budget of their source. When it is exceeded, the originals of that source unused for the longest time SHALL be removed. A removed original SHALL be fetched again when a new rendering needs it, unless its source is in the state of rejected access. A download SHALL NOT leave less free storage than the storage reserve (`photo-library`), whatever the budgets are; one for a rendering someone waits for SHALL leave at least 100 MiB free.

#### Scenario: Budget exceeded
- **WHEN** the downloaded originals of a source exceed its budget
- **THEN** the ones of that source unused longest are removed, the photos stay in the index and in the slideshow

#### Scenario: Budgets larger than the storage
- **WHEN** the budgets of several sources together are more than the free storage
- **THEN** a download that would leave less free storage than the storage reserve fails with a readable error and nothing is removed from the library

#### Scenario: Removed original on a nearly full frame
- **WHEN** the free storage is below the storage reserve but more than 100 MiB plus the size of the original, and the slideshow waits for a photo whose original was removed from the cache
- **THEN** the original is downloaded and the photo is shown, while no original is downloaded for a photo that is only rendered ahead of time

#### Scenario: Storage almost full
- **WHEN** less than 100 MiB plus the size of the original would remain free and a rendering needs an original that was removed from the cache
- **THEN** the original is not downloaded, the rendering fails, the photo stays in the library, and the slideshow shows other photos

### Requirement: Remove a source
The user SHALL be able to remove a source. The frame SHALL then delete its stored credentials, ask the server to revoke them if the kind has any to revoke and the server has not already refused them, stop syncing it and remove its photos and downloaded originals. The removal SHALL answer without waiting for files to be deleted; originals and rendered sizes left by a removal that was cut off SHALL be deleted at the next start. Other sources and local photos SHALL be untouched.

#### Scenario: Remove
- **WHEN** the user removes a source
- **THEN** its credentials and photos are gone and the local photos and the other sources are untouched

#### Scenario: Source with many photos
- **WHEN** the user removes a source with 10,000 photos whose originals and rendered sizes are on the frame
- **THEN** the API answers without waiting for the files, the photos are gone from the library at once, and the files are deleted in the background

#### Scenario: App ends during the deletion
- **WHEN** the app ends while the files of a removed source are being deleted, and starts again
- **THEN** no original and no rendered size of that source is left after the start
