# Delta for remote-sources

## MODIFIED Requirements

### Requirement: Sync of a folder
A sync SHALL list the source's folder (and its subfolders if included) and make the photos of the source match the image files found there. Formats are those of `photo-library`. The folder path below the source's folder SHALL be the album, as for local photos, so folders of the same path in different sources are one album. Videos and other files SHALL be ignored.

#### Scenario: New photo
- **WHEN** a photo is added to the folder of a source and a sync runs
- **THEN** it is downloaded, enters the index and joins the slideshow

#### Scenario: Same album name in two sources
- **WHEN** two sources both have a folder `holiday` with photos
- **THEN** the photos of both belong to the album `holiday`

#### Scenario: Nested subfolders as sub-albums
- **WHEN** the folder of a source holds `holiday/2024/may/beach.jpg` and subfolders are included
- **THEN** the photo belongs to the album `holiday/2024/may`, a sub-album of `holiday/2024` and `holiday`
