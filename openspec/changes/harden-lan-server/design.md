# Design

## Context

See proposal.md for the motivation. The code as of `main` after 0.8.1 (checked against the audit package P5 of
October 2026):

- **Transport.** `http/NanoHttpServer.kt` runs NanoHTTPD 2.3.1 with its default async runner: one unbounded thread per
  connection, a 30 s read timeout per `read()`, no connection cap. Handlers run on
  `Dispatchers.IO.limitedParallelism(8)` (`FrameCore`). A handler reads its JSON body itself, up to 1 MiB, so a slow
  public body holds one of the 8 slots. Draining an unread body allows 2 s per `read()`, without an overall limit.
  `ConnectionInput` sets the timeouts through a ThreadLocal.
- **Origin.** `http/HttpPipeline.kt` checks `Sec-Fetch-Site` and `Origin` against `Host`, for mutating requests only.
  A rebinding page has `Origin == Host`, so it passes. GET requests and the static web UI are not checked at all.
  `PairingRoutes.adminUrlFromHost` takes any syntactically valid `Host` for the invitation link.
- **Pairing.** `auth/PairingManager.kt`:
  - It creates a code whenever one is needed, whether or not the frame shows it.
  - 5 wrong codes lock it for a fixed 60 s, then a new code is made, without end.
  - All deadlines are `Instant`s from the wall clock.
  - `completeSession` calls `clients.create()` inside the `@Synchronized` monitor, and `create()` fsyncs.
  - `setShownOnFrame` comes from `DisplayActivity`.
  - The display state (`DisplayController.state.on`, `dark`) is available in `FrameCore`.
- **Image signatures.** `auth/ImageSigner.kt` builds `t = <expiry>.<HMAC(key, "photoId.expiry")>`. The expiry is the
  full hour after next, so URLs stay stable within an hour for the browser cache. The key rotates only on "Unpair all
  devices". `api/PhotoDtos.kt` signs the URLs for the library and slideshow routes. `AccessControl` verifies without
  looking at the client.
- **JSON.** `http/Http.kt` passes kotlinx messages through, which echo the input ("JSON input: …") and name serial
  classes. Deep nesting can overflow the small ART thread stacks.
- **Constraints.** No Java 9+ APIs in `:core` (Animal Sniffer against API 21), so no `InetAddress.ofLiteral`, and no DNS
  lookups for the Host check. `api/openapi.yaml` comes first, and every response is checked against it in tests.
  `errorCodes.test.ts` requires a text in `errorCodes.ts`, `en.json` and `de.json` for every error code of `:core`.

## Goals / Non-Goals

**Goals:**

- Keep the frame usable from other devices while one device floods it, and never let the process die from that.
- Make the unauthenticated surface unusable for a website through DNS rebinding.
- Bound guessing of the six-digit code to 20 attempts a day, and only while someone can see the code on the frame.
- Keep every existing client working: the web UI, Home Assistant through its configured host, curl.

**Non-Goals:**

- Several attackers on several LAN addresses filling all 48 slots, or slow reads: this is accepted and documented
  (decision 9).
- Accepting connections only on WLAN, Ethernet and tethering addresses, Range/HEAD and JSON 500 from the transport,
  format and header checks in the contract harness, and a log of device management. These are the rest of P5 and
  come later through the updater.
- Roles or rights per device (audit package P6).

## Decisions

1. **Bounded async runner, not a new server library.** A `BoundedAsyncRunner` is set with `setAsyncRunner()`:
   - It allows 48 connections in total and 16 per remote IP.
   - It takes the IP from the accepted socket, never from NanoHTTPD's `remote-addr` header, which a client can
     overwrite.
   - When a limit is reached, it closes an idle kept-alive connection of that IP, else the oldest idle one, else the
     new connection.
   - `closed()` unregisters, because `ClientHandler.run` calls it in `finally`.
   - `exec()` catches a `Throwable` from `Thread.start` (out of memory) and closes the connection.

   A `ServerSocketFactory` returns a socket whose `accept()` pauses 100 ms after an `IOException`, unless it is closed.
   Replacing NanoHTTPD was considered and rejected: it is a large change for one package, and the Range and gzip
   behaviour the web UI relies on would have to be rebuilt.
