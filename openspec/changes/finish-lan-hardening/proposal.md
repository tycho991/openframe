# Proposal

## Why

`harden-lan-server` (0.9.0) closed the attacks on the LAN server that need no token. Four points of audit package P5
were left for a follow-up:

- **The server answers on every address of the device.** It binds `0.0.0.0`, and on Android this socket is dual-stack
  (the Lenovo lists `:::8080` in `/proc/net/tcp6`). A frame on mobile data therefore answers on the carrier's
  address, and IPv6 carrier networks usually hand out a global address.
- **Some error paths still end badly.**
  - Partial image requests (Range, 206) never close their `RandomAccessFile`, so every one leaks a file
    descriptor.
  - A file removed between the cache lookup and the open gets NanoHTTPD's plain-text 500 with the cache path. A
    missing web UI asset ends the connection without an answer (`assets.open(target)!!`).
  - The early 405 for unknown methods has no security headers.
  - HEAD on the web UI sends a gzip body when the client accepts gzip.
- **The contract test sees less than it should.**
  - 206 and 416 at the image are missing from `api/openapi.yaml`, and `SettingsPatch` accepts any field.
  - `Contract.check` checks no response headers (a 429 without `Retry-After` passes), no formats (`date-time`) and
    no request bodies, and `TestHttp` hides the body of non-JSON answers.
- **Device management leaves no trace.** Renaming or revoking a paired device and creating an invitation are not
  logged at all, and "Paired" names the device but not its ID. When a device disappears from the list, nobody can
  tell which device removed it.

## What Changes

- **Connections only on local addresses.** The server SHALL answer only connections that arrive on loopback or on an
  address of a Wi-Fi, Ethernet, hotspot/tethering or VPN interface of the device, IPv4 or IPv6. It SHALL close
  connections on a mobile data address without an answer. VPN addresses are allowed (owner's decision), so a frame
  stays reachable through Tailscale or WireGuard. The address the frame shows stays the LAN address as before.
- **Log of device management.** Pairing, renaming, revoking, creating an invitation and pairing by invitation SHALL
  each write one info line with the IDs of the acting and the affected device and the device name. Tokens, secrets
  and invitation links SHALL NOT be logged.
- **Fixes without a spec change:**
  - Range responses close their file.
  - A missing file gives JSON 404 and any other error while opening a response gives JSON 500, both with the
    security headers and without a path.
  - The early 405 gets the security headers.
  - HEAD sends no body, also with gzip, and a correct `Content-Length`.
- **Contract and test harness:**
  - 206 and 416 (with `Content-Range` and `Accept-Ranges`) at `GET /photos/{photoId}/image`.
  - `SettingsPatch` with `additionalProperties: false` and the enums and limits of `Settings`.
  - 405 and HEAD described in the API description.
  - `Contract.check` validates required response headers, formats and, for 2xx answers, the request body sent.
  - `TestHttp` passes non-JSON bodies on.
  - `ContractConsistencyTest` additionally requires 400, 413 and 415 at every operation with a JSON body.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-access`: new requirements "Connections only on local addresses" and "Log of device management".

## Impact

- `core`:
  - `http/NanoHttpServer.kt`, `http/FrameServer.kt`, `http/StaticAssets.kt`, `FrameCore.kt`,
    `platform/Platform.kt`, `platform/LanAddresses.kt`, `api/PairingRoutes.kt`, `auth/PairingManager.kt`.
  - Tests: `testing/Contract.kt`, `testing/TestHttp.kt`, `testing/FakePlatform.kt`, `ContractConsistencyTest`, new
    tests for local addresses, Range/HEAD/500 and the log.
- `android`: `AndroidPlatform.kt` reports the accepted addresses, IPv6 included. `TestCore.kt` follows.
- `api/openapi.yaml`: 206/416 at the image, stricter `SettingsPatch`, description of 405 and HEAD. `npm run gen:api`.
  The web UI types may become narrower for settings.
- Behaviour visible to users: none on Wi-Fi. A frame on mobile data no longer answers there, and a frame on a VPN
  keeps answering.
- Device rows: mobile data cannot be tested (no test device has a SIM). Read-only checks on both frames instead: the
  listening socket, and the admin interface after Wi-Fi off and on, `adb forward` to `127.0.0.1` included.
