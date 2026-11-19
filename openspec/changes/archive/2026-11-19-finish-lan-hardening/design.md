# Design

## Context

See proposal.md for the motivation. Here is the code on `main` after 0.9.0 (`harden-lan-server`), checked against the
rest of audit package P5.

- **Binding.** `FrameServer.start` binds `0.0.0.0`, or `127.0.0.1` in local-only mode. On Android this socket is
  dual-stack: on the Lenovo and the Frameo, `/proc/net/tcp6` lists `:::8080` in `LISTEN`, and `/proc/net/tcp` has
  no entry. IPv4 clients arrive as IPv4-mapped addresses (`::ffff:a.b.c.d` in `/proc/net/tcp6`).
- **Addresses.**
  - `AndroidPlatform.addressCandidates()` collects the IPv4 addresses of every network, together with its
    `ConnectionKind` (`WIFI`, `ETHERNET`, `OTHER`, `CELLULAR`, `VPN`). For interfaces that no network owns, it
    guesses the kind from the name (`kindOfInterfaceName`).
  - `lanAddresses()` drops `CELLULAR`, `VPN` and 464XLAT, and `Platform.ipAddresses()` returns the result for
    the hint and the invitation link.
  - `FrameCore.onNetworkChanged()` clears the cached address and binds the server again. It runs only from
    `onAvailable` of a `NetworkRequest.Builder().build()` callback (`FrameService`). That request excludes VPNs by
    default, and nothing listens to `onLinkPropertiesChanged`, so a VPN coming up or a new IPv6 address reaches the
    core through no callback.
- **Connections.** `NanoHttpServer.createClientHandler` gets the accepted `Socket`, and `BoundedAsyncRunner.exec`
  decides whether a connection gets a thread.
- **Responses.**
  - `NanoHttpServer.fileResponse` wraps a `RandomAccessFile` channel in `BoundedInputStream`, and that class has an
    empty `close()`, because the request body needs that.
  - `toNano` opens files and streams without a guard.
  - `StaticAssets.handle` opens assets with `assets.open(target)!!`.
  - The early 405 for methods outside `ApiMethod` goes through `finish` without `SECURITY_HEADERS`.
  - HEAD is broken in NanoHTTPD 2.3.1 (checked with `javap`). For HEAD, `Response.send` skips
    `Transfer-Encoding`, calls `sendContentLengthHeaderIfNotAlreadyPresent` and then sends the body anyway. The
    chunked web UI answers therefore send `Content-Length: -1` and their whole body, fixed-length answers send their
    body as well, and with `Accept-Encoding: gzip` the body is gzipped. That method always writes the header, even
    when one was added with `addHeader`, and a `Response` without data resets its length to 0.
  - A Range request for a file deleted after the cache lookup gets 416, because `file.length()` returns 0.
- **Contract harness.**
  - `Contract.check(method, path, status, contentType, body)` validates only JSON bodies. Its `METHODS` know no
    HEAD, so a check of HEAD, TRACE or PROPFIND on `/api/` fails as an undescribed operation.
  - `TestHttp.send` passes `""` for every body that is neither JSON nor empty. `TestHttp.raw` reads to the end of
    the stream and merges repeated headers, so it can read neither two answers on one connection nor a doubled
    `Content-Length`.
  - `ContractConsistencyTest` already requires 421 and 500 at every operation and 408 at every operation with a
    JSON body.
- **Log.**
  - `PairingManager.create` logs `Paired` or `Paired by invitation` with the name only, outside the lock.
  - `PairingRoutes` logs nothing for `PATCH`/`DELETE /clients/{id}` and `POST /pairing/invites`.
  - "Revoke all pairings" on the frame logs `All pairings revoked on the frame` at warning level (`PairingAdmin`).
    No device acts there, and the line stays as it is.
  - Names are already single-line (`singleLine`, requirement "Device names without control characters").

## Goals / Non-Goals

**Goals:**

- The frame answers only on loopback and its LAN and VPN addresses, IPv4 and IPv6, and it notices new addresses
  without a restart.
- Every error path of a response ends with a JSON answer carrying the security headers, and no file descriptor
  leaks.
