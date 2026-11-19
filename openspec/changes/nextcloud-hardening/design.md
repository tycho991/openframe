# Design

## Context

See proposal.md for the motivation. The state on `main` (0.10.0) that shapes the approach:

- `core/.../nextcloud/` is unchanged since the audit snapshot.
  - `DavFolder.propfind` hands the raw body to `PropfindParser`, and `walk` recurses without limits.
  - `characters()` collects text without a cap, also outside `<response>`.
  - On Android 5/6, Expat does not know `disallow-doctype-decl`, and the parser swallows the feature error.
  - `LoginFlow.json` reads `body.string()`.
- Since `crash-resilience`, `SourceScheduler.sync` catches every `Throwable`, and safe mode starts no scheduler. A
  hostile listing no longer crashes the app, but it can still exhaust the heap or hang a sync. A hung sync holds the
  `syncLock` that all sources share.
- The scheduler's wake channel is `Channel.CONFLATED`, and its wait is a `withTimeoutOrNull` that starts again after
  every wake. A network wake therefore restarts a long wait, and can overwrite a pending configuration change or
  request.
- `RemoteSource` downloads under one global `downloadLock`. `RenderCache.render` calls `originalOf` on its single
  worker, so a missing remote original blocks every rendering. The worker has an `onCrashed` quarantine path for
  decoder crashes. `SourceOriginals.onFrame` returns a stored original without marking it as used, while
  `RemoteSource.original` does mark it.
- `SlideshowController` rethrows a `CancellationException` from a rendering, so one that reaches it from a coroutine
  that was not cancelled ends its command loop.
- `edit.skip` removes a photo from the library together with its hidden state, comment and section.
- `SourceConfigStore.load()` reports `MISSING`, `READ` or `UNREADABLE`. Only `MISSING` migrates from 0.1.0, and the
  sources launch in the other two cases too, so with an unreadable list the registry is simply empty.
- Since the decoder moved to its own process, the engine the core sees on Android is `RemoteMediaEngine`, which
  proxies to `AndroidMediaEngine` in `DecoderService`.
- The updater enforces https in code: it refuses non-https URLs and every non-https hop through a network interceptor
  (`GithubReleases`). That is the precondition for allowing cleartext app-wide.
- `http/NanoHttpServer.kt` already has an `internal class BoundedInputStream` for request bodies.
- The dev server used by the e2e tests serves Nextcloud from the in-memory `FakeClients`, not over HTTP.
  `FakeNextcloud`, the HTTP fake of the JVM tests, binds only to `127.0.0.1`.
- The archived `multi-source-share-links` design kept `nextcloud.json` for the way back to 0.1.0. Decision 9
  supersedes that.

## Goals / Non-Goals

**Goals:**

- Every read from a source server is bounded in bytes, time, entries and depth, the same on the JVM (Xerces) and on
  Android (Expat).
- Error kinds are precise enough for a code that the web UI can translate, and the code survives restarts and
  downgrades.
- No retry loop against refused credentials, and no app password left active on a server without a use.

**Non-Goals:**

- No filter of the addresses a source may point at (the blind SSRF is accepted, decision 12).
- No new kind of source and no change to share-link sources beyond the shared limits and codes.
- No change of the per-source storage budget or of the 100 MB reserve. P8 may change the reserve; the download limit
  reads it from the same place.
- No range requests for HEIC (see risks).

## Decisions

### 1. One limited input stream for every response body

- **What:** a new `LimitedResponseStream` in `core/.../nextcloud/` wraps every body read from a source server.
- **What it enforces:**
  - a byte cap;
  - a total time per body;
  - a minimum throughput per 60-second window, measured from the first read;
  - an optional `cancelled()` check before each read.
- **Clock:** the time runs on `Clock.monotonicMillis`, so a wall-clock jump after an NTP sync cannot make a download
  "too slow". `FrameCore` passes its `Clock` to `OkHttpNextcloudClients` and from there to `DavFolder` and
  `LoginFlow`; no `Clock` reaches them today.
- **Errors:** it throws its own `IOException` subclasses, so Xerces and Expat surface the same exception through the
  SAX parser. `DavFolder` and `LoginFlow` translate them:

  | Exception | Kind | Note |
  |---|---|---|
  | `LimitExceeded` | `LIMIT` | |
  | `NoRoom` | `STORAGE` | the free-space cap of a download |
  | `TooSlow` | `UNREACHABLE` | "too slow", for the throughput window and the total time |
  | `Cancelled` | `UNREACHABLE` | "the source was removed" |

