# Spec Delta

## MODIFIED Requirements

### Requirement: Photo list through the API
The API SHALL deliver the photos page by page, filterable by album and visibility and sortable by capture date or name. The album filter SHALL include the album's sub-albums unless only the album itself is asked for; an empty album SHALL stand for the photos without an album. Every entry SHALL contain ID, album, visibility, dimensions, capture date, image version and server-signed URLs for the thumbnail and the display size. A library of 10,000 photos SHALL page without noticeable delay.

#### Scenario: Fetch page
- **WHEN** page 2 with 100 entries from the album `holiday` is fetched
- **THEN** the response contains at most 100 photos from `holiday` and its sub-albums, the total count and a link to the next page

#### Scenario: Only the album itself
- **WHEN** the photos of the album `holiday` are fetched without sub-albums
- **THEN** the response contains only photos that lie directly in `holiday/`

#### Scenario: Photos without an album
- **WHEN** the photos of the empty album are fetched without sub-albums
- **THEN** the response contains only photos that lie directly in the photo folder of their source

## ADDED Requirements

### Requirement: Album list through the API
The API SHALL list every album that holds a photo in its branch, with its effective state, whether it differs from its parent, the number of photos and of visible photos in its branch, and a cover: the newest photo of the branch that is not quarantined, none if all are. The list SHALL also give the number of photos and of visible photos without an album.

#### Scenario: Counts of a branch
- **WHEN** `holiday` holds 10 photos and `holiday/2024` 30, and 5 of them are hidden
- **THEN** the entry of `holiday` has 40 photos and 35 visible ones

#### Scenario: Album with only quarantined photos
- **WHEN** every photo of an album is quarantined
- **THEN** its entry has no cover

#### Scenario: Photos without an album
- **WHEN** 3 photos lie directly in the photo folder, one of them hidden
- **THEN** the list gives 3 photos and 2 visible ones without an album
