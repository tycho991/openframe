# Spec Delta

## MODIFIED Requirements

### Requirement: Side-by-side operation with pictureframe
The app SHALL have its own package ID so that it can be installed next to `com.pictureframe.kiosk` without touching its data. pictureframe is the author's earlier kiosk app for the same frames; openframe replaces it device by device.

#### Scenario: Both installed
- **WHEN** openframe is installed on a device where pictureframe is running
- **THEN** pictureframe and its data remain unchanged