- **Why `Cancelled` is not a `CancellationException`:** on the render path it would reach `SlideshowController` from
  a coroutine that was never cancelled and end its loop. For a sync of a removed source the kind does not matter:
  the scheduler keeps no result for a removed source.
- **API level:** it uses only API-21 JDK calls.
- **Alternative:** extend the server's `BoundedInputStream`. It has no clock, no cancel check and other errors, and it
  belongs to the HTTP server, so the two packages of `:core` stay apart.

### 2. Limits as constants

All limits live in one object (`SourceLimits`), named in the tests:

| Limit | Value | Applies to |
|---|---|---|
| Listing body after gzip decompression | 64 MiB | each PROPFIND answer |
| Text per element | 16 KiB | `href`, `getetag`, … (a long percent-encoded path fits) |
| Entries per answer | 50,000 | each PROPFIND answer |
| Depth of subfolders | 32 | each sync |
| Folders per sync | 5,000 | each sync |
| Files per sync | 100,000 | each sync |
| Login answer | 64 KiB | each login JSON answer |
| Download with a listed size | size + 1 MiB | each download |
| Download without a listed size | `Content-Length` + 1 MiB, else 512 MiB | each download |
| Minimum throughput | 16 KiB per 60 s | every body |
| Total time | 5 min per listing answer, 60 s per login answer, 30 min per download | every body |

- **Sizing:** the values for entries and files are proposals sized for the Frameo's heap. The device row with 50,000
  entries can lower them, and the spec names only "fixed limits", so a new value needs no spec change.
- **What the total time protects:** it bounds how long a trickling server holds the `syncLock` or the render download
  thread. At the minimum throughput alone, a 512 MiB file would take weeks.

### 3. The parser refuses a DOCTYPE through a LexicalHandler

- **How:** `PropfindParser` gets an `XMLReader` from the `SAXParser` and sets
  `http://xml.org/sax/properties/lexical-handler` on that reader. `SAXParser.parse(InputSource, DefaultHandler)` does
  not install a LexicalHandler, so parsing goes through `XMLReader.parse`. Its `startDTD` throws.
- **Why it holds on Android:** Expat reports `startDTD` before it reads the internal subset, so nested entities are
  never expanded. Setting `disallow-doctype-decl` stays for Xerces, but a failure to set it is no longer the only
  defence.
- **Text and entries:** text is collected only inside `<response>`, up to the cap per element. More entries than the
  cap end the parse with `LIMIT`.
- **Test:** the JVM test uses a DOCTYPE without entities, so Xerces' own entity-expansion limit cannot make it pass,
  and it asserts the handler's own error and the kind `PROTOCOL`.
- **Fallback:** a streaming filter in front of the parser that refuses `<!DOCTYPE` and `<!ENTITY` before the root
  element, skipping comments and processing instructions. It is built only if the device row shows that Expat does
  not call `startDTD`.

### 4. Listings are read iteratively and checked against the folder's own entry

- **How:** `DavFolder.walk` becomes a loop over a queue with the depth, folder and file caps, and checks
  `cancelled()` between PROPFINDs.
- **Reference point:** in each answer it is the folder's own entry. That is the one whose path equals the requested
  path, otherwise the first one, which Nextcloud always lists first at `Depth: 1`.
- **Rejected entries:** every other entry must be a direct child of it, with a name that is not `.`, `..` or empty.
  Anything else fails the listing with `PROTOCOL`.
- **Why fail and not drop:** `RemoteFolder.list` promises "complete or nothing", and "Vanished photos" would delete
  photos from a shortened list.

### 5. Downloads: limits, changed files, too large files, cancel

- **Interface:**
  - `RemoteFolder.download(file, target, limits)` takes `DownloadLimits(maxBytes, cancelled, acceptHeader)` and
    returns the response's `ETag` header.
  - `RemoteFolder.list(recursive, cancelled)` takes the cancel check.
- **Cancel signal:** `RemoteSource` passes `cancelled = { config() == null }`. A removed source ends its listing or
  download at the next read, at the latest after the read timeout; `Job.cancel` does not interrupt a blocking read.
- **The byte cap:** the listed size plus 1 MiB. A size of 0 counts as unknown: the parser sets 0 when
  `getcontentlength` is missing, and then `Content-Length` plus 1 MiB or the fixed cap applies. Separately, a download
  may not take more than the free space minus the reserve, computed like `OriginalsStore.hasRoomOnDisk`. Going over
  that is `NoRoom` → `STORAGE`, never too large, so a full disk does not turn into a permanent skip.
