# Proposal

## Why

Audit package P7 found that the Nextcloud path trusts the server too much. A defective, misconfigured or hostile server
(or anything a paired device points a source at) can make the frame read without bounds, leave its folder, keep
retrying refused credentials, switch a login from https to http, or keep the only render worker waiting for a slow
download. The prerequisites the plan named are on `main` now: the sync catches every `Throwable`
(`crash-resilience`), safe mode stops sources after repeated crashes, and the updater enforces https in code
(`app-auto-update`). All 14 items of the package are still open on 0.10.0.

## What Changes

- **Bounded answers.**
  - Limits apply to the PROPFIND body after decompression, to the text per XML element, to the entries per answer,
    and to folder depth and the number of folders and files per sync. The login JSON is limited as well, and every
    answer has a total time, so a trickling server cannot hold a sync for days.
  - A `DOCTYPE` is refused. A server that goes over a limit fails the sync or login with a readable error, and no
    photo is lost.
- **Listings stay inside the folder.** Every entry of an answer must be a direct child of the listed folder. An entry
  named `.` or `..`, an empty name, or an entry outside the folder fails the listing instead of being dropped
  silently.
- **Bounded downloads.**
  - A download ends when it delivers more bytes than listed (or announced, or a fixed cap), when it would go below the
    storage reserve, or when it is too slow or takes too long. A photo already in the library keeps its state.
  - It also ends when its source is removed. Removing a source ends a running listing too.
  - A file replaced between listing and download counts as changed, not as a failed sync.
- **Rendering does not wait for downloads.** A missing original of a remote photo is fetched on a download thread of
  its own. Local photos and photos whose original is present keep rendering meanwhile.
- **No downgrade during the login.**
  - With an https address, the poll endpoint and the login page must be https on the same host and port. Otherwise
    the login stops with its own error code `nextcloud_insecure_login`, before the login page opens.
  - The login page must be http(s) on the server's host, and the web UI opens only http(s) addresses.
  - A known https account keeps https when it logs in again.
- **Rejected access is not retried in a loop** (owner's decision).
  - After refused credentials for the folder, a withdrawn share or an upload-only share, the frame tries again
    automatically at most once a day, also across restarts and network changes.
  - It tries at once after a user action: logging in again, changing the configuration, or "Sync now".
  - In that state it fetches no originals for rendering.
  - HTTP 429 honours `Retry-After`.
- **Errors are classified and carry a code.**
  - Classification fixes:
    - An I/O error while reading a body counts as unreachable, not as unexpected.
    - Only a certificate problem counts as an untrusted certificate. Other TLS failures get their own kind.
    - A server address with an invalid port gets 400 at its field, not 500.
  - The source status and the login state carry an `errorCode` from a fixed list. The web UI shows sync and login
    errors in its language, with the English text as detail.
- **No orphaned app passwords.**
  - The frame revokes an app password it no longer uses, in the background:
    - the old one when a login replaces it;
    - the new one when a login fails after the approval.
  - It skips this when the server already refused the old one.
  - The leftover app password of 0.1.0 in `nextcloud.json` is revoked if it differs from the current one, and the file
    is deleted. This gives up the way back to 0.1.0 (owner's decision).
- **Login names beyond ASCII.** Basic auth for accounts is encoded in UTF-8, as it already is for shares.
- **Smaller fixes without a spec change:**
  - The budget check of the originals costs O(1).
  - A refetched original whose size differs from the index is discarded, and the source syncs soon.
  - HEIC before Android 9 is recognised from its first bytes and not downloaded.
- **Plain http on every Android version** (owner's decision). `android:usesCleartextTraffic="true"` makes a
  deliberately entered http address work on Android 9 and later as well. The web UI warns about http at the field and
  now also on the card of a saved source. The updater keeps enforcing https in code.
- **Double click on "Connect"** starts one login only: button and field are locked while the login starts.
- **Accepted risk, documented** (owner's decision): a paired device can point a source at any http(s) address the frame
  reaches, a blind server-side request (SSRF). The README security model says so; it is re-checked with the permission
  model of P6.
- **Tests:**
  - The account folder is checked to reach the sync, with the real HTTP clients against the fake Nextcloud.
  - The CSP of the web UI is pinned in a test.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `nextcloud-source`: new requirements "Bounded answers of a server", "Listings stay inside the folder", "No downgrade
  during the login", "No orphaned app passwords", "Login names beyond ASCII", "Plain http on every Android version".
- `remote-sources`: "Schedule and retry", "Status", "No repeated downloads", "Storage of the originals", "Remove a
  source" and "Access to the sources" change; new requirements "Rejected access", "Bounded downloads",
  "Rendering does not wait for downloads", "Refetched original matches the index", "Undecodable formats are not
  downloaded".
- `web-admin`: new requirements "Source errors in the interface language", "Safe start of an account login", "Plain
  http is pointed out".

## Impact

- `core`:
  - `nextcloud/`: `DavFolder`, `PropfindParser`, `LoginFlow`, `NextcloudConnector`, `NextcloudHttp`,
    `NextcloudMigration`, `ShareLink`, a new limited input stream, and a pure login-origin rule.
  - `sources/`: `SourceException` (new kinds), `RemoteFolder` (download limits, ETag result, cancel), `RemoteSource`,
    `SourceScheduler` (absolute deadlines, wake flags), `SourceRegistry`, `OriginalsStore`, `SyncResults`,
    `StoredSource`.
  - Also `library/RenderCache`, `platform/MediaEngine`, `api/SourcesRoutes`, `FrameCore`.
  - Tests: the fake Nextcloud gets hostile modes, `FakeClients` records account folders, and there is a new
    `AccountSyncTest`.
- `android`: manifest (`usesCleartextTraffic`), `AndroidMediaEngine` and `RemoteMediaEngine` (`canDecode` answered
  locally).
- `api/openapi.yaml`:
  - `errorCode` (nullable, required) on `SourceStatus` and `SourceLoginState`.
  - `nextcloud_insecure_login` at `POST /sources/login`.
  - Then `npm run gen:api`.
- `webadmin`: error codes with de/en texts, the status line and login errors through the code, the connecting lock,
  an http(s) guard for the login URL, and the http warning on the source card.
- Test servers: the e2e dev server's failure injection takes a kind. A standalone `FakeNextcloud` with a bind address
  serves the hostile shares for the device rows.
- Docs: the README security model gets the accepted SSRF. The archived statement that `nextcloud.json` stays for the
  way back to 0.1.0 is superseded.
- Behaviour visible to users:
  - A source with refused access syncs at most once a day until the user acts.
  - Errors appear in the interface language.
  - http works on Android 9+.
  - A server whose answers break the limits fails with a readable error instead of hanging or crashing the sync.
- Device rows (private checklist):
  - A `DOCTYPE`/entity-expansion share on both frames, added and removed again with the owner's consent.
  - A synthetic folder with 50,000 entries on the Frameo.
  - A HEIC share on the Frameo.
  - An http Nextcloud on the API 37 emulator (owner's choice).
