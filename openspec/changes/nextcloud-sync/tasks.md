# Tasks

## 1. Configuration and credentials

- [ ] 1.1 Add the configuration of the source (`nextcloud.json` in the state folder through `JsonStore`: server, user ID, login name, app password, enabled, folder, subfolders, remove vanished, interval, originals budget) with validation and partial changes; verify unit tests cover each limit of the spec (interval 5 to 1,440, budget 100 to 50,000 MB, folder normalized, only `http` and `https`) and a restart that reads the file back
- [ ] 1.2 Make the password unreachable outside the client: a view of the configuration without it for the API, and no password in any log line or error text; verify a test that fails with a wrong password and checks status text, log and the API view for the secret

## 2. Talking to Nextcloud

- [ ] 2.1 Deliver the trust anchors as resources of `:core` (the root of Let's Encrypt and the others the first servers need) and build the combined trust manager with the platform's; verify a test with an HTTPS server and a test root (accepted with the anchor, refused without) and that every delivered PEM file parses
- [ ] 2.2 Add `LoginFlow` (start, poll, user ID lookup, revoke of the app password) over `HttpURLConnection` with timeouts of 15 s and 60 s and no secret in error texts; verify tests against a fake Nextcloud on NanoHTTPD (test sources) for pending, done, expired and an unreachable server
- [ ] 2.3 Add `RemoteFolder` over WebDAV: `PROPFIND` with `Depth: 1` per folder read by a SAX parser, subfolders on request, redirects within the host, a streamed download to a `.part` file renamed when complete; verify tests with the fake server for a folder tree, ETags, a redirect from `http` to `https`, `401`, `404`, an error in a subfolder and a download that stalls
- [ ] 2.4 Check `./gradlew :core:check` (Android 21 API check, no `android.*` imports); verify it passes with the new package

## 3. The source and the library

- [ ] 3.1 Add the nullable `etag` to the index entry and the originals store (path, `.part` handling, last-use time, budget and eviction by the oldest use, 100 MB reserve); verify unit tests for eviction order, the reserve and a missing file
- [ ] 3.2 Implement `NextcloudSource` (listing, downloads one at a time at low priority, probe, one `index.edit` for the result, removal only of entries known at the start, hidden state and ID kept, skipped files remembered); verify tests with an in-memory `RemoteFolder` for the scenarios of `nextcloud-source` (new, unchanged, replaced, deleted with removal on and off, partial listing, failure, album from a subfolder, a file that cannot be decoded)
- [ ] 3.3 Route originals per source for `RenderCache` and fetch a missing Nextcloud original before rendering; verify a test in which an original is removed, a rendering is requested and the file is fetched again
- [ ] 3.4 Refuse deleting a Nextcloud photo (`409`, `read_only_source`, reported in `failed` for a batch), add `source` to the photo in the API, and remove the photos and originals of the source on disconnect; verify `LibraryApiTest` cases for the three delete scenarios of `photo-library`

## 4. Schedule and status

- [ ] 4.1 Implement the schedule (first sync after 30 s, interval, doubling retry from 60 s capped at the interval, reset on success, wake-up by configuration change, "sync now" and a network change, a mutex against overlaps) and the status (running, last time, ok, error, added, updated, removed); verify tests with a test clock for each delay, for no overlap and for the wake-ups
- [ ] 4.2 Keep the status across a restart (a file next to the configuration); verify a restart test

## 5. API

- [ ] 5.1 Describe the endpoints in `api/openapi.yaml` first (`GET`, `PATCH` and `DELETE /nextcloud`, `POST /nextcloud/login`, `GET /nextcloud/login/{flowId}`, `POST /nextcloud/sync`, the error codes `read_only_source`, `nextcloud_not_connected`, `nextcloud_unreachable` and `nextcloud_login_failed`, `source` of a photo), then the DTOs by hand and the routes; verify `RouteAccessTest` (all new endpoints need a token) and the contract check pass
- [ ] 5.2 Wire the source into `FrameCore` (start, stop, `onNetworkChanged`, disconnect); verify a `TestFrame` test with a fake Nextcloud that goes from connect over sync to the photo list and a refused delete
- [ ] 5.3 Run `npm run gen:api` and commit the types; verify `npm run typecheck`

## 6. Web UI

- [ ] 6.1 Add the Nextcloud view (connect through the login flow in a new tab with polling, configuration with the field errors, sync now, status, disconnect after a confirmation) and the entry in the navigation; verify the unit tests of the view's logic and `npm run lint`
- [ ] 6.2 Hide deleting for Nextcloud photos in the overview and the detail view and point to hiding; verify an e2e case
- [ ] 6.3 Add the texts to `en.json` and `de.json`, including a text for each new error code; verify the test that every error code of `:core` has a text passes
- [ ] 6.4 Add a fake Nextcloud to the test server of the e2e run and cover connect, first sync, a photo deleted on the server, a failing sync and disconnect; verify `npm run e2e`

## 7. Documentation and acceptance rows

- [ ] 7.1 Document the source in the README (connect, what is stored where, the storage budget, `http` caveat) and add the rows of the device acceptance to `docs/DEVICE_TEST.md`; verify every row names its spec scenario
- [ ] 7.2 Run `./gradlew check`, `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e`; verify all pass

## 8. Acceptance on the devices

- [ ] 8.1 Frameo (Android 6): connect to a real Nextcloud with a Let's Encrypt certificate and sync a folder; verify the rows of `docs/DEVICE_TEST.md` for the login, the first sync (time and memory) and a second sync without downloads
- [ ] 8.2 Lenovo (Android 5): the same rows; verify them
- [ ] 8.3 On both: a photo deleted on the server, Wi-Fi off during a sync with the recovery afterwards, eviction and a new rendering, the jank of the slideshow during a sync (measured as in `FINDINGS.md`), disconnecting; verify the rows are ticked or have a finding
- [ ] 8.4 Decide the two open questions of the design (further trust anchors, pausing the sync during transitions) from the measurements and record the result in `design.md`

## Workflow follow-up

- Propose follow-up changes: videos from Nextcloud, taking over `pictureframe` on the devices (settings, hidden photos).
- Archive the change with `/opsx:archive` after review and green acceptance.
