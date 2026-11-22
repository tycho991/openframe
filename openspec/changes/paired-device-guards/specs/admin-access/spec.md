## MODIFIED Requirements

### Requirement: Protected endpoints
All endpoints that read or change photos or settings SHALL require a valid token. Without a valid token they SHALL answer with 401. Only the frame's info, pairing (including the request to show a code on the frame) and the static files of the admin interface SHALL be freely accessible. Image requests MAY carry a server-issued signature instead of the token, valid for that one photo or that one trash entry only, for at most two hours, and only while the device it was issued to stays paired.

#### Scenario: Without token
- **WHEN** a client without a token fetches the photo list
- **THEN** the API answers with 401

#### Scenario: With token
- **WHEN** a paired client fetches the photo list with its token
- **THEN** the API answers with 200

#### Scenario: Signed image URL
- **WHEN** a browser fetches an image URL from the photo list without a token
- **THEN** the API serves the image as long as the signature is valid

#### Scenario: Signature reused for another photo
- **WHEN** a client attaches the signature of one photo to the URL of another photo
- **THEN** the API answers with 401

#### Scenario: Signature after the device was removed
- **WHEN** a device is removed under "Paired devices" and one of its image URLs is fetched again within the two hours
- **THEN** the API answers with 401

#### Scenario: Signed thumbnail of a trash entry
- **WHEN** a browser fetches the thumbnail URL of a trash entry without a token, and later the same signature with the ID of another trash entry
- **THEN** the API serves the first while the signature is valid and answers the second with 401

## ADDED Requirements

### Requirement: Equal rights of paired devices
Every paired device SHALL have the same rights through the API, whether it paired with the code, the QR code or an invitation, including renaming, revoking and inviting other devices. The only limits SHALL be the trash for deleted local photos and the login needed to widen the folder of an account (`remote-sources`). "Reset all pairings on the frame" SHALL stay the remedy against a hostile paired device.

#### Scenario: Invited device revokes another one
- **WHEN** a device paired by invitation revokes the pairing of the device that invited it
- **THEN** the revoked device receives 401 on its next request, and the log names both devices

#### Scenario: A hostile device keeps revoking
- **WHEN** a paired device revokes every new pairing as soon as it appears
- **THEN** "Revoke all pairings" on the frame invalidates its token as well, and the next pairing stays

### Requirement: Origin of a pairing
Each pairing SHALL record whether it was made with the code shown on the frame, with the frame's QR code or with an invitation, and for an invitation the ID of the inviting device, also after that device is revoked. The list of paired devices SHALL give both. A pairing made before this was recorded, or with a value this version does not know, SHALL be listed without an origin, and the list of paired devices SHALL stay readable.

#### Scenario: Paired with the code
- **WHEN** a device pairs with the code shown on the frame
- **THEN** the list of paired devices gives the origin "code" and no inviting device for it, also after a restart

#### Scenario: Paired by invitation
- **WHEN** a phone creates an invitation and a tablet redeems it, and the phone is revoked later
- **THEN** the list gives the tablet the origin "invitation" and the phone's ID as the inviting device

#### Scenario: Pairing from before the update
- **WHEN** the frame is updated with devices that paired before
- **THEN** they stay paired and are listed without an origin

#### Scenario: Origin from a newer version
- **WHEN** the stored list of paired devices holds an origin value that this version does not know
- **THEN** every device stays paired, and that device is listed without an origin