- **Outcomes:**
  - **Changed:** a `Content-Length` above the listed size plus 1 MiB means the file was replaced after the listing.
    It becomes the kind `CHANGED`, with a log line, and `fetch` treats it like `NOT_FOUND`: the file is the next
    sync's business, and the sync goes on. Within the tolerance the download is taken, so a slightly stale listed
    size, such as on external storage that was not rescanned, still works.
  - **Too large, new file:** any other overrun on a file without a photo in the library becomes
    `ImageException(TOO_LARGE)`. It is skipped with its size and time, the same way a file over the pixel limit is,
    and the sync goes on.
  - **Too large, known photo:** the same overrun on a photo already in the library (the `unchanged` branch, an evicted
    original fetched again) is a `SourceException(LIMIT)` for that file. Like `NOT_FOUND`, it leaves the entry
    untouched, so hidden state, comment and section stay.
  - **Too slow, cancelled, no room:** these end the sync as today (`UNREACHABLE`, `STORAGE`).
  - **In every case** the `.part` file is deleted, and the `syncLock` is released in the existing `finally`.

### 6. Missing originals are downloaded on a thread of their own

- **The two functions:** `RenderCache` takes two functions instead of `originalOf`:
  - `onFrame(entry)` for an original that is already there. `SourceOriginals.onFrame` now marks it as used, as
    `RemoteSource.original` does, so the most rendered originals are not the first evicted.
  - `fetch(entry)`, which is `SourceOriginals.fileOf`.
- **Handoff:** when the original is missing, the render worker hands the job to a single download thread and takes
  the next job.
- **The download thread:**
  - It works in priority order (`DEMAND`, `NEXT`, `PREFETCH`).
  - It downloads each photo once, however many sizes wait for it.
  - It then queues the jobs again with their `Deferred`, together with the downloaded file.
  - The `inFlight` entry stays during the handoff, so another request for the same size joins it.
- **Pinning:** the downloaded original stays pinned in `OriginalsStore` until the queued jobs of that photo have
  rendered. `trim` skips pinned files, so a small budget cannot evict it in between and cause a download loop.
- **Failures:**
  - A failed download completes all waiting `Deferred`s of that photo with a `SourceException`, never a
    `CancellationException`.
  - It does not count as a decoder crash.
  - The probe of a refetched original (decision 15) runs through the engine. If the decoder crashes there, the
    failure is reported the way a crash during rendering is, so `onCrashed` quarantines the photo and it is not
    downloaded again for every render attempt.
- **Close:** `close()` stops the download thread and cancels its jobs.
- **Locks:** the global `downloadLock` in `RemoteSource` becomes 16 striped locks keyed by the hash of the entry ID,
  so the number of locks stays fixed. The `.part` file is already named per ID, and the probe stays under the lock of
  its entry. `OriginalsStore` becomes thread-safe for this (decision 10).
- **Alternative:** a second render worker. It doubles the decoder's memory on 1 GB devices, while a download needs
  none.

### 7. Login origin rule

- **The rule:** `LoginOrigin.accepts(base, other)` is pure. It requires scheme `http` or `https` and the same host.
  With an `https` base it also requires `https` and the same port.
- **Where it applies:** `OkHttpLoginFlow.start` applies it to the poll endpoint and to the login page. A violation
  throws the new kind `INSECURE_LOGIN`.
  - The message has a hint to `overwriteprotocol` and `trusted_proxies` only for a downgrade to `http` or another
    port.
  - A login page on another host or with another scheme gets its own message.
- **API answer:** `NextcloudConnector.startLogin` maps the kind to 502 `nextcloud_insecure_login`, not to
  `nextcloud_unreachable`, whose text would hide the hint.
- **Reported server address:** `upgradedServer` takes the server's reported address only where the rule holds. So
  `http`→`https` on the same host is still taken, and `https`→`http` never is.
- **Log in again with `http`:** if the address typed for a login matches, by `serverKey` without regard to scheme, a
  source that is on `https`, `startLogin` switches to that source's `https` address before the first request. The
  whole flow then runs over `https`, poll answer and app password included.
- **Port change:** `pollLogin` finds the existing source by the reported address and, failing that, by the address
  the login started with. So an `http` account on one port that moves to `https` on another port of the same host
  updates its source instead of adding a second one, and the old password is revoked (decision 9).
