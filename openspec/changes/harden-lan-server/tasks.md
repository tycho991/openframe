# Tasks

## 1. Contract and error texts (no change in behaviour except `no_address`)

- [x] 1.1 `api/openapi.yaml` per design decision 10:
  - shared responses `MisdirectedHost` (421) and `InternalError` (500) on every operation;
  - `RequestTimeout` (408) on every operation with a JSON request body;
  - 503 `no_address` on `POST /pairing/invites`;
  - `code_paused` in the 429 description of `POST /pairing`;
  - `Retry-After` required in `Locked`.

  Verify: `npm run lint` in `webadmin/` (Redocly) is clean, and `npm run gen:api` regenerates the types without
  typecheck errors.
- [x] 1.2 Contract consistency test in `core/src/test`: it reads `api/openapi.yaml` and requires 421 and 500 on every
  operation and 408 on every operation with a JSON request body. Verify: the test fails on a copy of the contract
  without one of them and passes on 1.1. `RouteAccessTest` stays green.
- [x] 1.3 `POST /pairing/invites` throws `HttpException(503, "no_address")` instead of `IllegalStateException` when the
  frame knows no address. Verify: an `InviteTest` case with an HTTP/1.0 request without `Host` over a raw socket, the
  frame without an address, gets 503 `no_address` with the contract check on.
- [x] 1.4 Texts for `request_timeout`, `misdirected_host` (with the hint to use the IP address the frame shows),
  `code_paused` and `no_address` in `webadmin/src/lib/i18n/errorCodes.ts`, `en.json` and `de.json`. Verify:
  `npm test` (`errorCodes.test.ts`) and `npm run typecheck` are green.

## 2. Pairing on monotonic time

- [ ] 2.1 `PairingManager` per design decision 7: expiry of session and invitation, `lockedUntil` and the request window
  on `clock.monotonicMillis()`; `CreatedInvite.expiresAt` and `Retry-After` as now plus the time left;
  `PairingDisplay.requestedUntil` becomes `requested: Boolean` (and `DisplayActivity`'s null check becomes that
  boolean). Verify: `PairingTest` and `InviteTest` with `TestClock` cover `admin-access` › Pairing deadlines
  independent of the wall clock › Clock set back and › Clock set forward, plus jumps during a session and during the
  request window. `./gradlew :android:lint` is green.
- [ ] 2.2 `pair()` and `pairWithSecret()` check and consume inside a `synchronized` block; `clients.create()` and the log
  run outside it. Verify: a new test with a `JsonStore` whose write blocks on a latch shows that `setShownOnFrame()`
  and `tick()` return at once while `create()` waits. The existing pairing tests stay green.

## 3. Pairing by code: visibility, lockout, budget

- [ ] 3.1 `setScreenUsable(Boolean)` fed by `FrameCore` from the display state per design decision 5. A code only when
  shown and usable, `requestDisplay()` creating at most a secret, and a code staying valid after the hint is hidden.
  The e2e test server's `/code` and `/qr` set the hint as shown with its fake display on. Verify:
  - `PairingTest` covers `admin-access` › Code only while shown on the frame › Display off, with the secret unchanged
    over 10 ticks.
  - It also covers › Code entered after the hint was hidden, and › Request code on the frame › Request while the
    display is off.
  - A `FrameCore` test with `FakeDisplayPower` covers display off and dark wake.
  - `npm run e2e` (pairing specs) stays green.
- [ ] 3.2 Growing lockout per design decision 6 (60, 120, 240 … up to 3600 s; back to 60 s after a pairing or
  `reset()`), keeping the text "Try again in N s." Verify: `PairingTest` covers `admin-access` › Brute-force
  protection › Growing lockout, › Lockout back to 60 seconds, and the cap at 3600 s over several virtual days.
- [ ] 3.3 Rolling budget of 20 wrong codes in 24 h with 429 `code_paused` and `Retry-After`; `codeLocked` on the frame
  while paused. Verify:
  - `PairingTest` covers `admin-access` › Daily budget for pairing by code › Budget used up and › QR code while
    paused, and a simulation over 24 virtual hours admits at most 20 code attempts.
  - An API test with `TestFrame` and the contract check sees 429 `code_paused` with `Retry-After`.
  - `slideshow-display` › Display of the pairing QR code › Code paused is checked through `PairingDisplay.codeLocked`.
- [ ] 3.4 Pairing dialog in the web UI: `code_paused` shows its text (the default branch with the error text) and no
  countdown. Verify: a component or unit test of the dialog's error mapping, and `npm run lint` is green.

## 4. Host check

- [ ] 4.1 `isLocalHost(host)` in `core/.../http/` per design decision 4, without DNS and without Java 9+ APIs. Verify:
  - Unit tests for every scenario of `admin-access` › Host check against DNS rebinding, including trailing dots,
    upper case, ports 0 and 65536 (rejected), `[::1]` and `frame.fritz.box`.
  - Test addresses follow the neutral convention (`192.168.100.x`).
  - `./gradlew :core:check` (Animal Sniffer) is green.
