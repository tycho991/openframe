# Design

## Context

See proposal.md for the motivation. Here is the code on `main` after 0.9.0 (`harden-lan-server`), checked against the
rest of audit package P5.

- **Binding.** `FrameServer.start` binds `0.0.0.0`, or `127.0.0.1` in local-only mode. On Android this socket is
  dual-stack: on the Lenovo, `/proc/net/tcp6` lists `:::8080` in `LISTEN`, and `/proc/net/tcp` has no entry. The
  same read on the Frameo returned nothing; implementation repeats it there.
- **Addresses.**
  - `AndroidPlatform.addressCandidates()` collects the IPv4 addresses of every network, together with its
    `ConnectionKind` (`WIFI`, `ETHERNET`, `OTHER`, `CELLULAR`, `VPN`). For interfaces that no network owns, it
    guesses the kind from the name (`kindOfInterfaceName`).
  - `lanAddresses()` drops `CELLULAR`, `VPN` and 464XLAT, and `Platform.ipAddresses()` returns the result for
    the hint and the invitation link.
  - `FrameCore.onNetworkChanged()` clears the cached address and binds the server again.
- **Connections.** `NanoHttpServer.createClientHandler` gets the accepted `Socket`, and `BoundedAsyncRunner.exec`
  decides whether a connection gets a thread.
- **Responses.**
  - `NanoHttpServer.fileResponse` wraps a `RandomAccessFile` channel in `BoundedInputStream`, and that class has an
    empty `close()`, because the request body needs that.
  - `toNano` opens files and streams without a guard.
  - `StaticAssets.handle` opens assets with `assets.open(target)!!`.
  - The early 405 for methods outside `ApiMethod` goes through `finish` without `SECURITY_HEADERS`.
  - `StaticAssets` answers HEAD like GET, and NanoHTTPD 2.3.1 gzips text answers whenever the client accepts gzip,
    also for HEAD.
- **Contract harness.**
  - `Contract.check(method, path, status, contentType, body)` validates only JSON bodies.
  - `TestHttp.send` passes `""` for every body that is neither JSON nor empty.
  - `ContractConsistencyTest` already requires 421 and 500 at every operation and 408 at every operation with a
    JSON body.
- **Log.**
  - `PairingManager.create` logs `Paired` or `Paired by invitation` with the name only, outside the lock.
  - `PairingRoutes` logs nothing for `PATCH`/`DELETE /clients/{id}` and `POST /pairing/invites`.
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
- Binding to individual addresses instead of filtering. NanoHTTPD binds one address, and rebinding on every
  network change would drop open connections.
- NanoHTTPD's own plain-text 400 for request lines it cannot parse. It answers before our code runs.
- Logging read access or settings changes. Only the list of paired devices is covered.

## Decisions

1. **Filter on the accepted socket, before a thread starts.**
   - `NanoHttpServer` gets a predicate `acceptsLocal: (InetAddress) -> Boolean` and checks
     `socket.localAddress` in `createClientHandler`, before `BoundedAsyncRunner.exec`.
   - A refused connection is closed at once, with no answer, no thread and no slot. It is logged at most once a
     minute ("Connection on <address> refused: not a local address").
   - Alternative: filtering in `HttpPipeline`. Rejected, because NanoHTTPD would already have read the request and
     the connection would have taken a slot.
2. **Allowlist, failing closed.**
   - Allowed are loopback (`isLoopbackAddress`, which covers 127/8 and `::1`) and the device's addresses of every
     kind except `CELLULAR`, without the 464XLAT range.
   - A new `Platform.acceptedAddresses(): Set<String>` supplies them. `FrameCore` caches the set, clears it in
     `onNetworkChanged()`, and reads it again at most every 5 seconds when a connection arrives on an address
     outside it, which covers the short race after a network change.
   - Alternative: a deny-list of mobile data addresses. It would fail open for an address it does not know yet.
3. **IPv6 included.** The socket already accepts IPv6, and the Host check allows IPv6 literals. Filtering IPv4 only
   would cut off LAN clients that use IPv6 today.
   - `AndroidPlatform` collects IPv4 and IPv6 candidates for the new method.
   - `ipAddresses()` and `lanAddresses()` stay IPv4 only, so the shown address does not change.
   - Addresses are compared by their bytes: IPv4-mapped `::ffff:a.b.c.d` counts as `a.b.c.d`, and the scope of a
     link-local address (`%wlan0`) is ignored.
4. **VPN allowed (owner's decision).** `ConnectionKind.VPN` counts as local for accepting. It stays out of the shown
   address, because a browser on the LAN cannot open it.
5. **Device management log.** `PairingManager` and `PairingRoutes` write these lines at info level, outside any
   lock. Every ID is the full 16-hex client ID; the name comes last because it is free text.

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
   - An `IOException` (the file is gone, an asset is missing) becomes JSON 404 `not_found`.
   - Any other `Throwable` becomes JSON 500 `internal_error` and is logged.
   - Both answers carry `SECURITY_HEADERS`.
   - `StaticAssets` throws `FileNotFoundException` instead of using `!!`.
   - The early 405 adds `SECURITY_HEADERS` and `Allow`.
8. **HEAD.**
   - For HEAD, `toNano` gives NanoHTTPD no data and sets `Content-Length`. The length comes from the response when
     it is known (JSON, file, stream with a length). For a stream of unknown length it is counted once; only web
     UI assets have no length, and they are small.
   - `useGzipWhenAccepted` returns `false` for HEAD, because `setRequestMethod` runs before it (checked with javap
     in the audit).
   - `/api` keeps answering HEAD with 405.
9. **Stricter contract check.**
   - `Contract.check` takes the response headers and the request body.
   - It validates every response header the contract declares, and requires the ones marked `required`.
   - It turns on format assertions of the schema validator (`date-time`, `uri`).
   - For 2xx answers to a JSON request, it validates the request body against the operation's `requestBody`, so
     a test cannot pass with a body the contract forbids.
   - `TestHttp` passes on the body of every answer whose type is text or JSON, so a 204 with a body fails.
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
- **Short refusals after a network change.** A connection on a new address that arrives within 5 seconds of the last
  refresh is refused once. → `onNetworkChanged()` clears the cache, and the browser retries.
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
