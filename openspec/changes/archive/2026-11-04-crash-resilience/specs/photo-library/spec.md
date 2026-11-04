## MODIFIED Requirements

### Requirement: Local photo folder
The frame SHALL have a photo folder that is reachable over USB (MTP) without root. Subfolders SHALL be scanned. New, changed and removed files SHALL be in the index 30 seconds after the change at the latest. Files that the frame itself creates or deletes, for example through upload, SHALL afterwards also be visible correctly over USB. While the storage is unavailable, entries SHALL NOT be removed (see "Photo folder kept").

#### Scenario: Photo copied over USB
- **WHEN** a photo is copied into the photo folder over USB
- **THEN** it is in the index and visible after 30 seconds at the latest

#### Scenario: File removed
- **WHEN** a file is deleted from the photo folder
- **THEN** its entry disappears from the index after 30 seconds at the latest

#### Scenario: Uploaded photo over USB
- **WHEN** a photo is uploaded through the admin interface and the frame is connected over USB afterwards
- **THEN** the photo is visible in the photo folder

## ADDED Requirements

### Requirement: Skipped after a decoder crash
If the decoding process crashes or hangs while a file from the photo folder or from a source is being checked, the file SHALL appear in the list of skipped files with the reason `crashed`, like any file that cannot be read; a changed file of a photo in the library loses its entry this way. It SHALL NOT be checked or downloaded again until its size or modification time changes.

#### Scenario: Poisoned file copied over USB
- **WHEN** a file is copied into the photo folder whose check crashes the decoder
- **THEN** the next scan lists it as skipped with the reason `crashed`, and later scans do not check it again

#### Scenario: File replaced
- **WHEN** a file skipped as `crashed` is replaced by a file of a different size
- **THEN** the next scan checks it again

### Requirement: Quarantined photos
If the decoder crashes or hangs while rendering a photo of the library, that version of the photo SHALL be quarantined: it is never rendered and never shown in the slideshow, but stays in the library with its comment, section and visibility. The photo list SHALL mark it as quarantined. It SHALL be possible to hide it, to delete it if it is a local photo, and to retry it through the API. A new version of the file SHALL lift the quarantine.

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
- **THEN** file, index entry and rendered sizes are removed

### Requirement: Image size limits
Images of more than 100 megapixels or with an aspect ratio of more than 32:1 SHALL be skipped with the reason `too_large`; an upload of such an image SHALL be rejected with 415 and the error code `image_too_large`. Checking an image SHALL decode at most about one megapixel. A memory error while checking SHALL skip the file as `too_large`, a memory error while rendering only that rendering.

#### Scenario: Very long strip
- **WHEN** a PNG of 500,000 × 127 pixels is copied into the photo folder
- **THEN** it is listed as skipped with the reason `too_large` and the app keeps running

#### Scenario: Upload over the limit
- **WHEN** a JPEG of 120 megapixels is uploaded
- **THEN** the API answers 415 with `image_too_large` and stores nothing

#### Scenario: Large phone photo
- **WHEN** a JPEG of 48 megapixels is copied into the photo folder
- **THEN** it is accepted and shown

### Requirement: Photo folder kept
The photo folder chosen at the first start SHALL be kept; the frame SHALL NOT switch to internal storage while the external storage is unavailable. While the storage is not mounted or the folder cannot be listed, a scan SHALL NOT remove entries and uploads SHALL be rejected with 503 and the error code `storage_unavailable`. A folder deleted over USB on mounted storage SHALL be created again, and its entries removed.

#### Scenario: Storage mounted late after a restart
- **WHEN** the device restarts and its storage is mounted only after the app has started
- **THEN** no photo disappears from the library, and comments, sections and hidden states are kept

#### Scenario: Upload while the storage is unavailable
- **WHEN** a photo is uploaded while the storage is not mounted
- **THEN** the API answers 503 with `storage_unavailable` and stores nothing

#### Scenario: Folder deleted over USB
- **WHEN** the whole photo folder is deleted over USB
- **THEN** the folder exists again after the next scan and no photo of the folder remains in the library

### Requirement: Partial uploads after a crash
Partial files of an upload that was interrupted by a crash, a kill or an update SHALL be deleted at the next start, before the server accepts uploads.

#### Scenario: Crash during an upload
- **WHEN** the app ends while a photo is being uploaded and starts again
- **THEN** no partial file of that upload remains in the photo folder
