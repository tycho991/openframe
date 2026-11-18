# Proposal

## Why

The frame's HTTP server is reachable without a token by anything on the home network, and since the repository went
public its weaknesses are public too. The audit of October 2026 (package P5) found that one device on the LAN can
exhaust the server with slow or many connections. A website can read and drive the unauthenticated endpoints through
DNS rebinding. The six-digit pairing code can be guessed at 5 tries a minute without end. Image URLs stay valid after
their device was unpaired. The core of that package was meant to land before the first updater release and did not;
this change brings it now, delivered to the frames through the updater.

## What Changes

- **Connection limits and deadlines.** The server accepts at most 48 connections, 16 per remote address, and drops
  idle keep-alive connections first when full. Headers must arrive within 10 s of the first byte, else the connection is
  closed; JSON bodies within 10 s, else 408 `request_timeout`. Draining an unread body gets one overall deadline. Small JSON bodies are read
  before a handler slot is taken. `POST /pairing` accepts at most 4 KiB. A failing `accept()` backs off instead of
  spinning.
- **Host check against DNS rebinding.** Every request, the web UI included, whose `Host` is not an IP literal,
  `localhost`, a single-label name or a local suffix gets 421 `misdirected_host`. The local suffixes are `.local`,
  `.home.arpa`, `.localdomain`, the names of RFC 6762 appendix G and `.fritz.box`. A missing `Host` stays allowed.
  Invitation links take only an allowed host.
- **Pairing by code, harder to guess.**
  - A code exists only while the frame visibly shows the hint and the display is on, not in a dark wake.
  - The lockout after 5 wrong codes doubles from 60 s up to 1 h.
  - After 20 wrong codes in 24 h, pairing by code pauses with 429 `code_paused`.
  - QR code and invitation always keep working.
- **Pairing deadlines on monotonic time.** Code, secret, invitation, lockout, budget and request window no longer move
  when the wall clock jumps. Creating the client is moved out of the pairing lock.
- **Image signatures bound to the device.** A signature names the client it was issued to and stops working as soon as
  that client is revoked. **BREAKING** for open browser tabs: image URLs issued before the update get 401 once, until
  the list reloads.
- **Malformed JSON.**
  - Nesting deeper than 64 levels gets 400 `invalid_json` without an ERROR log entry.
  - Error messages no longer echo the input or name internal classes.
- **Contract.**
  - 421 and 500 on every operation, and 408 on every operation with a JSON body.
  - 503 `no_address` on `POST /pairing/invites` instead of an unhandled exception.
  - `code_paused` at `POST /pairing`, and `Retry-After` required on 429.
  - The web UI gets texts for the new error codes.
- **Threat model.** design.md and SECURITY.md add websites on the internet (DNS rebinding) and hostile LAN devices, name
  the residual risks, and record why `appVersion` and `androidVersion` stay public in `GET /info`.

Not in this change: accepting connections only on the frame's local addresses (no mobile data or VPN), Range/HEAD
fixes and JSON 500 from the transport, the stricter contract harness (formats, headers, request bodies), and a log of
device management. They follow through the updater as their own change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-access`:
  - Brute-force protection grows its lockout. Request code on the frame depends on visibility. Protected endpoints bind
    image signatures to the device. The invitation address answers 503 when the frame knows no address.
  - New requirements: Host check against DNS rebinding, limits for connections and request times, size of request
    bodies without token, code only while shown on the frame, daily budget for pairing by code, pairing deadlines
    independent of the wall clock, malformed JSON bodies.
- `slideshow-display`: Display of the pairing QR code names the pause after the daily budget besides the lockout.

## Impact

- `:core`:
  - Transport: `http/NanoHttpServer.kt` (async runner, server socket factory, `ConnectionInput` phases), `http/Http.kt`
    (JSON depth, messages, 408), `http/HttpPipeline.kt` (Host check).
  - A new `http/LocalHost.kt`.
  - Pairing: `auth/PairingManager.kt` (visibility, lockout, budget, monotonic time), `auth/ImageSigner.kt`,
    `auth/AccessControl.kt`, `api/PairingRoutes.kt`.
  - Photo DTOs and their callers in library and slideshow routes, and `FrameCore.kt` (screen usable from the display
    state).
- `:android`: no code change expected. Tests run on the JVM; the frames get the change as a release through the
  updater.
- `api/openapi.yaml`: new responses (408, 421, 500, 503), new codes (`request_timeout`, `misdirected_host`,
  `code_paused`, `no_address`), `Retry-After` required.
- `webadmin/`: texts for the new codes in `errorCodes.ts`, `en.json`, `de.json`; regenerated API types.
- Docs: `SECURITY.md`, README security model.
- Clients: Home Assistant (ha-openframe) reaches the frame through its configured host. An IP address or a `.local` or
  `.fritz.box` name keeps working. A custom DNS name under a public domain gets 421.