- **Web UI guard:** the web UI checks the scheme before `window.open` and `href`, as a second guard.

### 8. Rejected access and 429 in the scheduler

- **What counts as rejected:**
  - `UNAUTHORIZED`, `GONE` or `NOT_READABLE` from listing the folder put a source into the rejected state.
  - The same kinds from the download of a single file do not. A server can refuse one file, for example through
    access-control rules, and that file is retried like other failures.
  - A refusal from a sync that started with another connection than the current one does not count either. That
    happens when the user logged in again during the sync.
- **Absolute deadlines:**
  - The loop keeps an absolute deadline on `monotonicMillis` instead of a relative `withTimeoutOrNull` that starts
    again after each wake.
  - Wake reasons are kept as flags (configuration, request, network), not in a conflated channel, so a network wake
    can no longer overwrite a pending configuration change or request.
- **The waits:**
  - Rejected: the deadline is 24 hours after the failed sync. A network change does not move it. A configuration
    change (login again, patch) or a request ("Sync now") tries at once.
  - Throttled: HTTP 429 becomes the kind `THROTTLED`. `SourceException` gets a `retryAfterMs` field, parsed from
    `Retry-After` (seconds or an HTTP date) and clamped to 15 min–24 h. A network change does not move that deadline
    either. Without the header, the normal retry applies.
  - Other failures: the existing retry, which a network change still shortens.
- **Across restarts:** the rejected state comes from the stored `StoredSyncResult` (its `errorCode` and time). It
  therefore holds after a restart and in safe mode, where no scheduler runs. On start, a rejected source waits for the
  rest of its 24 hours, clamped to 0–24 h because the wall clock may jump after a boot, instead of `FIRST_SYNC_MS`.
- **Renderings:** `SourceRegistry.original` refuses at once in the rejected state, without a request that carries the
  refused credentials.
