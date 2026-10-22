# Design

## Context

openframe has one photo source, the local folder (`LocalPhotoSource`). The library already treats sources as equals: every photo has a `source`, IDs derive from source and path, `PhotoSource.scan()` reconciles the index, and `PhotoIndexStore.edit` applies changes in one step. `RenderCache` renders thumbnails and display sizes from an original that it asks for through a function `originalOf(entry)`; the slideshow shows only rendered images. What is missing is a source that gets its files from a server.

`pictureframe` is the reference for behavior (Login Flow v2, WebDAV, ETag comparison, retry with doubling delays, bundled trust anchors). Nothing is taken over from it: its code, its configuration and its hosts stay in the other repository.

Constraints: `:core` has no `android.*` imports and is checked against API 21 (`javax.xml` and the SAX parser are there, `java.nio.file` and Java 9+ are not). The devices are an Android 5.0.1 tablet and an Android 6.0.1 frame with 1 GB of RAM or less; both are old enough not to trust the root certificate of Let's Encrypt. The API is defined in `api/openapi.yaml` first.

## Goals / Non-Goals

**Goals:**
- A Nextcloud folder shows up on the frame without typing a password, and stays in step in the background.
- A sync never takes away what is on the frame when it fails, and never disturbs the slideshow.
- The same code runs on both devices and on the JVM, so that the sync logic is tested without a device.

**Non-Goals:**
- Videos, writing to Nextcloud, several folders or accounts, and sharing features.
- Importing hidden states, comments or crops from `pictureframe`.
- Certificate pinning or a way to accept any certificate. An unknown authority is refused.

## Decisions

### 1. A package `nextcloud` in `:core`, with the network behind two small interfaces

`NextcloudSource : PhotoSource` does the sync. It talks to the server through `RemoteFolder` (`list(path)`, `download(file, target)`) and to the login through `LoginFlow` (`start(url)`, `poll(flow)`). The real implementations use OkHttp; the tests use an in-memory fake for the sync logic and a small HTTP server for the real client. The interfaces are not about the platform (OkHttp works the same everywhere) but about testing without sockets.

