# Tasks

## 1. Error kinds and the contract (first, so later groups report codes)

- [x] 1.1 `api/openapi.yaml` (design decision 11): a schema `SourceErrorCode`, and `errorCode` as
  `anyOf: [SourceErrorCode, null]` (required) on `SourceStatus` and `SourceLoginState`. Add `nextcloud_insecure_login`
  to the `BadGateway` description and at `POST /sources/login`. Then the DTOs in `SourcesRoutes.kt` by hand and
  `npm run gen:api`. Verify:
  - `npm run lint` in `webadmin/` is green;
  - `SourcesApiTest` cases with the contract check on see `errorCode` `null` after a successful sync, a code after a
    failed one and after a failed login, and `null` for a stored result without one;
  - a hand-made answer with `errorCode: "nonsense"` fails the contract check.
- [x] 1.2 Kinds and stored codes:
  - add the kinds `TLS_HANDSHAKE`, `THROTTLED`, `CHANGED`, `LIMIT` and `INSECURE_LOGIN`, and `retryAfterMs` on
    `SourceException`;
  - `StoredSyncResult.errorCode` as a string: the scheduler writes the kind, or `unexpected` for any other
    `Throwable`; an unknown stored value is served as `null`;
  - `LoginState.code`, `too_many_sources` included;
  - a `kind=` parameter for the failure injection of the e2e dev server.

  Verify:
  - a JVM test maps every kind to a contract value and fails on a kind without one;
  - a stored result without `errorCode` and one with an unknown value both load, and the unknown one is served as
    `null`.
- [x] 1.3 Classification (design decision 11):
  - `NextcloudHttp.translate` walks the cause chain: `TLS` only for a `CertificateException` or an
    `SSLPeerUnverifiedException`, other `SSLException`s are `TLS_HANDSHAKE`, an `UnknownServiceException` gets the
    cleartext text;
  - 429 becomes `THROTTLED` with `Retry-After` (seconds or HTTP date, clamped 15 min to 24 h);
  - body read errors in `propfind` and `json` go through `translate`;
  - `normalizeServerUrl` and `ShareLink.parse` check with `toHttpUrlOrNull`.

  Verify, new `NextcloudClientTest` cases:
  - a connection reset in the middle of a listing gives `UNREACHABLE`, not `unexpected`;
  - a handshake failure with the TLS fixture gives `TLS_HANDSHAKE`, and an unknown CA gives `TLS`;
  - 429 with `Retry-After: 120` gives 15 min, and with an HTTP date two days ahead gives 24 h;
  - `POST /sources/login` with port 99999, and `POST /sources` with such a share link, give 400 at the field.

## 2. Bounded answers and listings

- [x] 2.1 `LimitedResponseStream` and `SourceLimits` (design decisions 1 and 2): byte cap, total time and throughput
  window on `Clock.monotonicMillis`, cancel check, and the exceptions `LimitExceeded`, `NoRoom`, `TooSlow` and
  `Cancelled`. Pass `FrameCore`'s `Clock` to `OkHttpNextcloudClients`. Verify:
  - unit tests with `TestClock` for the cap and cap + 1, a stall below 16 KiB per 60 s, a steady trickle above the
    minimum that passes the total time, a cancel between reads, and a wall-clock jump that does not count as slow;
  - `./gradlew :core:check` (API 21 check) is green.
- [x] 2.2 `PropfindParser` (design decision 3):
  - text only inside `<response>`, up to 16 KiB per element, and at most 50,000 entries;
  - `DOCTYPE` refused through a `LexicalHandler` on the `XMLReader`;
  - `propfind` reads through the limited stream (64 MiB after gzip, 5 min).

  `FakeNextcloud` gets hostile modes: a DOCTYPE without entities, a DOCTYPE with nested entities, an oversized
  element, too many entries, a gzip body beyond the cap, a listing that trickles. Verify:
  - each mode fails the sync with its fixed kind and a readable text, and the photos stay. The kinds are: DOCTYPE
    `PROTOCOL`, the oversized element, too many entries and the gzip body `LIMIT`, and the trickle `UNREACHABLE`;
  - the DOCTYPE test without entities asserts the handler's own message, so it fails if the `LexicalHandler` is not
    installed;
  - a folder with 20,000 entries syncs completely;
  - `PropfindParserTest` stays green.
