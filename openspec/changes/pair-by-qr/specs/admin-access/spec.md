# Spec Delta

## MODIFIED Requirements

### Requirement: Pairing by code
A client SHALL be able to pair by entering a code that the frame shows. The code SHALL have six digits, be valid for 5 minutes and be usable only once. After successful pairing the client SHALL receive a token. If a code expires while the frame is showing it, the frame SHALL replace it together with the QR code by new ones.

#### Scenario: Successful pairing
- **WHEN** the user chooses "Pair" in the admin interface and enters the code shown on the frame
- **THEN** the browser receives a token and afterwards has access to photos and settings

#### Scenario: Code expired
- **WHEN** a code is entered after more than 5 minutes
- **THEN** pairing is rejected and the frame shows a new code on request

### Requirement: Request code on the frame
A client without a token SHALL be able to ask the frame to show a valid code and QR code. The API response SHALL contain neither the code nor the secret. While pairing by code is locked, the request SHALL succeed and the frame SHALL show the QR code without a code.

#### Scenario: Request code on the frame
- **WHEN** the user chooses "Show code on frame" in the pairing dialog
- **THEN** the frame shows a valid code and QR code for 5 minutes, and the API response contains neither

#### Scenario: Request during the lockout
- **WHEN** pairing by code is locked and the user chooses "Show code on frame"
- **THEN** the API answers with 204 and the frame shows the QR code and a note that the code is locked

### Requirement: Brute-force protection
After 5 wrong codes the frame SHALL discard the current code and reject further pairing attempts by code for 60 seconds. Wrong secrets from QR codes or invitations SHALL NOT count as failed attempts. The lockout SHALL NOT prevent pairing by QR code or by invitation.

#### Scenario: Too many failed attempts
- **WHEN** a wrong code is entered five times
- **THEN** pairing by code answers with 429 for 60 seconds and a new code applies afterwards

#### Scenario: QR code during the lockout
- **WHEN** pairing by code is locked and the user scans the QR code on the frame
- **THEN** the phone is paired

#### Scenario: Wrong secrets
- **WHEN** a client sends a wrong secret ten times
- **THEN** the API answers with 401 each time, and the code on the frame stays valid

### Requirement: Reset all pairings on the frame
On the frame itself there SHALL be a way to revoke all pairings without needing a paired client. The action SHALL be confirmed.

#### Scenario: Access lost
- **WHEN** the user chooses "Revoke all pairings" on the frame and confirms
- **THEN** all tokens, all issued image signatures and all open invitations are invalid, and the frame shows a new pairing code and QR code

## ADDED Requirements

### Requirement: Pairing by QR code
Wherever the frame shows a pairing code, it SHALL also show a QR code. The QR code SHALL contain the address of the admin interface and a secret of at least 128 bits that the browser does not send to the server when it opens the address. The secret SHALL be valid as long as the matching code and usable only once. Redeeming either the code or the secret SHALL invalidate both.

#### Scenario: Scan with the phone
- **WHEN** the user scans the QR code on the frame with the camera app of their phone and opens the link
- **THEN** the browser is paired without any further input and shows the photos

#### Scenario: QR code used twice
- **WHEN** a second phone scans the same QR code after the first one was paired
- **THEN** pairing is rejected and the frame shows a new QR code on request

#### Scenario: Code after a scan
- **WHEN** a phone was paired by QR code and somebody then enters the code shown next to it
- **THEN** pairing is rejected

### Requirement: Pairing by invitation
A paired client SHALL be able to create an invitation that pairs one further device without access to the frame. The invitation SHALL be available as a link and as a QR code, be valid for 5 minutes and be usable only once. At most one invitation per device SHALL be open; a new one SHALL replace the previous one.

#### Scenario: Invite a second phone
- **WHEN** the user chooses "Invite device" on their paired phone and a second phone scans the QR code shown
- **THEN** the second phone is paired without anybody having been at the frame

#### Scenario: New invitation replaces the old one
- **WHEN** the user creates an invitation twice in a row
- **THEN** only the second one is valid

### Requirement: Invitation address
The invitation link SHALL use the address through which the inviting device reaches the frame, so that both devices use the same origin. If that address cannot be determined, the link SHALL use the address the frame shows.

#### Scenario: Same origin
- **WHEN** a device that reaches the frame at `http://192.168.100.215:8080/` creates an invitation
- **THEN** the invitation link starts with `http://192.168.100.215:8080/`

### Requirement: Invitation ends with the inviting device
When the pairing of the inviting device is revoked, its open invitation SHALL become invalid immediately.

#### Scenario: Inviting device revoked
- **WHEN** a device has an open invitation and its pairing is revoked
- **THEN** redeeming the invitation is rejected with 401

### Requirement: Rename paired devices
A paired client SHALL be able to rename any paired device. The name SHALL have 1 to 64 characters and SHALL survive a restart of the frame.

#### Scenario: Rename
- **WHEN** the user renames the device "Safari on iPhone" to "Anna's iPhone"
- **THEN** the list of paired devices shows the new name on all clients, also after the frame restarts