- The contract test catches missing required headers, wrong formats, request bodies the contract does not allow,
  and bodies on answers that should have none.
- Each change to the list of paired devices can be traced to the device that made it.

**Non-Goals:**

- A switch for VPN access. VPN is always allowed.
- Binding to individual addresses instead of filtering. NanoHTTPD has one listening socket, and a VPN or a new
  IPv6 address triggers no rebind (see Context).
- Listening to `onLinkPropertiesChanged` or VPN networks. The refresh on an unknown address (decision 2) covers
  them without a new Android callback.
- NanoHTTPD's own plain-text 400 for request lines it cannot parse. It answers before our code runs.
- Logging read access or settings changes. Only the list of paired devices is covered.

## Decisions

1. **Filter on the accepted socket, before a thread starts.**
   - `NanoHttpServer` gets a predicate `acceptsLocal: (InetAddress) -> Boolean` and checks
     `socket.localAddress` in `createClientHandler`. For a refused address it returns a `Refused` handler.
     NanoHTTPD's accept loop always passes the handler to `exec`, and `BoundedAsyncRunner.exec` closes a `Refused`
     handler at once, without a slot or a thread.
   - The connection gets no answer. It is logged at most once a minute ("Connection on <address> refused: not a
     local address").
   - Alternative: filtering in `HttpPipeline`. Rejected, because NanoHTTPD would already have read the request and
     the connection would have taken a slot.
2. **Allowlist, failing closed.**
   - Allowed are loopback (`isLoopbackAddress`, which covers 127/8 and `::1`) and the device's addresses of every
     kind except `CELLULAR`, without the 464XLAT range.
   - A new `PlatformInfo.localAddresses(): LocalAddresses` supplies two lists of `InetAddress`: accepted, and
     refused (mobile data and 464XLAT).
   - `FrameCore` keeps an immutable snapshot of both as byte keys in a `@Volatile` field, because the accept thread
     reads it. `onNetworkChanged()` clears it.
   - A connection on an address in neither list reads the addresses again at once, at most once a second
     (`monotonicMillis`). This covers a VPN or a new IPv6 address that no callback announces, and the race after a
     network change. An address known as refused never triggers a read.
   - Alternative: a deny-list of mobile data addresses. It would fail open for an address it does not know yet.
3. **IPv6 included.** The socket already accepts IPv6, and the Host check allows IPv6 literals. Filtering IPv4 only
   would cut off LAN clients that use IPv6 today.
   - `AndroidPlatform` collects IPv4 and IPv6 candidates for the new method.
   - `ipAddresses()` and `lanAddresses()` stay IPv4 only, so the shown address does not change.
   - Addresses are compared by their bytes, never parsed back from text: Android's `hostAddress` carries
     `%wlan0`, and parsing it fails once the interface is gone. IPv4-mapped `::ffff:a.b.c.d`, the form in which
     the dual-stack socket reports IPv4 clients, counts as `a.b.c.d`, and the scope of a link-local address is
     ignored.
4. **VPN allowed (owner's decision).** `ConnectionKind.VPN` counts as local for accepting. It stays out of the shown
   address, because a browser on the LAN cannot open it.
5. **Device management log.** `PairingManager` and `PairingRoutes` write these lines at info level, outside any
   lock. Every ID is the full 16-hex client ID; the name comes last because it is free text. A revocation reads
   the name before it removes the client.

   | Event | Line |
   |---|---|
   | Pairing | `Paired <id>: <name>` |
   | Pairing by invitation | `Paired <id> by invitation of <inviterId>: <name>` |
   | Rename | `Device <actor> renamed <target> to <name>` |
   | Revocation (a device removing itself is `<actor> == <target>`) | `Device <actor> revoked <target>: <name>` |
   | Invitation | `Device <actor> created an invitation` |

   The tests check, in the style of `SourcesSecrecyTest`, that no token, secret or invitation URL appears in
   `GET /api/v1/logs`.
6. **Range streams close their file.** `fileResponse` uses its own `RangeInputStream` over the channel, whose
   `close()` closes the `RandomAccessFile`. `BoundedInputStream` keeps its empty `close()` for request bodies.
7. **Errors while opening a response.**
   - `serve` wraps `toNano`.
   - `fileResponse` opens the file first and takes the length from the open file, so a file deleted after the
     cache lookup gives 404 also for a Range request, instead of 416.
   - A `FileNotFoundException` (the file is gone, an asset is missing) becomes JSON 404 `not_found`.
   - Any other `Throwable`, other `IOException`s included, becomes JSON 500 `internal_error` and is logged.
   - Both answers carry `SECURITY_HEADERS`.
   - `StaticAssets` throws `FileNotFoundException` instead of using `!!`.
   - The early 405 adds `SECURITY_HEADERS` and `Allow`.
8. **HEAD.**
   - For HEAD, `toNano` builds `newFixedLengthResponse(status, type, <empty stream>, length)`. That writes one
     `Content-Length` and sends no body. Adding the header by hand would write it twice, and a response without
     data would report 0.
   - The length comes from the response when it is known (JSON, file, stream with a length). For a stream of
     unknown length it is counted once; only web UI assets have no length, and they are small.
   - `useGzipWhenAccepted` returns `false` for HEAD, because `setRequestMethod` runs before it (checked with javap
     in the audit).
   - `/api` keeps answering HEAD with 405.
9. **Stricter contract check.**
   - `Contract.check` takes the response headers and the request body.
   - It validates every response header the contract declares, and requires the ones marked `required`.
   - It turns on format assertions of the schema validator (networknt 3.0.9:
     `SchemaRegistryConfig.builder().formatAssertionsEnabled(true)`). The contract uses `date-time`; `Invite.url`
     gets `format: uri`.
   - It skips methods the contract does not model (HEAD, TRACE, PROPFIND). Their generic behaviour is in the info
     description, and the tests assert it directly.
   - For 2xx answers to a JSON request, it validates the request body against the operation's `requestBody`, so
     a test cannot pass with a body the contract forbids.
   - `TestHttp` passes the body of every text or JSON answer and the byte count of every other answer, so a 204
     with any body fails.
   - `TestHttp` gets a raw reader for answers on one keep-alive connection that keeps repeated headers apart.
   - `ContractConsistencyTest` adds 400, 413 and 415 at every operation with a JSON request body.
10. **Contract changes.**
    - `GET /photos/{photoId}/image` gets 206 (with `Content-Range` required, and `Accept-Ranges`) and 416 (with
      `Content-Range`).
    - `SettingsPatch` gets `additionalProperties: false` and refers to the enums and limits of `Settings`. Where
      they are inline, they move to `components/schemas`.
    - The info description adds that unknown methods get 405 with `Allow`, and that HEAD works on the web UI only.

## Risks / Trade-offs

- **An unknown interface counts as local.** A vendor's mobile data interface whose name is not in
  `CELLULAR_PREFIXES` and which no Android network owns would be accepted. → Android reports mobile data as a
  network with `TRANSPORT_CELLULAR` on API 21 and later, and the name guess is only a fallback.
- **Short refusals after a network change.** A connection on a new address within a second of the last read is
  refused. The browser shows "empty response" (`ERR_EMPTY_RESPONSE`) and does not retry by itself. → A fresh
  address triggers a read at once, and `onNetworkChanged()` clears the snapshot.
- **The emulator may have mobile data.** Recent emulator images may report an interface as cellular. → Check with
  `dumpsys connectivity`. If it is cellular, the emulator also tests the mobile data scenario.
- **The stricter harness finds old contract violations.** → They are fixed in this change, in the contract or the
  code, whichever is wrong. The task for the harness comes first, so the fixes stay small.
- **Narrower settings types in the web UI** can break `npm run typecheck`. → It is fixed together with
  `npm run gen:api`.
- **Mobile data cannot be tested on a device.** No test frame has a SIM, and the Galaxy Tab S7 is paused. → JVM tests
  with a fake platform, plus read-only device checks of the listening socket and of reachability after Wi-Fi off
  and on.

## Migration Plan

None. The change ships with the next release through the updater. A frame that answered on mobile data stops
answering there; Wi-Fi, Ethernet, hotspot and VPN behave as before. Rollback is the previous release.