- [x] 2.3 `DavFolder.walk` iterative with depth 32, 5,000 folders and 100,000 files, checked against the folder's own
  entry (design decision 4). Verify, against `FakeNextcloud`:
  - an entry `..`, an empty name, `.` and an entry outside the folder each fail the listing with `PROTOCOL`, and no
    photo is removed with "remove vanished" on;
  - depth 33 and 5,001 folders fail with `LIMIT`;
  - a normal tree with subfolders still syncs.
- [x] 2.4 `LoginFlow.json` reads at most 64 KiB within 60 s. Verify: a 65 KiB answer to the start and to the poll
  fails the login with a readable error and stores nothing.

## 3. Downloads

- [x] 3.1 `RemoteFolder.download(file, target, DownloadLimits)` returns the `ETag`, and `list` takes `cancelled`.
  `DavFolder` enforces the caps per design decision 5, and `FakeRemoteFolder` follows. Verify, new tests:
  - a new file sending more bytes than listed is skipped as `too_large`, the `.part` is gone, and the sync goes on
    with the next file;
  - a known photo whose evicted original overruns stays in the library with its hidden state, comment and section;
  - size 0 with a `Content-Length` uses that value; without either, a smaller cap injected for the test applies;
  - a `Content-Length` of the listed size + 2 MiB skips the file without failing the sync, and the next sync fetches
    it; one of + 100 KiB is downloaded;
  - a download that would go below the reserve fails with `STORAGE`, and nothing is skipped;
  - the existing "A download that stalls" test now ends on the throughput rule with `TestClock`;
  - a test where the download exceeds the total time passes as well.
- [x] 3.2 Cancel on removal: `RemoteSource` passes `cancelled = { config() == null }` to `list` and `download`.
  Verify:
  - a test removes the source while `FakeNextcloud` holds first a listing and then a download open; each ends, no
    `.part` stays, and a second source's sync starts after it;
  - a `TestFrame` test removes a source while one of its originals downloads for a rendering; the slideshow keeps
    changing photos afterwards.
- [x] 3.3 HEIC from the first bytes (design decision 14):
  - `MediaEngine.canDecode`, with the overrides in `AndroidMediaEngine` and in `RemoteMediaEngine` (local, no binder)
    and a switch in `JvmMediaEngine`;
  - `acceptHeader` in the download;
  - `fetch` catches the `ImageException` from `download`.

  Verify:
  - with HEIF switched off, the client reads at most the first 32 bytes of a HEIC file, counted on the client side.
    The file is skipped as `unsupported` and not downloaded at the next sync;
  - a JPEG named `.heic` is shown;
  - `AndroidMediaEngineTest` and `RemoteMediaEngineTest` cover `canDecode` at SDK 23 and 28.

## 4. Rendering and the originals store

- [x] 4.1 `OriginalsStore` with a synchronized running total, pinned files and a seam for listing the folder (design
  decision 10). Verify:
  - a counting test shows one folder listing per sync instead of one per `fitsBudget`, and `trim` stats each file
    once;
  - a pinned file survives a `trim` below budget;
  - `RemoteSourceTest`'s budget cases stay green.
- [x] 4.2 Download thread in `RenderCache` (design decision 6):
  - `onFrame` (now marking the original as used) and `fetch`;
  - priority order and one download per photo;
  - `inFlight` kept during the handoff, the file pinned until its jobs render;
  - a refetch probe crash reported as a decoder crash;
  - `close()` stopping the thread;
  - 16 striped locks in `RemoteSource` instead of `downloadLock`;
  - the wiring in `FrameCore`.

  Verify:
  - a test blocks a remote download and shows a local render and a render with a stored original finishing
    meanwhile;
  - two sizes of one photo download once;
  - a failed download fails both waiting renders with a `SourceException` and does not quarantine the photo;
  - a decoder crash in the refetch probe quarantines it, and there is no second download;
  - an original rendered through `onFrame` is not the first evicted;
  - `close()` during a download returns;
  - `RenderCacheTest` and `SourceOriginalsTest` stay green.
