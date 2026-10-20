# Spec Delta

## Purpose

Defines who may manage the frame over the network. A client is paired with a code that the frame shows on its display. After that it identifies itself with a token. A "local only" mode shuts the network out completely.

## ADDED Requirements

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
A client SHALL be able to pair by entering a code that the frame shows. The code SHALL have six digits, be valid for 5 minutes and be usable only once. After successful pairing the client SHALL receive a token. If a code expires while the frame is showing it, the frame SHALL replace it with a new one.

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
A client without a token SHALL be able to ask the frame to show a valid code. The API response SHALL NOT contain the code.

#### Scenario: Request code on the frame
- **WHEN** the user chooses "Show code on frame" in the pairing dialog
- **THEN** the frame shows a valid code for 5 minutes, and the API response does not contain the code

### Requirement: Brute-force protection
After 5 wrong codes the frame SHALL discard the current code and reject further pairing attempts for 60 seconds.

#### Scenario: Too many failed attempts
- **WHEN** a wrong code is entered five times
- **THEN** pairing answers with 429 for 60 seconds and a new code applies afterwards

### Requirement: Manage tokens
A paired client SHALL be able to see the list of paired devices and revoke individual pairings. A revoked token SHALL become invalid immediately. Tokens SHALL be stored on the frame only as a hash.

#### Scenario: Revoke pairing
- **WHEN** the user revokes the pairing of their old laptop
- **THEN** the old laptop receives 401 on its next request

### Requirement: Reset all pairings on the frame
On the frame itself there SHALL be a way to revoke all pairings without needing a paired client. The action SHALL be confirmed.

#### Scenario: Access lost
- **WHEN** the user chooses "Revoke all pairings" on the frame and confirms
- **THEN** all tokens and all issued image signatures are invalid and the frame shows a new pairing code

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
