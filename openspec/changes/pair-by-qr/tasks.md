# Tasks

## 1. QR encoder

- [x] 1.1 Add Nayuki `qrcodegen` to `gradle/libs.versions.toml` and wrap it in `:core` as `QrMatrix` (module matrix from a string, error correction M, rows of `0`/`1` for the API); ZXing as a test dependency only; if the Animal Sniffer check against API 21 fails, copy the single source file with its license notice instead; verify: `./gradlew :core:check` green, a test decodes the matrix of `http://192.168.100.215:8080/#pair=` plus a 22-character secret with ZXing, gets the same URL back and finds version ≤ 4

## 2. API contract

- [x] 2.1 `api/openapi.yaml`: `PairingRequest` with exactly one of `code` or `secret` (`^[A-Za-z0-9_-]{22}$`); `POST /pairing/invites` (token, 201 with `url`, `expiresAt`, `qr`); `PATCH /clients/{clientId}` (token, body `name` 1–64 characters, 200 with `Client`, 400, 404); `POST /pairing/code` documents that it also answers 204 during the lockout; verify: `RouteAccessTest` knows the new routes and only `GET /info`, `POST /pairing` and `POST /pairing/code` are reachable without a token
- [x] 2.2 Add the Kotlin DTOs by hand (`PairingRequestDto` with optional `code` and `secret`, invite and rename DTOs) and run `npm run gen:api` in `webadmin/`; verify: `./gradlew :core:compileKotlin` and `npm run typecheck` green

## 3. Pairing in `:core`

- [x] 3.1 Move the admin address from `DisplayActivity.address()` into `FrameCore` (first IPv4 address, bound port, port 80 without suffix) and use it in `DisplayActivity`; verify: unit test with a fake `PlatformInfo` for port 8080, port 80 and no address, hint text on the emulator unchanged
- [x] 3.2 `PairingManager`: session of code and secret (16 bytes `SecureRandom`, Base64url) created, replaced and ended together; constant-time comparison; wrong secret → 401 `invalid_code` without a failed attempt, expired secret → `expired_code`; lockout discards only the code, the secret stays until the session expires, a new session after the lockout; `requestDisplay()` without 429 during the lockout; `PairingDisplay` with `url` and `codeLocked`; `POST /pairing` accepts `secret` and rejects both or neither with 400; verify: tests with a fake `Clock` for "Scan with the phone", "QR code used twice", "Code after a scan", "QR code during the lockout", "Wrong secrets", "Request during the lockout" and replacement after 5 min
- [x] 3.3 Invitations: in memory, at most one per client, 5 min, once, `Host` from the request with fallback to the address from 3.1, dropped when the client is revoked and on "Revoke all pairings"; verify: contract tests for "Invite a second phone", "New invitation replaces the old one", "Same origin", "Inviting device revoked", an invalid `Host` falls back to the frame's address, and "Access lost" with an open invitation
- [x] 3.4 `ClientRegistry.rename` and `PATCH /clients/{clientId}` (trimmed, 1–64 characters, 400 otherwise, 404 for unknown ID, persisted immediately); verify: contract test for "Rename" including a new `ClientRegistry` loading the new name from the store

## 4. Display on the frame

- [x] 4.1 QR bitmap from `QrMatrix` (black on white, quiet zone of 4 modules, integer scaling without filtering, edge ≥ 40 % of the short screen side) in the hint overlay and the empty state; landscape QR on the right, portrait on top; "Code locked" instead of the code while `codeLocked`; no QR code without a network address or in local-only mode; texts de/en as string resources; verify: scenarios "Hint with QR code", "New frame" and "Code locked" on the emulator, screenshots at 1024×600 and 1920×1200 in `docs/screenshots/`, `./gradlew check` green
- [x] 4.2 Add a line to `docs/DEVICE_TEST.md` referring to "Hint with QR code": iPhone and Android camera from 1 m, at an angle, in room light, on Frameo and Lenovo; verify: line present with the scenario reference

## 5. Web UI

- [x] 5.1 `QrCode.svelte` draws the matrix as an SVG of `<rect>` elements, without `{@html}`; verify: Vitest renders a matrix and counts the dark modules
- [x] 5.2 On start: read `#pair=`, remove it with `history.replaceState`, redeem it without a valid token, discard it otherwise; result "Paired as …" with "Rename"; an error leads to the pairing dialog with a matching message; texts in `de.json` and `en.json`; verify: Playwright "Opened through a pairing link", "Expired pairing link" and "Already paired" against the `:core` test server
- [x] 5.3 Pairing dialog: hint to scan the QR code on the frame, code input and name field as before; "Show code on frame" handles 204 during the lockout; verify: Playwright "First visit"
- [x] 5.4 Device list: "Invite device" with QR code, selectable link and remaining time, "New invitation" after expiry; "Rename" per device; verify: Playwright "Show invitation", "Invitation accepted" (second browser context), "Rename device"; at 360 px no horizontal scrolling
- [x] 5.5 README, section "Installing and first pairing": first pairing by QR code, code as fallback, invitation, note on a DHCP reservation and on iOS web apps on the home screen; verify: section present and the steps match the interface

## 6. Integration

- [ ] 6.1 Whole flow against the `:core` test server: frame session → link → paired → invitation → second context paired → rename → revoke the inviting device; verify: `./gradlew check` and `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e` in `webadmin/` green, `openspec validate pair-by-qr` with exit 0 and no warnings

## Workflow follow-up

- Archive `bootstrap-openframe-core` first; this change's deltas modify its requirements.
- Home Assistant change: pairing by request and approval, mDNS/NSD, control endpoints.
- Archive this change with `/opsx:archive` after review and a green acceptance on the devices.
