# Tasks

## 1. Stricter contract harness (first, so old violations surface early)

- [ ] 1.1 `Contract.check` takes the response headers and the request body (design decision 9):
  - it validates every response header the contract declares and requires the `required` ones;
  - it turns on format assertions;
  - for 2xx answers to a JSON request it validates the request body against `requestBody`.

  `TestHttp.send` and `TestHttp.raw` pass both, and `TestHttp.send` passes on text and JSON bodies of every
  answer. Verify: new `ContractHarnessTest` cases fail for a 204 with a text body, a 429 without `Retry-After`, a
  `date-time` field holding `"yesterday"` and a 2xx answer to a request body with an unknown field. The existing cases
  stay green.
- [ ] 1.2 Run `./gradlew :core:test` with the stricter harness and fix every violation it finds, in the contract or
  in the code, whichever is wrong. Verify: `:core:test` is green, and the PR text lists each fix.
- [ ] 1.3 `ContractConsistencyTest` also requires 400, 413 and 415 at every operation with a JSON request body. Add
  the missing ones to `api/openapi.yaml`. Verify: the test fails on a copy of the contract without one of them and
  passes on the real contract.

## 2. Contract

- [ ] 2.1 `api/openapi.yaml` per design decision 10:
  - 206 and 416 at `GET /photos/{photoId}/image` (`Content-Range`, `Accept-Ranges`);
  - `SettingsPatch` with `additionalProperties: false` and the enums and limits of `Settings`;
  - 405 and HEAD in the info description.

  Then `npm run gen:api`, and adapt the web UI to the narrower settings types. Verify: `npm run lint`,
  `npm run typecheck` and `npm test` in `webadmin/` are green. A new `SettingsRoutesTest` case with the contract
  check on gets 400 for an unknown field in `PATCH /settings`, 415 for `text/plain` and 413 for more than 1 MiB.

## 3. Responses

- [ ] 3.1 Range answers close their file (design decision 6). Verify: a test in `HttpPipelineTest` sends 100 range
  requests for an image (206, contract check on), and the number of entries under `/proc/self/fd` is unchanged
  afterwards. A test hook that reports the close replaces the count where `/proc` is missing.
- [ ] 3.2 Errors while opening a response (design decision 7). Verify:
  - a file deleted between the cache lookup and the open gives JSON 404 with `X-Content-Type-Options` and no path in
    the body;
  - an `AssetSource` that returns `null` on the second open gives JSON 404;
  - a stream that throws `IllegalStateException` on open gives JSON 500 with the security headers and an `ERROR`
    line in the log;
  - `TRACE` and `PROPFIND` get 405 with the security headers and `Allow`.
- [ ] 3.3 HEAD (design decision 8). Verify, over a raw socket on one keep-alive connection: HEAD on `/` with and
  without `Accept-Encoding: gzip`, then GET `/`. HEAD gives 0 body bytes and the `Content-Length` of the GET, and the
  GET is complete. HEAD on `/api/v1/info` stays 405.

## 4. Connections only on local addresses

- [ ] 4.1 Address candidates may hold IPv6 addresses. Add
  `acceptedAddresses(candidates): Set<String>` in `platform/LanAddresses.kt`: everything except `CELLULAR` and
  464XLAT. Add `Platform.acceptedAddresses()`; `FakePlatform` and `TestCore` follow. Verify: `LanAddressesTest`
  cases for Wi-Fi, Ethernet, hotspot (`OTHER`), VPN and IPv6 link-local accepted, and mobile data and
  `192.0.0.4` refused. `lanAddresses()` returns the same as before.
- [ ] 4.2 `AndroidPlatform` collects IPv6 addresses for `acceptedAddresses()`; `ipAddresses()` stays IPv4.
  Verify: `./gradlew :android:check` is green, and a debug build on the API 37 emulator logs no refusal for
  `adb forward` and for the emulator's own address.
- [ ] 4.3 `NanoHttpServer` and `FrameServer` take an `acceptsLocal` predicate. Connections on other addresses are
  closed before a thread starts and logged at most once a minute (design decision 1). `FrameCore` supplies loopback
  plus the cached `acceptedAddresses()`: cleared in `onNetworkChanged()`, refreshed at most every 5 s for an unknown
  address (design decision 2). Addresses are compared by their bytes (design decision 3). Verify, new tests in
  `ServerLimitsTest` or a `LocalAddressTest`:
  - `127.0.0.2` is answered with an allowed set that holds only a foreign address;
  - a predicate without the loopback exception closes the connection without an answer and without a thread;
  - an unknown address triggers one refresh, and a second one comes at the earliest 5 s later (`TestClock`);
  - `::ffff:127.0.0.1` counts as loopback;
  - `LocalAccessTest` stays green.

## 5. Log of device management

- [ ] 5.1 Lines per design decision 5 for pairing, pairing by invitation, rename, revocation and invitation, outside
  the pairing lock. Verify: a `TestFrame` test renames, revokes, creates an invitation and pairs with it. Then
  `GET /api/v1/logs` has one line per event with the IDs, and contains neither a token, nor the pairing secret, nor
  the invitation URL. `RenameTest` and `InviteTest` stay green.

## 6. Docs

- [ ] 6.1 README security model and `SECURITY.md`: the frame answers only on loopback, LAN and VPN addresses, and the
  log records changes to the paired devices. Verify: both name VPN as reachable and mobile data as not.

## 7. Checks

- [ ] 7.1 `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e`
  (own `OPENFRAME_E2E_PORT`) are green. Verify: the command outputs.
- [ ] 7.2 `openspec validate finish-lan-hardening --strict` exits 0 without warnings. Verify: the output.

## 8. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 8.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references:
  - both frames, read only: which socket listens on the port (`/proc/net/tcp`, `/proc/net/tcp6`), and that the
    admin interface answers on the Wi-Fi IPv4 address, on the IPv6 link-local address and through `adb forward`
    (`admin-access` › Connections only on local addresses › From the device itself; › IPv6 on the LAN);
  - one frame, with consent: Wi-Fi off and on, then the admin interface answers on the new address without a
    restart (› Wi-Fi off and on);
  - mobile data and VPN not testable on the frames (no SIM, no VPN app); noted with the JVM tests that cover them.

  Verify: the rows exist with their references.
- [ ] 8.2 Run the rows of 8.1 after the release. Verify: results entered, and a finding in
  `../openframe-private/FINDINGS.md` for anything that differs.

## Workflow follow-up

- Archive the change after the device rows of 8.1 are done.
