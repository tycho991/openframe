## MODIFIED Requirements

### Requirement: Skipped files
If the photo folder contains files that the frame could not accept, the interface SHALL point this out and list them with path and reason. The reasons `crashed` and `too_large` SHALL have their own texts.

#### Scenario: HEIC over USB on an old device
- **WHEN** a HEIC file has been copied into the photo folder over USB on a frame with Android 6
- **THEN** the interface shows a hint, and the list names the file with the reason "not supported"

#### Scenario: File that crashed the decoder
- **WHEN** a file was skipped because the decoder crashed on it
- **THEN** the list names it with a reason saying that it made the decoder crash

## ADDED Requirements

### Requirement: Quarantined photos in the interface
For a quarantined photo, the photo grid and the detail view SHALL show a placeholder without requesting the image, and it SHALL NOT be an album's cover. The section editor SHALL NOT be offered for it. Selecting, deleting and hiding SHALL stay possible as for other photos, and the detail view SHALL offer to try the photo again.

#### Scenario: Grid with a quarantined photo
- **WHEN** the overview shows a quarantined photo
- **THEN** its tile shows the placeholder and the browser requests no image for it

#### Scenario: Album with only quarantined photos
- **WHEN** every photo of an album is quarantined
- **THEN** the album list shows the album without a cover image

#### Scenario: Try again
- **WHEN** the user chooses to try a quarantined photo again in the detail view
- **THEN** the photo is no longer quarantined and its image is requested again

### Requirement: Safe mode notice
While the frame is in safe mode, the interface SHALL show a banner that explains that photos are paused after repeated crashes and offers to start the frame normally. The error codes `safe_mode`, `storage_unavailable`, `decoder_unavailable` and `image_too_large` SHALL each have an understandable message.

#### Scenario: Frame in safe mode
- **WHEN** the user opens the interface of a frame in safe mode
- **THEN** a banner explains the safe mode and offers "Start normally"

#### Scenario: Start normally from the banner
- **WHEN** the user chooses "Start normally" in the banner
- **THEN** the frame restarts in normal mode and the banner disappears once the frame reports normal mode

#### Scenario: Upload of an oversized image
- **WHEN** the user uploads an image over the size limit
- **THEN** the interface names the file and says that the image is too large
