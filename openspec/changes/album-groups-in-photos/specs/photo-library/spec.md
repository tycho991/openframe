# Spec Delta

## ADDED Requirements

### Requirement: Album list through the API
The API SHALL list every album that holds a photo in its branch, in tree order, with its effective state, whether it differs from its parent, the number of photos and of visible photos in its branch, and a cover: the newest photo of the branch that is not quarantined, none if all are. Visible SHALL mean what the visibility filter of the photo list means, so a quarantined photo counts by its hidden state alone.

#### Scenario: Counts of a branch
- **WHEN** `holiday` holds 10 photos and `holiday/2024` 30, and 5 of them are hidden
- **THEN** the entry of `holiday` has 40 photos and 35 visible ones

#### Scenario: Hidden by the album and by the photo
- **WHEN** `holiday` with 10 photos is hidden, its sub-album `holiday/2024` with 30 photos is shown as an exception, and 2 photos in `holiday/2024` are hidden themselves
- **THEN** the entry of `holiday` has 40 photos and 28 visible ones, and the entry of `holiday/2024` 30 and 28

#### Scenario: Quarantined photo
- **WHEN** a photo in `holiday` that is not hidden is quarantined
- **THEN** it counts as visible in the entry of `holiday`, as the photo list with the filter for visible photos lists it

#### Scenario: Album with only quarantined photos
- **WHEN** every photo of an album is quarantined
- **THEN** its entry has no cover

### Requirement: Photos without an album in the API
The album list SHALL also give the number of photos and of visible photos that belong to no album. The photo list SHALL be able to select only these photos, with the same visibility filter, sort order and paging as for an album. A request that asks for them and for an album at once SHALL be refused as a bad request.

#### Scenario: Counts without an album
- **WHEN** 3 photos lie directly in the photo folder, one of them hidden
- **THEN** the album list gives 3 photos and 2 visible ones without an album

#### Scenario: List the photos without an album
- **WHEN** the photos without an album are fetched
- **THEN** the response contains only photos that lie directly in the folder of their source, from every source, with their total count

#### Scenario: Without an album and an album at once
- **WHEN** the photos without an album and of the album `holiday` are asked for in one request
- **THEN** the API answers with a bad request and no photos
