# Design

## Context

Pairing from `bootstrap-openframe-core` (decision 9) works with a six-digit code that the frame shows. The code proves physical proximity but has to be typed in together with the address. Constraints:

- The web UI runs over `http://` on the LAN, so it is not a secure context. Camera (`getUserMedia`), `navigator.share` and `navigator.clipboard` are not available to it. Scanning therefore happens with the phone's camera app.
- The token lives in `localStorage` and is therefore per origin (`http://IP:port`).
- `PairingManager` holds one code, compares in constant time and publishes a `PairingDisplay` (`code`, `requestedUntil`) as a `StateFlow`. During the lockout it publishes no code, and `requestDisplay()` answers 429.
- The frame shows text in the hint overlay and in the empty state (decision 10, `DisplayActivity`). `DisplayActivity.address()` builds the admin address from `PlatformInfo.ipAddresses()` and `server.boundPort`. `:android` uses only the framework.
- Mutating requests pass the origin check in `HttpPipeline` (`Sec-Fetch-Site`, `Origin` against `Host`).

## Goals / Non-Goals

**Goals:**

- A phone with a camera pairs without any input.
- A further device can be paired from a paired one, without a trip to the frame.
- The code stays as fallback for everything that cannot scan.
- The security model stays: pairing requires proximity to the frame or a paired device.

**Non-Goals:**

- Pairing by request and approval, mDNS/NSD, Home Assistant.
- Control endpoints for the slideshow, changes to gestures and device menu.
- Scanner in the web UI, TLS, a stable address when the IP changes.

## Decisions

### 1. QR content: URL with the secret in the fragment

The QR code contains `http://<IP>:<port>/#pair=<secret>`. The secret is 16 random bytes from `SecureRandom` in Base64url without padding (22 characters). The browser never sends the fragment to the server, so it shows up in no log and no `Referer`. The web UI reads it on start, removes it immediately with `history.replaceState` and then sends it with `POST /api/v1/pairing` as `secret`. Reloading therefore does not try to pair again. If the browser already has a valid token, the web UI discards the secret without redeeming it.

With an IPv4 address and a port the URL has at most 56 characters and fits QR version 4 (33×33 modules) with error correction M.

*Alternative:* Put the six-digit code into the URL. Rejected, because the secret would then stay guessable and the QR path would depend on the lockout after failed attempts.

### 2. One session, two paths, lockout only for the code

`PairingManager` holds a pairing session with code and secret. Both are created together, are valid for 5 minutes and are replaced together. Redeeming either one ends the session. A new one is created as soon as the frame needs it, as the code is today.

The lockout after 5 wrong codes discards only the code. The session's secret stays valid until the session expires, and the frame keeps showing the QR code with "Code locked" in place of the code. When the lockout ends and the frame still needs a code, a new session (new code and new secret) replaces the old one. If the session expires during the lockout, a new one without a code is created. `requestDisplay()` no longer answers 429 during the lockout: it opens the request window, and the frame shows the QR code alone. `PairingDisplay` carries `code` (or `null`), `url` (the pairing URL with the secret), `codeLocked` and `requestedUntil`.

