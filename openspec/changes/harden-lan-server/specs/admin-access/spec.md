## ADDED Requirements

### Requirement: Host check against DNS rebinding
The frame SHALL answer a request only if its `Host` is an IP literal, `localhost`, a single-label name, or a name under `.local`, `.home.arpa`, `.localdomain`, `.intranet`, `.internal`, `.private`, `.corp`, `.home`, `.lan` or `.fritz.box`, with or without port. Any other host SHALL get 421 `misdirected_host`, for the API and the web UI alike, without resolving the name. A request without `Host` SHALL stay allowed.

#### Scenario: Rebinding page
- **WHEN** a website on `rebind.example` resolves its own name to the frame's address and its page fetches `/api/v1/info` or posts to `/api/v1/pairing/code`
- **THEN** the frame answers 421 and neither returns its info nor shows a code

#### Scenario: Local names
- **WHEN** a browser opens the frame as `http://192.168.100.215:8080/`, `http://frame.local:8080/`, `http://frame.fritz.box:8080/` or `http://[fe80::1]:8080/`
- **THEN** the frame serves the web UI and the API as before

#### Scenario: Look-alike names
- **WHEN** a request carries the host `evil.com.local.evil.com` or `1.2.3.4.evil.com`
- **THEN** the frame answers 421

### Requirement: Limits for connections and request times
The frame SHALL hold at most 48 connections at once and at most 16 from one address; when full, it SHALL close an idle kept-alive connection before refusing a new one. A request's headers SHALL be complete within 10 seconds of its first byte, else the frame SHALL close the connection. A JSON body SHALL arrive within 10 seconds, else the frame SHALL answer 408 `request_timeout` and close the connection.

#### Scenario: Flood from one device
- **WHEN** one device on the LAN opens 200 connections and sends their requests byte by byte
- **THEN** another device still uses the web UI with answers within a second, the slideshow keeps running and the app does not restart

#### Scenario: Slow body
- **WHEN** a client announces a JSON body for `POST /api/v1/pairing` and sends it slower than the deadline
- **THEN** the frame answers 408 `request_timeout` after 10 seconds, without an error in the log

#### Scenario: Many idle connections
- **WHEN** a browser keeps 16 idle connections to the frame open and sends one more request
- **THEN** the frame answers that request

### Requirement: Size of request bodies without token
The body of `POST /pairing` SHALL be limited to 4 KiB; a larger body SHALL get 413. A slow body of an endpoint without token SHALL NOT hold one of the frame's request handlers while it arrives.

#### Scenario: Oversized pairing body
- **WHEN** a client posts a 10 KiB body to `/api/v1/pairing`
- **THEN** the frame answers 413

#### Scenario: Slow bodies and handlers
- **WHEN** ten clients send their pairing bodies slowly at the same time
- **THEN** `GET /api/v1/info` and the web UI answer within a second meanwhile

### Requirement: Code only while shown on the frame
The frame SHALL create a pairing code only while it visibly shows the pairing hint or the empty state and its display is on, not during a dark wake. A request to show a code while the display is off SHALL create none until then. A code once shown SHALL stay valid for its 5 minutes, also after the hint was hidden.

#### Scenario: Display off
- **WHEN** the display is off by the schedule and a client posts to `/api/v1/pairing/code`
- **THEN** the API answers 204, the display stays dark and no code exists that a client could guess

#### Scenario: Code entered after the hint was hidden
- **WHEN** the frame showed a code and its hint was hidden again a minute later
- **THEN** entering that code within its 5 minutes pairs the device

### Requirement: Daily budget for pairing by code
After 20 wrong codes within 24 hours the frame SHALL pause pairing by code until the oldest of these attempts is 24 hours old, a device pairs, or all pairings are reset on the frame. Meanwhile `POST /pairing` with a code SHALL answer 429 `code_paused` with `Retry-After`. Pairing by QR code and by invitation SHALL keep working.

#### Scenario: Budget used up
- **WHEN** wrong codes were entered 20 times within 24 hours and a client sends another code
- **THEN** the API answers 429 `code_paused` with `Retry-After`, and the frame shows the QR code without a code

#### Scenario: QR code while paused
- **WHEN** pairing by code is paused and the user scans the QR code on the frame
- **THEN** the phone is paired, and pairing by code works again afterwards

