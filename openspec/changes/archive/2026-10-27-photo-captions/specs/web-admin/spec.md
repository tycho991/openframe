# Spec Delta

## ADDED Requirements

### Requirement: Edit the comment
The detail view of a photo SHALL show its comment and SHALL let the user write, change and remove it, for local and remote photos alike. It SHALL show how many of the 500 characters are used. A rejected comment SHALL show its error at the field and keep the text the user wrote.

#### Scenario: Write a comment
- **WHEN** the user writes "Grandma's 80th birthday" into the comment field of a photo and saves
- **THEN** the detail view shows the saved comment without reloading, and the frame shows it in the caption

#### Scenario: Remove a comment
- **WHEN** the user empties the comment field and saves
- **THEN** the photo has no comment

#### Scenario: Nextcloud photo
- **WHEN** the user opens the detail view of a photo from Nextcloud
- **THEN** the comment field can be edited

#### Scenario: Text too long
- **WHEN** the user tries to save a comment of more than 500 characters
- **THEN** the error appears at the comment field, nothing is saved and the text stays in the field

### Requirement: Location in the detail view
The detail view SHALL show a photo's place name as the frame names it, in the frame's language, and its coordinates as numbers. It SHALL show no map and SHALL NOT load anything from another server for it. A photo without a position SHALL show no location.

#### Scenario: Photo with position
- **WHEN** the user opens the detail view of a photo taken in Siena on a frame set to German, in an interface in English
- **THEN** it shows "Siena, Italien" and the coordinates 43.3188, 11.3308

#### Scenario: Photo without position
- **WHEN** the user opens the detail view of a photo without GPS data
- **THEN** no location is shown

### Requirement: Attribution of the place data
The interface SHALL name GeoNames as the source of the place names with its licence (CC BY 4.0), next to the place setting. The attribution SHALL be available offline.

#### Scenario: Settings open
- **WHEN** the user opens the caption settings without internet access
- **THEN** the attribution of GeoNames and its licence is shown next to the place setting
