# admin-access Specification

## Purpose
Defines who may manage the frame over the network. A client is paired with a code that the frame shows on its display. After that it identifies itself with a token. A "local only" mode shuts the network out completely.

## Requirements

### Requirement: Reachability on the LAN
The frame's server SHALL be reachable on the local network on a fixed port (default 8080) as long as local-only mode is off. The address SHALL be readable on the frame.

#### Scenario: Show address
- **WHEN** the user taps the frame
- **THEN** the hint shows the address of the admin interface, e.g. `http://192.168.100.215:8080/`

### Requirement: Protected endpoints
All endpoints that read or change photos or settings SHALL require a valid token. Without a valid token they SHALL answer with 401. Only the frame's info, pairing (including the request to show a code on the frame) and the static files of the admin interface SHALL be freely accessible. Image requests MAY carry a server-issued signature instead of the token, valid for that one photo only and for at most two hours.

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

### Requirement: Pairing by code
A client SHALL be able to pair by entering a code that the frame shows. The code SHALL have six digits, be valid for 5 minutes and be usable only once. After successful pairing the client SHALL receive a token. If a code expires while the frame is showing it, the frame SHALL replace it together with the QR code by new ones.

#### Scenario: Successful pairing
- **WHEN** the user chooses "Pair" in the admin interface and enters the code shown on the frame
- **THEN** the browser receives a token and afterwards has access to photos and settings

#### Scenario: Code expired
- **WHEN** a code is entered after more than 5 minutes
- **THEN** pairing is rejected and the frame shows a new code on request

### Requirement: Device name when pairing
A paired client SHALL appear in the list of paired devices under a device name. The client provides the name; failing that, the name SHALL be derived from the user agent.

#### Scenario: Name from client
- **WHEN** a client pairs with the device name "Laptop"
- **THEN** it appears in the list of paired devices as "Laptop"

#### Scenario: Name from user agent
- **WHEN** a client pairs without a device name
- **THEN** it appears in the list with a name derived from the user agent, e.g. "Firefox on Android"

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

### Requirement: Manage tokens
A paired client SHALL be able to see the list of paired devices and revoke individual pairings. A revoked token SHALL become invalid immediately. Tokens SHALL be stored on the frame only as a hash.

#### Scenario: Revoke pairing
- **WHEN** the user revokes the pairing of their old laptop
- **THEN** the old laptop receives 401 on its next request

### Requirement: Reset all pairings on the frame
On the frame itself there SHALL be a way to revoke all pairings without needing a paired client. The action SHALL be confirmed.

#### Scenario: Access lost
- **WHEN** the user chooses "Revoke all pairings" on the frame and confirms
- **THEN** all tokens, all issued image signatures and all open invitations are invalid, and the frame shows a new pairing code and QR code

### Requirement: Local-only mode
While local-only mode is on, the server SHALL NOT be reachable over the network, only from the device itself. The mode SHALL be stored durably and SHALL be switchable on or off ONLY on the frame itself, not through the API. This way nobody can lock themselves out from the browser.

#### Scenario: Local-only on
- **WHEN** local-only mode is switched on
- **THEN** the port is not reachable from the LAN and the slideshow keeps running

#### Scenario: Turn off on the frame
- **WHEN** the user turns local-only mode off on the frame
- **THEN** the admin interface is reachable from the LAN again

#### Scenario: Via the API
- **WHEN** a paired client tries to set local-only mode through the settings
- **THEN** the API answers with 400 and the mode stays unchanged

### Requirement: Security headers
All responses SHALL set headers that prevent embedding in foreign pages and MIME sniffing. Mutating requests from foreign origins SHALL be rejected.

#### Scenario: Foreign page
- **WHEN** a page on another origin sends a mutating request to the frame from a browser
- **THEN** the server rejects it with 403

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