### Requirement: Pairing deadlines independent of the wall clock
The validity of code, secret and invitation, the lockout, the daily budget and the window of a request to show a code SHALL run on time that does not jump with the device's clock. Times the API reports, such as an invitation's `expiresAt` and `Retry-After`, SHALL be the current time plus the time left.

#### Scenario: Clock set back
- **WHEN** pairing by code is locked and the device's clock is set back by a day
- **THEN** the lockout still ends after its remaining seconds

#### Scenario: Clock set forward
- **WHEN** an invitation was created and the device's clock jumps a day ahead
- **THEN** the invitation stays valid for the rest of its 5 minutes

### Requirement: Malformed JSON bodies
A JSON body nested deeper than 64 levels, or one that is not valid JSON, SHALL get 400 `invalid_json` without an error entry in the log. Error messages for invalid bodies SHALL name at most the position of the error or the missing fields; they SHALL NOT repeat the input or name internal classes.

#### Scenario: Deep nesting
- **WHEN** a paired client sends 100,000 opening brackets to an endpoint that takes JSON
- **THEN** the API answers 400 `invalid_json` and the log has no error entry

#### Scenario: Broken JSON
- **WHEN** a client sends a body that is not valid JSON
- **THEN** the message names the position of the error and does not contain the body

## MODIFIED Requirements

### Requirement: Protected endpoints
All endpoints that read or change photos or settings SHALL require a valid token. Without a valid token they SHALL answer with 401. Only the frame's info, pairing (including the request to show a code on the frame) and the static files of the admin interface SHALL be freely accessible. Image requests MAY carry a server-issued signature instead of the token, valid for that one photo only, for at most two hours, and only while the device it was issued to stays paired.

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

### Requirement: Request code on the frame
A client without a token SHALL be able to ask the frame to show a valid code and QR code. The API response SHALL contain neither the code nor the secret. While pairing by code is locked or paused, the request SHALL succeed and the frame SHALL show the QR code without a code.

#### Scenario: Request code on the frame
- **WHEN** the user chooses "Show code on frame" in the pairing dialog
- **THEN** the frame shows a valid code and QR code for 5 minutes, and the API response contains neither

#### Scenario: Request during the lockout
- **WHEN** pairing by code is locked and the user chooses "Show code on frame"
- **THEN** the API answers with 204 and the frame shows the QR code and a note that the code is locked

#### Scenario: Request while the display is off
- **WHEN** the display is off and the user chooses "Show code on frame"
- **THEN** the API answers with 204, and a code appears only once the display is on and shows the hint

### Requirement: Brute-force protection
After 5 wrong codes the frame SHALL discard the current code and reject further pairing attempts by code, for 60 seconds at the first lockout and twice as long at each further one, at most 1 hour. A successful pairing or resetting all pairings on the frame SHALL return the lockout to 60 seconds. Wrong secrets from QR codes or invitations SHALL NOT count as failed attempts. The lockout SHALL NOT prevent pairing by QR code or by invitation.

#### Scenario: Too many failed attempts
- **WHEN** a wrong code is entered five times
- **THEN** pairing by code answers with 429 for 60 seconds and a new code applies afterwards

#### Scenario: Growing lockout
- **WHEN** after the first lockout another five wrong codes are entered, and again after that one
- **THEN** pairing by code is locked for 120 and then 240 seconds, and no lockout lasts longer than 1 hour

#### Scenario: Lockout back to 60 seconds
- **WHEN** a device pairs after a lockout of 240 seconds and later five wrong codes are entered
- **THEN** pairing by code is locked for 60 seconds

#### Scenario: QR code during the lockout
- **WHEN** pairing by code is locked and the user scans the QR code on the frame
- **THEN** the phone is paired

#### Scenario: Wrong secrets
- **WHEN** a client sends a wrong secret ten times
- **THEN** the API answers with 401 each time, and the code on the frame stays valid

### Requirement: Invitation address
The invitation link SHALL use the address through which the inviting device reaches the frame, so that both devices use the same origin. If that address cannot be determined, the link SHALL use the address the frame shows. If the frame knows no address either, creating the invitation SHALL fail with 503 `no_address`.

#### Scenario: Same origin
- **WHEN** a device that reaches the frame at `http://192.168.100.215:8080/` creates an invitation
- **THEN** the invitation link starts with `http://192.168.100.215:8080/`

#### Scenario: No address known
- **WHEN** a request without `Host` creates an invitation while the frame has no network address
- **THEN** the API answers 503 `no_address`