- [x] 4.3 Refetched originals (design decision 15): ETag fast path, then dimensions; `CHANGED` or other dimensions
  trigger `onStale` once per sync; version + 1 in the `unchanged` branch when the dimensions differ. Verify:
  - a replaced file with other dimensions, and one with a `Content-Length` 2 MiB above the listed size, each fail the
    render and trigger one sync of the source;
  - a rewritten ETag with the same dimensions renders;
  - a sync that downloads an evicted original with other dimensions bumps the version.

## 5. Scheduler, login and app passwords

- [x] 5.1 Scheduler (design decision 8):
  - absolute deadlines on `monotonicMillis` and wake flags instead of the conflated channel;
  - the rejected state only for listing failures and only when the connection is unchanged since the sync started;
  - 24 h for rejected access, taken from the stored result after a restart and clamped to 0 to 24 h;
  - `THROTTLED` waits `retryAfterMs`;
  - a network change moves neither wait, while a configuration change and a request try at once;
  - `SourceRegistry.original` refuses in the rejected state.

  Verify, `SourceSchedulerTest` with `TestClock`:
  - after a 401 on the listing, no attempt within 23 h 59 min, also with a network change at 12 h and after a new
    scheduler built from the stored result, and one attempt at 24 h;
  - a 401 on a single file download retries after 60 s;
  - a configuration change followed at once by a network change still syncs at once, and so does "Sync now";
  - after a 429 the next try comes after `Retry-After`, also with a network change in between;
  - a 401 from a sync that ran while `reconnect` stored new credentials does not enter the rejected state;
  - a render request in the rejected state makes no HTTP request (`FakeNextcloud.requests`).
- [x] 5.2 Login (design decision 7, remote-sources-9):
  - `LoginOrigin` for the poll endpoint and the login page, with the hint only for a downgrade;
  - `INSECURE_LOGIN` → 502 `nextcloud_insecure_login`;
  - `upgradedServer` only where the rule holds;
  - an `http` address of an `https` source switched to `https` before the first request;
  - the existing source found by the start address when the port changes;
  - `Credentials.basic(…, UTF_8)` in `WebDavFolder`, `userId` and `revoke`.

  Verify:
  - an `https` start with an `http` endpoint, another port, or a login page on another host or with `javascript:`
    stops before the page opens, with the code;
  - an `http` account moving to `https` keeps its ID (`NextcloudConnectorTest`), also from port 8080 to port 8443,
    with no second source and the old password revoked;
  - a re-login typed with `http` for an `https` source sends no request over `http` (`FakeNextcloud.requests`) and
    stays on `https`;
  - login, sync and revoke work for the login name `jürgen` against `FakeNextcloud`.
- [x] 5.3 App-password hygiene (design decision 9):
  - `reconnect` revokes the old password in the background, after a running sync of the source, unless it was
    refused;
  - the login handler revokes the new password in the background when `userId()` or `reconnect` fails, and on a full
    frame;
  - `remove` skips the revoke for a refused password;
  - `NextcloudMigration.cleanUpLeftovers()` runs at start only after `READ` or a fresh `migrate()`, and not in safe
    mode;
  - `sourceRemoved` revokes a differing password;
  - the KDoc no longer promises the way back.

  Verify, `NextcloudConnectorTest` and `NextcloudMigrationTest`:
  - each case revokes exactly the unused password (`FakeLoginFlow.revoked`), and none for a refused one;
  - the 0.1.0 files are gone after the first start, with a revoke only when the password differs and `connected` is
    true;
  - with an unreadable `sources.json`, nothing is revoked and `nextcloud.json` stays;
  - a silent server does not delay the answer of `GET /api/v1/sources/login/{flowId}`.