*Alternative:* `java.net.HttpURLConnection`, which needs no dependency. Rejected after trying it: it refuses every method outside `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `OPTIONS` and `TRACE` (`ProtocolException: Invalid HTTP method: PROPFIND`), on the JVM and on Android, and `PROPFIND` is the listing. A client of our own on sockets would have to get TLS, redirects and chunked bodies right; that is more to get wrong than a dependency. OkHttp 4.12 supports API 21 and is a pure Java and Kotlin library (no native code; the APK check for that stays green). It adds about 1 MB to the APK.

### 2. WebDAV: one `PROPFIND` per folder, user ID looked up once

The listing uses `PROPFIND` with `Depth: 1` per folder and walks the subfolders itself, because Nextcloud refuses `Depth: infinity`. It asks for `getetag`, `getcontentlength`, `getlastmodified` and `resourcetype`, and the answer is read with a SAX parser (`javax.xml.parsers`, on the JVM and on API 21 alike, and it streams instead of building a tree). The path is `/remote.php/dav/files/<user id>/<folder>`. The user ID is not always the login name, so it is read once after the login with `ocs/v2.php/cloud/user` and stored with the configuration.

OkHttp's own redirect handling is switched off. Redirects (typically `http` to `https`) are followed by the client itself, up to five times, only within the same host and never from `https` back to `http`, and the `Authorization` header goes only to that host. A listing that fails in any folder fails the whole sync: nothing is removed from a partial listing.

### 3. The login runs through the frame, one request per poll

The browser cannot talk to Nextcloud (no CORS), so `POST /nextcloud/login` makes the frame call `index.php/login/v2` and returns `{flowId, loginUrl}`; the web UI opens `loginUrl` in a new tab. `GET /nextcloud/login/{flowId}` makes the frame poll Nextcloud once and answer `pending`, `done`, `failed` or `expired`. The web UI polls every two seconds. The frame does not poll on its own, so an abandoned login costs nothing and simply expires after 20 minutes. There is at most one flow; a new one replaces it.

The address the user gives is the only address (besides the login URL it returns) the frame calls. Only `http` and `https` are accepted. A home Nextcloud on plain `http` in the LAN is allowed; the interface says so.

### 4. The configuration lives in a file of its own

`nextcloud.json` in the state folder (written through `JsonStore`, atomic) holds the server, user ID, login name, app password and the options. It is not part of `settings.json` and not of `PATCH /api/v1/settings`: the settings are public to every paired device and logged in part; this file has a secret. The API returns the configuration without the password, with a `connected` flag. The password is read in one place (the WebDAV client) and never put into a log line or an error message; the client builds its error texts from status codes and the server's own message only. On disconnect the frame tries to revoke the app password (`DELETE ocs/v2.php/core/apppassword`, five seconds, failures ignored), deletes the file and removes the photos of the source.

### 5. TLS: the system's anchors plus delivered ones, in one trust manager

`TrustAnchors` loads the PEM files delivered as resources of `:core` (the root of Let's Encrypt, `ISRG Root X1`, and the other roots the devices need), adds them to a `KeyStore` and builds an `X509TrustManager` that accepts a chain if the platform's default manager or the delivered one accepts it. The OkHttp client gets the resulting `SSLSocketFactory` and trust manager. The hostname check stays the platform's. A test starts an HTTPS server with a test root and checks that the chain is accepted with that root delivered and refused without.

*Alternative:* tell users to install a certificate on the device. Rejected: the point is that it works after pairing.

### 6. Originals are a cache with a budget of their own

Downloaded files go to `<cache>/nextcloud/<id>.<extension>`, first as `.part` and renamed when complete (a download is streamed to disk, never held in memory: a 10 MB photo must not need 10 MB of heap on a frame with a 200 MB limit). The budget is the option `originalsBudgetMb`; when it is exceeded after a sync or a download, the files with the oldest last use are removed (the file's modification time is set when an original is read). `RenderCache` asks a router for the original: the local folder for local photos, the Nextcloud cache for the others. If a Nextcloud original is missing, the router fetches it on the render worker before rendering; a failure ends in the usual `render_failed`, and the next sync fetches the file again anyway (a sync fetches a missing original again only while the budget has room for it, otherwise a library larger than the budget would be downloaded again at every run).

Rendered sizes are not touched. A slideshow needs only them, so evicting originals does not make a photo disappear. The cache folder may also be emptied by Android under storage pressure; the same path covers it.

*Alternative:* keep originals next to the local photos in the photo folder. Rejected: they would show up over USB and would be mistaken for the user's own files, and Android may clear a cache folder but never the photo folder.

A download is refused when it would leave less than 100 MB free (the reserve that uploads use already); the sync then ends with an error in the status.

### 7. The sync applies its result in one step

For every remote file the sync compares the ETag with the one in the index (a new nullable field `etag` of the index entry; local photos keep `null`). Downloads and probes happen first, outside any lock, one file at a time at low thread priority (the slideshow runs on the same few cores). Only then one `PhotoIndexStore.edit` applies all changes. Removal considers only entries that were in the index when the sync began, so a photo added meanwhile is never taken away (the same flaw was fixed in the local scan). Changed files keep ID and hidden state and raise the image version. Files the engine cannot accept go to the list of skipped files with the source `nextcloud`; the sync remembers size and modification time of those so that it does not download them again.

The ID is `idFor("nextcloud", path)` with `path` relative to the configured folder, as for local photos. Changing the folder therefore changes the IDs and loses the hidden states of the old folder; that is stated in the interface text of the folder field.

### 8. Schedule: one coroutine, delays as a pure function

One coroutine in the frame's scope runs the sync: once 30 seconds after start (the network is rarely ready earlier), then every interval. After a failure the next try comes after `min(interval, 60 s × 2^n)` with `n` capped at 5; a success resets `n`. A change of the configuration, "sync now" and `onNetworkChanged()` wake it up (a network change shortens a waiting retry to 10 seconds). A mutex keeps two syncs from overlapping; "sync now" during a run returns `202` with `syncing: true` and starts nothing. The delay logic is a function of `n`, the interval and the outcome, tested on its own with a test clock.

### 9. What the library changes

- `PhotoEntry` gets `etag: String? = null`, and the API's photo gets `source` (`local` or `nextcloud`) so that the interface can hide the delete button.
- `DELETE /photos/{id}` for a Nextcloud photo answers `409 read_only_source`; in a batch it is reported in `failed` (that field exists since the review fixes). Hiding works as for local photos.
- Uploads still go to the local folder only.

### 10. Wi-Fi off and a slow server

Connections time out after 15 s (connect) and 60 s (read) so that a hung server does not block the sync thread for long. With no network the sync fails at once, the status shows the error, the slideshow is not affected. A frame that starts with no network and no cache shows the empty state, as without any photos.

### 11. Tests

- JVM: the sync logic against an in-memory `RemoteFolder` (new, changed, unchanged, removed, partial listing, failure, hidden state kept, albums, storage reserve, eviction); the schedule function; `TrustAnchors` with a test root; the real client against a fake Nextcloud on NanoHTTPD (login flow, `PROPFIND`, `GET`, redirects, `401`, `404`, a stalled download) with the contract check of the new endpoints and `RouteAccessTest`.
- Web UI: unit tests of the new view's logic and an end-to-end test with the fake Nextcloud added to the test server of the e2e run (connect, sync, photos appear, remote delete, disconnect).
- Devices: rows in `docs/DEVICE_TEST.md`: login and first sync with a real server and a Let's Encrypt certificate on both devices (time, memory), a second sync without downloads, a photo deleted on the server, Wi-Fi off during a sync and the recovery afterwards, the eviction and a new rendering, the jank of the slideshow during a sync, and disconnecting.

## Risks / Trade-offs

- **The app password sits unencrypted in app-private storage.** The same holds for the pairing tokens' hashes and for what `pictureframe` does; on a rooted device (both are) it can be read. Mitigation: it is an app password, revocable in Nextcloud, and the frame revokes it on disconnect.
- **Weak devices and the sync.** Probing a 30 MP photo and writing 10 MB files compete with the slideshow for CPU, memory bandwidth and the flash. Mitigation: one file at a time, low priority, a measured acceptance row for the jank during a sync. If it shows, the sync can pause while a transition runs.
- **`http` to a Nextcloud sends the password in clear.** Allowed because home servers often are, and said in the interface.
- **A listing of thousands of files** is thousands of entries in one `PROPFIND` per folder; parsing with DOM could be heavy on 200 MB of heap. Mitigation: a SAX parser that keeps only the four properties of each entry, and one folder at a time.
- **Changing the folder loses hidden states.** Accepted and stated in the interface.
- **Eviction can leave a photo that cannot be rendered when the server is away.** The photo stays visible (its renders are cached); only a new rendering waits for the server.

## Open Questions

- Which trust anchors besides `ISRG Root X1` are needed for the servers in use (a root of Sectigo or USERTrust)? The list is fixed when the first real servers are tried on both devices; the test of the anchors does not depend on it.
- Should the sync pause while a transition runs? Decide after the measurement on the Lenovo.