A wrong secret is answered with 401 `invalid_code`, an expired one with `expired_code` (the last expired session's secret is remembered, as the code is today). A wrong secret does not count as a failed attempt: 128 bits cannot be guessed, and this way nobody on the LAN can trigger the lockout through this path. Secrets are compared in constant time like codes (`MessageDigest.isEqual`).

`POST /api/v1/pairing` takes exactly one of `code` (six digits) and `secret` (22 Base64url characters), otherwise 400.

### 3. Invitations

`POST /api/v1/pairing/invites` (with token) creates an invitation and returns `url`, `expiresAt` and `qr`. An invitation is a secret as in decision 1, valid for 5 minutes and only once, and is also redeemed through `POST /api/v1/pairing` with `secret`. Invitations are held in memory only, at most one per paired client. A new one replaces the old one. Revoking the inviting client's pairing drops its invitation, and "Revoke all pairings" drops all. A restart of the frame drops all invitations, which is acceptable for 5-minute links.

The invitation URL uses the `Host` header with which the inviting device made the request, as long as it is a valid host (DNS name, IPv4 address or bracketed IPv6 address) with an optional port. Otherwise it uses the frame's address from decision 5. This puts both devices on the same origin. With a long host name the QR code needs a higher version than 4, which the web UI can display at any size.

Unlike `POST /api/v1/pairing/code`, this endpoint returns the secret to the client. That is acceptable because only a paired device can call it, and that device already has full access.

Validity stays at 5 minutes, like the code. *Alternative:* 24 hours, so that a link sent through a messenger still works when it is opened later. Rejected for now: a long-lived link that pairs a device is a much more valuable thing to leak, and creating a new invitation is cheap. It can be revisited in its own change.

### 4. QR encoder in `:core`, matrix over the API

The QR code is created in `:core` as a module matrix with Nayuki `qrcodegen` (`io.nayuki:qrcodegen`, MIT, plain Java, no dependencies), wrapped in a small `QrMatrix` type. If the library does not pass the Animal Sniffer check against API 21, its single source file is copied in with its license notice. `:android` draws the matrix as a bitmap without smoothing. The API returns the matrix as a list of rows of `0` and `1`, and the web UI draws an SVG of `<rect>` elements from it.

Tests run on the JVM: a decoder on the test classpath only (ZXing) reads the generated matrix back and compares the URL.

*Alternative:* A finished SVG or PNG from the API. Rejected, because the web UI would need `{@html}` or an image with a signature for that, and the matrix is a single source for frame and web UI.

### 5. Admin address in `:core`

The frame's pairing URL has to be built where the secret lives. `FrameCore` therefore gets the address that `DisplayActivity.address()` builds today (first IPv4 address from `PlatformInfo`, bound port, port 80 without suffix), and `PairingManager` receives it as a function. `DisplayActivity` uses the same function for the address text, so text and QR code cannot diverge. Without a network address the frame shows neither address nor QR code, only the code.

### 6. Device name and renaming

When pairing with a secret, the web UI sends no name. The server derives it from the user agent as before. After pairing the web UI shows "Paired as …" once, with "Rename". `PATCH /api/v1/clients/{clientId}` changes the name of any paired device (trimmed, 1 to 64 characters, otherwise 400, unknown ID 404) and is persisted immediately like a pairing. Every paired device may do this, just as it may revoke any pairing. The pairing dialog with the code keeps the optional name field.

### 7. Display on the frame

The QR code is always black on white with a quiet zone of 4 modules, also in the dark theme, because not every camera app reads inverted codes. Its edge is at least 40 % of the short side of the screen, on the Frameo (1024×600) about 240 px, scaled by an integer factor per module. In landscape the QR code sits to the right of name, address, code and pause, in portrait above them. Below it is "Scan with your phone". The texts exist in German and English as string resources.

## Risks / Trade-offs

- **[New IP, new origin]** → The token is lost and bookmarks point nowhere. Scanning again is cheap. The README recommends a DHCP reservation. A stable name comes with mDNS at the earliest.
- **[iOS: a web app on the home screen has its own storage]** → After "Add to Home Screen" the app is not paired. With an invitation from Safari it pairs without a trip to the frame. The README describes this.
- **[QR code is photographed]** → It is valid for 5 minutes at most and only once, the same as the code. Every pairing appears in the device list and can be revoked.
- **[Invitation link is passed on]** → One-time and valid for 5 minutes, and it ends with the inviting device's pairing.
- **[Forged `Host` header]** → Only a paired client can create an invitation, and it would only produce a link to a wrong address for itself.
- **[Reflecting display, old camera apps without QR detection]** → The code stays as fallback. `docs/DEVICE_TEST.md` checks legibility on both devices.
- **[No copying and sharing without a secure context]** → The web UI shows the invitation link as selectable text.

## Migration Plan

No data migration. `POST /api/v1/pairing` with `code` works unchanged, the new fields and endpoints are additions. Stored tokens and clients stay valid. The only changed behavior for existing clients is that `POST /api/v1/pairing/code` answers 204 instead of 429 during the lockout. The web UI handles both.

This change modifies requirements that `bootstrap-openframe-core` adds and is archived after it.
