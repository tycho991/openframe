# Spec Delta

## MODIFIED Requirements

### Requirement: Delete
It SHALL be possible to delete photos through the API. The file and all derived image sizes SHALL be removed in the process. Photos of a source that the frame only reads (Nextcloud) SHALL NOT be deleted: the API refuses with 409 and the error code `read_only_source`, because the photo would come back with the next sync. Hiding it remains possible.

#### Scenario: Delete photo
- **WHEN** a photo is deleted
- **THEN** file, index entry and rendered sizes are removed

#### Scenario: Delete a photo from Nextcloud
- **WHEN** a client deletes a photo that comes from Nextcloud
- **THEN** the API answers 409 with `read_only_source`, and the photo stays in the library

#### Scenario: Batch with Nextcloud photos
- **WHEN** a batch deletes a local photo and a Nextcloud photo together
- **THEN** the local photo is deleted and the Nextcloud photo is reported as failed

### Requirement: Limit storage space
The cached image sizes SHALL stay within the budget from the settings (`frame-settings`). When it is exceeded, the entries that have gone unused longest SHALL be removed. Originals that are the only copy, those in the local photo folder, SHALL NOT be deleted in the process. Downloaded originals of Nextcloud photos are a cache and follow the budget of `nextcloud-source`.

#### Scenario: Budget exceeded
- **WHEN** the cache exceeds the budget
- **THEN** the cache entries unused for the longest time are removed until it is below the budget again, and all originals in the local photo folder are kept