- [ ] 4.2 The check at the start of `HttpPipeline.dispatch`, 421 `misdirected_host` with the security headers, 421 in
  the status reasons, and `adminUrlFromHost` using the same check. Verify:
  - `SecurityTest` with `Host` and `Origin` set to `rebind.example:8080`: `GET /api/v1/info`, `POST /api/v1/pairing`,
    `POST /api/v1/pairing/code` and `GET /` give 421.
  - The allowed hosts get their normal answers.
  - A raw-socket request without `Host` works.
  - `InviteTest` "Invalid Host" now expects 421, and the other invite cases stay green.

## 5. Connection limits and deadlines

- [ ] 5.1 `BoundedAsyncRunner` and the backing-off server socket per design decision 1, limits as constructor
  parameters. Verify: a new `ServerLimitsTest` with short injected deadlines and the flood from `127.0.0.2`:
  - 200 connections at slowloris pace leave `GET /api/v1/info` from `127.0.0.1` answering within 1 s, and the thread
    count stays below 48 plus a small constant;
  - 16 idle kept-alive connections of one address let a 17th request of that address through;
  - a simulated out-of-memory in `exec()` keeps the listener accepting;
  - an `IOException` from `accept()` backs off instead of spinning.
- [ ] 5.2 `ConnectionInput` phases with monotonic deadlines per design decision 2, passed on explicitly instead of the
  ThreadLocal. Verify in `ServerLimitsTest`: a header sent byte by byte is closed after the header deadline, and a
  drain that trickles ends after its overall deadline. The existing test "A body that is announced but not sent"
  stays green.
- [ ] 5.3 JSON bodies up to 64 KiB read before the dispatcher, 408 `request_timeout` on a slow JSON body, 4 KiB limit for
  `POST /pairing` per design decision 3. Verify:
  - `admin-access` › Limits for connections and request times › Slow body ends with 408 and the security headers,
    without an ERROR in `GET /api/v1/logs`.
  - › Size of request bodies without token › Oversized pairing body gives 413.
  - › Slow bodies and handlers: `/info` and `/` answer within 1 s.
  - All with the contract check on.

## 6. Malformed JSON

- [ ] 6.1 Depth scan, `StackOverflowError` mapping and short messages in `Http.kt` per design decision 9. Verify: tests
  for `admin-access` › Malformed JSON bodies › Deep nesting (100,000 `[` at a JSON route with token: 400, no ERROR
  in the log) and › Broken JSON (a one-line message with the offset, no echo). Also:
  - 64 levels still parse;
  - 4,000 `[` at `/pairing` give 400;
  - an unknown field at `PATCH /clients/{id}` names no package or class.

## 7. Image signatures bound to the device

- [ ] 7.1 `ImageSigner.sign(photoId, clientId)` and `verify` returning the client, per design decision 8; `AccessControl`
  checks the registry; `PhotoDtos` and its callers in the library and slideshow routes pass the request's client.
  Verify:
  - `ImageSignerTest`: a swapped client ID or another photo makes the signature invalid.
  - `LocalAccessTest` is adapted.
  - A new API test with `TestFrame` and the contract check covers `admin-access` › Protected endpoints › Signature
    after the device was removed.
  - › Reset all pairings on the frame › Access lost and `PhotoListPerformanceTest` stay green.

## 8. Threat model in the docs

- [ ] 8.1 `SECURITY.md` and the README security model per design decision 11:
  - the Host check, the limits and the pairing budget;
  - the residual risks: several attacker addresses or slow reads block the web UI while the slideshow runs on, and
    pairing by code can be paused for up to 24 h;
  - why `appVersion` and `androidVersion` stay public in `GET /info`.

  Verify: the README's statements match the specs of this change, and the README still names exactly the three
  endpoints without a token.

## 9. Integration

- [ ] 9.1 Run the full checks. Verify: `./gradlew check` with `:android`, `npm run lint`, `npm run typecheck`, `npm test`
  and `npm run e2e` (with its own `OPENFRAME_E2E_PORT`) are green.
- [ ] 9.2 Emulators API 21 and API 37 with the debug build:
  - pair by code with the display on;
  - after the display is switched off, `POST /pairing/code` creates no code;
  - the web UI works through `adb forward` (Host `localhost:<port>`);
  - photos load with their signed URLs.

  Verify: noted in the pull request, screenshots without private photos.
- [ ] 9.3 Check read-only which host the owner's Home Assistant entries use for both frames, from the config entries in
  its database. Verify: an IP address or an allowed name. If neither, tell the owner before the release.

## 10. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 10.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references:
  - a flood of 200 slow connections from a computer on the LAN while the web UI is used from a phone, on the Lenovo
    and the Frameo: the slideshow stays smooth, no restart, and the entries under `/proc/<pid>/fd` stay bounded, read
    only (`admin-access` › Limits for connections and request times › Flood from one device);
  - a request to show a code while the display is off (› Code only while shown on the frame › Display off);
  - a code entered after the hint was hidden (› Code entered after the hint was hidden);
  - Home Assistant still reaches both frames after the update.

  Verify: the rows exist with their references.
- [ ] 10.2 Run the rows of 10.1 after the release. Verify: results entered, and a finding in
  `../openframe-private/FINDINGS.md` for anything that differs.

## Workflow follow-up

- Propose the rest of audit package P5 as its own change: accepting connections only on local addresses, Range/HEAD and
  JSON 500 from the transport, the stricter contract harness, and a log of device management.
- Archive the change after the device rows of 10.1 are done.