2. **Deadlines as phases of `ConnectionInput`.** The phases are IDLE (30 s per read, as today), HEADER (10 s in total
   from the first byte), BODY (10 s for JSON; for multipart only the read timeout) and DRAIN (2 s in total). Before each
   `read()` the socket timeout is set to the smaller of the phase's base and its remaining time, in monotonic time.
   `serve()` passes the `ConnectionInput` on explicitly instead of through the ThreadLocal, because the handler reads
   on a dispatcher thread. Limits and deadlines are constructor parameters of `NanoHttpServer` and `FrameServer`, so
   tests can shorten them.
3. **Read small JSON bodies on the connection thread.** `serve()` reads JSON bodies up to 64 KiB into memory before
   `withContext(dispatcher)`, so slow bodies do not occupy handler slots. Larger bodies are possible only after
   `authorize()` and keep the 10 s deadline in the handler. A timeout while reading a JSON body becomes 408
   `request_timeout` with the security headers and `closeConnection(true)`; today it ends as 500 with an ERROR entry.
   `POST /pairing` reads at most 4 KiB.
4. **Host allowlist without DNS.** `isLocalHost(host)` in `http/` is the first step of `HttpPipeline.dispatch`, before
   `checkOrigin`, the router and the static fallback.
   - It parses IPv4 dotted notation itself, accepts IPv6 literals in brackets, compares case-insensitively and drops a
     trailing dot.
   - The allowed names are those of the owner's decision of 29 Oct 2026: IP literals, `localhost`, single-label names,
     `.local`, `.home.arpa`, `.localdomain`, RFC 6762 appendix G and `.fritz.box`.
   - `.fritz.box` stays although `.box` is a public TLD. FRITZ!Box networks reach devices as `<name>.fritz.box`, and the
     router's own rebind protection exempts that name.
   - A missing `Host` is allowed, because a rebinding browser always sends one.
   - `adminUrlFromHost` uses the same check.
   - 421 is added to NanoHTTPD's status reasons.

   Resolving the name and comparing addresses was rejected: the lookup is the very thing an attacker controls.
5. **Visibility comes from the core, not the activity.** `PairingManager` gets `setScreenUsable(Boolean)`, which
   `FrameCore` feeds from `display.state.on && !display.dark`. A code is created only when `shownOnFrame &&
   screenUsable` holds, it is not locked or paused, and no code exists. `requestDisplay()` alone opens the request
   window and creates at most a secret, which has 128 bits and cannot be guessed. The rule is testable on the JVM and
   does not depend on the activity life cycle of Android 5 and 6. `DisplayActivity` stays as it is.
6. **Growing lockout and a rolling budget, in memory.** The lockout lasts 60 s, then doubles per further lockout up to
   3600 s. A successful pairing of any kind and `reset()` return it to 60 s. The budget keeps the monotonic times of
   wrong codes within 24 h; at 20 it answers 429 `code_paused`, with `Retry-After` until the oldest leaves the window.
   - Both live only in memory: a restart resets them. Without a token, a client cannot restart the process at will once
     the transport limits are in place.
   - Persisting them was rejected: it would add a store write to every wrong code.
   - A LAN attacker can keep pairing by code paused for up to 24 h. That is deliberate, because QR code and invitation
     stay.
   - `locked` keeps its text "Try again in N s." and its `Retry-After`, because the web UI's pairing dialog reads the
     seconds from the text.
7. **Monotonic deadlines and creating outside the monitor.** All deadlines in `PairingManager` use
   `clock.monotonicMillis()`, and the API reports wall time as now plus the time left. `PairingDisplay.requestedUntil`
   becomes a boolean. `pair()` and `pairWithSecret()` check and consume the session or invitation inside a
   `synchronized` block; `clients.create()` and the log run after it. This change comes first, because decisions 5 and
   6 build on it.
