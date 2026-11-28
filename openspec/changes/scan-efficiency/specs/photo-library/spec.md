## ADDED Requirements

### Requirement: Cost of an unchanged scan
A scan of the photo folder SHALL learn whether an entry is a file, its size and its modification time from one query to the system per entry, and SHALL NOT open a file whose size and modification time are unchanged. A new way of reading these facts SHALL NOT make files the index already knows count as changed.

#### Scenario: Nothing changed with 5,000 photos
- **WHEN** a scan runs over a photo folder with 5,000 known photos and none of them has changed
- **THEN** it queries each entry once, opens no file, and the library version stays the same

#### Scenario: Times read another way
- **WHEN** the faster way of reading modification times gives a different time for a known file than the way the index was built with
- **THEN** the frame keeps reading times the old way, and no known photo is read again

### Requirement: Files that vanish during a scan
A file that disappears after a scan has listed the photo folder SHALL NOT be decoded and SHALL NOT be put on the list of skipped files; that scan or the next SHALL remove its entry. Of the unexpected read errors of files that still exist, a scan SHALL log the first with at most one stack trace and count the others in one line.

#### Scenario: Folder deleted during a scan
- **WHEN** a folder with 5,000 photos is deleted over USB while a scan runs
- **THEN** none of its files is decoded or listed as skipped, and their entries are gone after the next scan at the latest

#### Scenario: Many unreadable files
- **WHEN** a scan fails to read 100 new files that still exist with an unexpected error
- **THEN** all 100 are on the list of skipped files, and the scan logs one line with a stack trace and one line counting the other 99

### Requirement: Many new photos at once
When a scan reads many new or changed files, the frame SHALL add the photos read so far to the index at least every 10 seconds instead of only at the end of the scan. The 30-second limit of "Local photo folder" SHALL apply to the first of them; the others SHALL follow while the frame reads them.

#### Scenario: 5,000 photos copied at once
- **WHEN** 5,000 photos are copied into the photo folder at once while the display is on
- **THEN** the first of them are in the index after 30 seconds at the latest, and more follow at least every 10 seconds until all are in
