## MODIFIED Requirements

### Requirement: Edit photos
In the interface it SHALL be possible to hide, unhide and delete individual or several selected photos. Deleting SHALL be confirmed beforehand, saying that the photos go to the trash for 7 days, where every paired device can see and restore them. A detail view SHALL show the photo larger, as the frame shows it (section, fill mode and black borders in the screen's aspect ratio), with album, capture date and dimensions.

#### Scenario: Hide several
- **WHEN** the user selects three photos and chooses "Hide"
- **THEN** all three are marked as hidden and no longer appear in the slideshow

#### Scenario: Confirm delete
- **WHEN** the user chooses "Delete"
- **THEN** the interface asks for confirmation, saying that the photos stay in the trash for 7 days and that every paired device can see them there, and moves them only afterwards

#### Scenario: Detail view of a portrait photo
- **WHEN** the user opens a portrait photo without a section on a landscape frame in the fill mode `auto`
- **THEN** the detail view shows it in the frame's aspect ratio with the same black borders and the same part as the frame

### Requirement: Paired devices
The interface SHALL list the paired devices and allow revoking a pairing and renaming a device. The current device SHALL be recognizable in the list. For each device the list SHALL show how it paired: with the code, with the QR code, by an invitation of a named device or of a removed device, or that this is unknown.

#### Scenario: Remove old device
- **WHEN** the user chooses "Revoke pairing" for another device
- **THEN** it disappears from the list and the current device stays paired

#### Scenario: Rename device
- **WHEN** the user chooses "Rename" for the current device and saves a new name
- **THEN** the list shows the new name without reloading

#### Scenario: Invited device
- **WHEN** a device was paired by an invitation from the phone "Anna's phone"
- **THEN** the list shows that "Anna's phone" invited it, and after a rename of the phone its new name

#### Scenario: Inviting device removed
- **WHEN** the device that invited another one has been revoked
- **THEN** the list shows that a removed device invited it

#### Scenario: Pairing from before the update
- **WHEN** a device paired before the frame recorded the origin
- **THEN** the list shows that its origin is unknown

### Requirement: Invite a device
The interface SHALL be able to create an invitation from the list of paired devices. It SHALL show the invitation as a QR code and as a selectable link, together with the remaining validity, and SHALL say that the invited device gets the same rights as every paired device, including deleting photos, changing sources and settings and revoking other devices. After expiry it SHALL offer to create a new invitation.

#### Scenario: Show invitation
- **WHEN** the user chooses "Invite device" in the list of paired devices
- **THEN** the interface shows a QR code, the link, the remaining time and the note on the invited device's rights

#### Scenario: Invitation accepted
- **WHEN** another device redeems the invitation shown
- **THEN** it appears in the list of paired devices after 30 seconds at the latest

## ADDED Requirements

### Requirement: Trash in the interface
When the trash holds photos, the photo overview SHALL say so with their number and size and SHALL list them with a thumbnail, name, album, deletion time, the deleting device and the date until which each is kept. A quarantined entry, or one without a thumbnail, SHALL show a placeholder without requesting an image. It SHALL be possible to restore single entries and all of them.

#### Scenario: Restore a deleted photo
- **WHEN** the user deletes a photo, opens the trash in the overview and chooses "Restore" for it
- **THEN** the photo is back in its album in the overview and the trash no longer lists it

#### Scenario: Quarantined entry
- **WHEN** the trash holds a quarantined photo
- **THEN** its row shows the placeholder and the browser requests no image for it

#### Scenario: Restore all
- **WHEN** the trash holds three photos and the user chooses "Restore all"
- **THEN** all three are back in the overview and the trash is no longer shown

### Requirement: Storage full and the trash
When an upload is refused because the storage is full, the interface SHALL say that photos deleted within the last day stay in the trash and that older ones were already removed to make room.

#### Scenario: Upload refused with a recent trash
- **WHEN** an upload is refused with 507
- **THEN** the interface explains the full storage and the role of the trash in its language

### Requirement: Confirm a wider folder
When the frame refuses a change of a source with `account_login_required`, the source card SHALL say that a wider folder needs a Nextcloud login on this device and SHALL offer it. After a successful confirmation login it SHALL save the change again; after a failed one it SHALL show the error and keep the form as it is.

#### Scenario: Whole account confirmed
- **WHEN** the user sets the folder of an account to `/`, saves, chooses to confirm and approves the login as the same Nextcloud user
- **THEN** the card saves the folder `/` and shows it as saved

#### Scenario: Other account
- **WHEN** the confirmation login is approved as another Nextcloud user
- **THEN** the card shows the error for `other_account` in the interface language, and the folder is not saved

### Requirement: Folder of an account explained
At the folder of an account source the interface SHALL say that `/` shows the whole account, that every paired device sees the photos of the folder, that a wider folder needs a Nextcloud login on the device that sets it, and that a share link suits a frame others use.

#### Scenario: Folder field of an account
- **WHEN** the user edits the folder of an account source
- **THEN** the hint below the field says these four things in the interface language