8. **The client in the image signature.** `sign(photoId, clientId)` gives `t = <expiry>.<clientId>.<mac>` over
   `photoId.clientId.expiry`. Client IDs are 16 hex characters without dots.
   - `verify` returns the client ID. `AccessControl` also requires that the client is still in the registry, which is a
     lookup in memory.
   - `PhotoDtos` takes the client of the request; all its callers run with a token.
   - URLs stay stable per device and hour, so the browser cache keeps working. The client ID is not a secret: every
     paired device sees it in `GET /clients`, and the referrer policy is `no-referrer`.
   - Rotating the key at every single revocation was rejected, because it would break the images in every other open
     tab.
9. **Short JSON errors.** Before parsing, `bodyJson` scans linearly and counts `[` and `{` outside strings, with escapes,
   rejecting more than 64 levels. A `StackOverflowError` at that point also becomes 400. The message becomes "Not valid
   JSON at offset N", with the offset taken by regex from the kotlinx message, because its exception type is internal.
   `bodyAs` answers "Invalid body" plus the missing fields from `MissingFieldException`. The log half of the audit item
   is already done (crash-resilience: 30 frames, 8 K characters per entry).
10. **Contract first, and a small consistency test.**
    - `api/openapi.yaml` gets shared responses `MisdirectedHost` (421) and `InternalError` (500) on every operation,
      `RequestTimeout` (408) on every operation with a JSON request body, 503 `no_address` on `POST /pairing/invites`,
      `code_paused` in the 429 of `POST /pairing`, and `Retry-After` required in `Locked`.
    - A Kotlin test reads the contract and requires 421 and 500 everywhere and 408 on JSON operations, so new endpoints
      cannot forget them.
    - The stricter harness of the audit (formats, required headers, request bodies) is not part of this.
11. **Threat model.** The bootstrap design (decision 9) assumed a trusted LAN. That assumption now widens to websites
    on the internet that reach the frame through the user's browser, and to hostile devices on the LAN.
    - `SECURITY.md` and the README security model name what is protected and the residual risks: several attacker
      addresses or slow reads can block the web UI while the slideshow keeps running, and the 24 h pause of pairing by
      code.
    - `appVersion` and `androidVersion` stay public in `GET /info`. The build is readable from the hashed asset names
      in `index.html` anyway, and `frame-settings` › Frame identity requires them without pairing (the owner's decision
      of 29 Oct 2026).

## Risks / Trade-offs

- [A user reaches the frame through a DNS name under a public domain, such as split DNS or a Pi-hole name] → The 421
  message tells them to use the frame's IP address, which the frame shows. The allowlist follows RFC 6762 appendix G.
  Check that the owner's Home Assistant entries use an IP or an allowed name before the release (read only).
- [16 connections per address are too few for a client that opens many, such as a development proxy] → Idle
  kept-alive connections are closed first. The e2e photo overview and the Home Assistant card run against the limit
  in tests or on the device.
- [NanoHTTPD 2.3.1 internals (async runner, `closed()` in `finally`, server socket factory) are known only from its
  bytecode] → Raw-socket tests on loopback cover every assumption. The flood comes from `127.0.0.2` and the probe from
  `127.0.0.1`, since all of 127/8 is on `lo` under Linux.
- [The image signature format changes] → Image URLs issued before the update get 401 once, until the page reloads its
  list. The web UI already reloads on 401 of a list request, and images recover on the next poll.
- [Code only while shown breaks automated pairing] → The control endpoints `/code` and `/qr` of the e2e test server set
  the hint as shown, and its fake display is on.
- [A JSON body up to 64 KiB that is announced but never sent now waits 10 s for 408] → This only affects broken
  clients. The existing test with an announced 1 MB body stays green, because it is above 64 KiB.
- [New error codes without texts] → Each task that adds a code also adds its texts, so `errorCodes.test.ts` stays
  green.

## Migration Plan

- The change ships as a normal release and reaches both frames through the updater, confirmed on the frame.
- Rolling back means installing the previous release by hand. The updater refuses downgrades, and settings and
  pairings stay compatible because nothing persistent changes format.
- Tokens stay valid. Only image signatures change.
- Device rows in `../openframe-private/DEVICE_TEST.md` run after the release, each with the owner's consent:
  - a flood from a computer on the LAN while the web UI is used from a phone;
  - a request to show a code while the display is off;
  - entering a code after the hint has been hidden.