- **Why once a day** (owner's decision): a failed attempt every 24 h stays below Nextcloud's 12-hour brute-force
  window. A temporary 401, such as a directory outage, still heals without the user.

### 9. App-password hygiene and the end of the way back to 0.1.0

- **Login again:** `SourceRegistry.reconnect` reads the old configuration before it stores the new one. If the
  connection differs, it revokes the old app password in the registry's scope, as `remove` does. It waits until a
  sync of that source that is running has ended, so the running sync does not fail with the old, revoked password.
- **Login fails after the approval:** if `userId()` or `reconnect` fails after the approval, the `GET
  /sources/login/{flowId}` handler revokes the new password in the registry's scope. The handler does not do it
  itself, because a silent server would hold the request for up to 75 s. The same background revoke replaces the
  synchronous one on a full frame.
- **Refused passwords:** neither `reconnect` nor `remove` revokes a password while the source is in the rejected state
  for `UNAUTHORIZED`. The server refuses it anyway, and the attempt counts as a failed login there.
- **Leftover of 0.1.0:** `NextcloudMigration.cleanUpLeftovers()` runs at start only when `sources.json` was read
  (`READ`) or has just been created by `migrate()`, and only when sources launch (not in safe mode). With an
  unreadable list the registry is empty, and the source `nextcloud` would look gone although its password is in use.
  - If `nextcloud.json` is `connected` and holds a password other than the current one of the source `nextcloud`, or
    that source is gone, the password is revoked in the background with the server and login name from that file.
  - Then `nextcloud.json` and `nextcloud-status.json` are deleted.
  - `sourceRemoved` revokes a differing password the same way.
- **Superseded:** this replaces the archived `multi-source-share-links` design (lines 30 and 81) and the KDoc of
  `NextcloudMigration`. The requirement "Existing connection after the update" stays: the source is kept, only the
  file of the old version goes.

### 10. Originals store with a running total

- **The total:** `OriginalsStore` keeps a synchronized byte total. It is read once when first needed and again at the
  start of every sync, because Android may trim `cacheDir`.
- **Who updates it:** `commit` (replacing a file included), `remove`, `trim` and `clear`.
- **Effects:**
  - `fitsBudget` becomes O(1).
  - `trim` returns at once when the total is within the budget. Otherwise it reads `lastModified` and `length` once
    per file and sorts those pairs, skipping pinned files.
- **Test seam:** listing the folder goes through one function, so a test can count the listings.

### 11. Error kinds and the `errorCode` contract

- **New kinds:** `SourceException.Kind` gains `TLS_HANDSHAKE`, `THROTTLED`, `CHANGED`, `LIMIT` and `INSECURE_LOGIN`.
- **`NextcloudHttp.translate`:**
  - It walks the cause chain with the helper the updater already uses.
  - It reports `TLS` only for a `CertificateException` in the cause chain or an `SSLPeerUnverifiedException`. Other
    `SSLException`s become `TLS_HANDSHAKE`.
  - An `UnknownServiceException` gets a text about cleartext, a fallback should the manifest attribute ever be
    missing.
- **Body reads:** I/O errors while reading a body (`propfind`, `json`) go through `translate`.
- **Address checks:** `normalizeServerUrl` and `ShareLink.parse` also check with `toHttpUrlOrNull`, so a port outside
  1–65535 gives 400 at the field.
- **Contract:** a named schema `SourceErrorCode` is a string enum with:
  - every kind in snake_case (`changed` and `insecure_login` included, though a sync result never has them);
  - `unexpected` for any other `Throwable`;
  - `too_many_sources`.

  `SourceStatus.errorCode` and `SourceLoginState.errorCode` are required and are
  `anyOf: [{$ref: SourceErrorCode}, {type: "null"}]`. A `$ref` with a sibling `type` would not allow `null`.
- **Naming:** the kind `INSECURE_LOGIN` and the API error code `nextcloud_insecure_login` are the same condition. The
  API code follows the existing `nextcloud_unreachable`; it is the only one a client sees for a login start.
- **Stored as a string:** `StoredSyncResult` stores the code as a plain string, not as the Kotlin enum. `JsonStore`
  ignores unknown keys but fails on unknown enum values, so a kind renamed in a later version or after a downgrade
  would make `sources-status.json` unreadable. When serving, a stored code the contract does not know becomes `null`.
  Older files without the field load with `null`.
- **Tests:**
  - A JVM test checks that every kind has a contract value.
  - `SourcesApiTest` asserts `errorCode` `null` on success, a code on failure, and `null` for a result without one.
- **Web UI:**
  - `sourceErrorKeys: Record<SourceErrorCode, Key>` follows `updateErrorKeys`, and the English text stays as a detail.
  - `nextcloud_insecure_login` also goes into `errorKeys`, which `errorCodes.test.ts` checks for every
    `HttpException` code in the Kotlin sources.
- **Dev server:** the failure injection of the e2e dev server takes a `kind=` parameter, so the e2e test can assert
  the German text of a chosen code.

### 12. Accepted: blind SSRF through sources

- **What it is:** a paired device can set a source to any http(s) address the frame reaches. The frame reports only
  status classes back.
- **Why accepted:**
  - A paired device is in the LAN already and has full rights today (P6 open).
  - Through the frame it reaches in addition only the frame's own loopback services and, on a device with a SIM, the
    carrier network.
  - A filter for loopback and link-local addresses would need a bypass for tests, because `FakeNextcloud` and the e2e
    server run on `127.0.0.1`. It would also exclude home servers on link-local addresses.
- **Documented:** in the README security model. It is re-checked when the permission model decides who may add
  sources.

### 13. Cleartext through the manifest attribute

- **How:** `android:usesCleartextTraffic="true"` with `tools:targetApi="23"`. The manifest root gets the
  `xmlns:tools` declaration it does not have yet.
- **No XML file:** without a `networkSecurityConfig`, Android 7+ derives its cleartext rule from the attribute.
- **Unchanged:** the trust anchors still come from `TrustAnchors.platform`. The updater keeps refusing http in code,
  so the attribute does not reach it.
- **Test:** a manifest test reads the merged manifest and asserts the attribute.

### 14. HEIC before Android 9 from the first bytes

- **`canDecode`:** `MediaEngine` gets `fun canDecode(format: ImageFormat): Boolean = true`.
  - `AndroidMediaEngine` overrides it for HEIF with the SDK level.
  - `RemoteMediaEngine` answers it locally from `Build.VERSION.SDK_INT`, without a binder call, so it can never throw
    `DecoderUnavailableException`.
  - `JvmMediaEngine` gets a switch for tests.
- **The download:** `DavFolder.download` buffers the first 32 bytes and calls `acceptHeader` from `DownloadLimits`.
  On `false` it closes the response and throws `ImageException(UNSUPPORTED)`.
- **`fetch`:** it catches that exception from `download` itself, deletes the part and returns `Result.failure`, so the
  file is skipped with its size and time.
- **By content:** detection stays by content, so a JPEG named `.heic` is downloaded.

### 15. Refetched originals: ETag fast path, then dimensions

- **Fast path:** `RemoteSource.original` compares the normalised response `ETag` (no `W/`, no quotes, no `-gzip` or
  `-br` suffix) with the entry. If they match, the original is taken.
- **Otherwise:** it probes the `.part` file. Same dimensions: the original is taken.
- **Stale:** if the dimensions after rotation differ, or the download ended with `CHANGED`:
  - the part is deleted and the rendering fails;
  - `onStale` makes the registry call `configChanged()` for the source, at most once until that sync ends.
- **In a sync:** in the `unchanged` branch of `fetch`, dimensions from the probe that differ from `previous` make the
  entry count as changed (new dimensions, version + 1).
- **Alternatives:**
  - `If-Match`: weak ETags can turn it into a permanent 412.
  - An ETag comparison alone: a proxy that rewrites ETags would make originals unrenderable and cause endless syncs.

### 16. Web UI

- **Connect lock:** `SourcesView.connect()` gets a `connecting` flag like `adding`. It locks button and field and
  returns early while set.
- **Scheme check:** a helper in `lib/sources.ts` checks the login URL for http(s).
- **Warning:** `SourceCard` shows `sources.httpWarning` for a saved http server.
- **CSP:** a test pins `script-src 'self'` without `'unsafe-inline'` in `StaticAssets`.
- **Labels:** the English `DeviceName` labels stay. They are stored at pairing time and are names, not messages.

### 17. Test servers for the device rows

- **Standalone fake:** `FakeNextcloud` gets a `main` in the test sources that binds to a given address. It serves:
  - a share with a DOCTYPE;
  - a folder with 50,000 entries;
  - a share with HEIC files;
  - a plain account over `http`.
- **Who reaches it:** the frames reach it on this PC's LAN address, and the API 37 emulator on `10.0.2.2`. The
  frames' local-address rule applies only to connections into the frame, so these outgoing requests are not affected.
- **Rest of the code:** the in-memory fake of the dev server stays as it is for the e2e tests.

## Risks / Trade-offs

- **Limits too tight or too loose:** a legitimate folder beyond the caps fails, or the caps still load the Frameo's
  heap.
  → The values are constants (decision 2), and the code `limit` names the reason. A test lets 20,000 entries through,
  and a device row measures 50,000 entries on the Frameo. The values are adjusted before release if needed.
- **Total time too short for a slow line:** 30 min per download needs about 290 KiB/s for a 512 MiB file.
  → Photos are rarely that large. A file that times out is retried like any other unreachable failure.
- **The DOCTYPE refusal on Expat is proven only on a device.**
  → The device row on Lenovo and Frameo is mandatory. The fallback filter of decision 3 is ready if Expat does not
  call `startDTD`.
- **Stricter listings fail on inconsistent servers,** such as a proxy with a subpath and no `overwritewebroot`.
  → The reference point is the folder's own entry, so only answers that contradict themselves fail, and the message
  names the cause.
- **A temporary 401 on the folder pauses a source for up to a day** (owner's decision).
  → "Sync now" and logging in again try at once, and the status names the reason.
- **Cleartext is allowed app-wide.**
  → The updater enforces https in code, with a test for a redirect to http. The UI warns at the field and on the
  card.
- **HEIC abort costs a connection per file.** OkHttp discards an unread body by closing the connection, so 5,000 HEIC
  files cost 5,000 TLS handshakes once.
  → If the Frameo row shows this as too slow, a follow-up uses a range request for `.heic`/`.heif`.
- **A revoke with an already invalid password counts as a failed login on the server.**
  → It is skipped in the rejected state. Cleaning up `nextcloud.json` can still send one, once.
- **Merge order with P8** (`RenderCache`, `OriginalsStore`).
  → P7 goes first, and P8 builds its queue generations and reserve on top.
- **Disclosure:** the proposal describes weaknesses in a public repository.
  → Proposal and implementation land close together, and the release follows soon through the updater.

## Migration Plan

- **Results without a code:** old results have no `errorCode` and show their text until the next sync.
- **Already refused sources:** a source that already fails with refused credentials enters the rejected state only at
  its next failed sync, because old results have no code.
- **The 0.1.0 file:** it is revoked and deleted at the first start of the new version with a readable list of sources.
  A downgrade below this version keeps working; a downgrade to 0.1.0 has no Nextcloud account any more.
- **Rollback:** install the previous release over it. The stored results gain a string field that older versions
  ignore.