## 6. Android, web UI and docs

- [x] 6.1 `android:usesCleartextTraffic="true"` with `tools:targetApi="23"` and the `xmlns:tools` declaration (design
  decision 13). Verify:
  - a test reads the merged manifest and finds the attribute;
  - `./gradlew :android:check` (lint with `abortOnError`) is green;
  - `GithubReleasesTest` "Redirect to http" stays green.
- [x] 6.2 Web UI (design decisions 11 and 16):
  - `sourceErrorKeys` with de/en texts for every code;
  - `nextcloud_insecure_login` also in `errorKeys`;
  - the status line and the login error use the code, with the English text as a detail, and the text when the code
    is `null`;
  - a `connecting` lock on "Connect";
  - the http(s) check before opening the login page;
  - `sources.httpWarning` on the card of a saved http source.

  Verify:
  - Vitest: every contract code has a key, `null` falls back to the text, an unknown code is shown raw, and a
    non-http(s) login URL is not opened;
  - e2e: a double click sends one `POST /sources/login`, and the injected sync failure (`kind=unreachable`) shows the
    German text;
  - `errorCodes.test.ts` is green;
  - `npm run lint`, `npm run typecheck` and `npm test` are green.
- [x] 6.3 Tests that were missing (tests-quality-8):
  - `FakeClients.accountFolder` records the folder per account;
  - a new `AccountSyncTest` with the real `OkHttpNextcloudClients` against `FakeNextcloud`: login flow, `PATCH` to
    `/Photos`, files in `Photos` and `Audio`;
  - `StaticAssetsTest` pins `script-src 'self'` without `'unsafe-inline'`.

  Verify:
  - only the `Photos` files reach the library, and every request is under `/remote.php/dav/files/<user>/Photos`;
  - the test fails when `NextcloudKinds` passes the account's root instead of its folder.
- [x] 6.4 Docs: the README security model names the accepted blind SSRF (design decision 12), plain http with its
  warning, and that a refused source retries once a day; `SECURITY.md` points to it. Verify: both texts are there, and
  the README names a future permission model for adding sources as the point to re-check.

## 7. Checks

- [x] 7.1 `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e`
  (own `OPENFRAME_E2E_PORT`) are green. Verify: the command outputs.
- [x] 7.2 `openspec validate nextcloud-hardening --strict` exits 0 without warnings. Verify: the output.
- [x] 7.3 A standalone `FakeNextcloud` for the device rows (design decision 17), with a bind address and with:
  - a DOCTYPE share;
  - a folder with 50,000 entries;
  - a HEIC share;
  - a plain `http` account.

  Verify: `curl` from this PC on its LAN address gets each listing, and the emulator reaches it on `10.0.2.2`.

## 8. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 8.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references:
  - Lenovo and Frameo: a source on the DOCTYPE share fails with a readable error, the app keeps running, and its heap
    stays bounded (`dumpsys meminfo`); the source is removed afterwards (`nextcloud-source` › Bounded answers of a
    server › Listing with a DOCTYPE);
  - Frameo: the folder with 50,000 entries syncs completely or fails with `limit`; note the time and the peak heap
    (› Large but legitimate folder);
  - Frameo: the HEIC share: the files are skipped as not supported after their first bytes; note the time for the
    share (`remote-sources` › Undecodable formats are not downloaded › HEIC on Android 6);
  - API 37 emulator: the plain `http` account connects and syncs (`nextcloud-source` › Plain http on every Android
    version).

  Verify: the rows exist with their references.
- [ ] 8.2 Run the rows of 8.1 after the release, updating through the updater, with the owner's consent for the update
  and for each test source. Adjust the limits in design.md if the Frameo needs it. Verify: results are entered, and
  anything that differs gets a finding in `../openframe-private/FINDINGS.md`.

## Workflow follow-up

- Archive the change after the device rows of 8.2 have run.
