# Tasks

## 1. Contract first

- [x] 1.1 `api/openapi.yaml`: replace `/nextcloud*` by `/sources` (list, add a share), `/sources/login`, `/sources/login/{flowId}`, `/sources/{id}` (get, patch, delete) and `/sources/{id}/sync`; schemas for source, source state, share request and settings patch; error codes `too_many_sources`, `source_exists`, `source_no_folder`; photo ID pattern `^[a-z][a-z0-9]*-[0-9a-f]{40}$`; verify: `npx @redocly/cli lint api/openapi.yaml` (rules in `redocly.yaml`) passes
- [x] 1.2 Run `npm run gen:api` in `webadmin/`; verify: `webadmin/src/lib/api/schema.d.ts` has the new types and `git diff --stat` shows only generated changes there

## 2. Source model in `:core`

- [x] 2.1 `StoredSource` (common settings plus `Account` or `Share` connection), `SourceKind` (`remoteFolder`, `revoke`, `needsFolder`, `listsFlat`) and a config store for a list of sources with validation of each field, the limit of eight and partial patches, ported from `NextcloudConfigStore`; verify: unit tests for each setting range, the unknown field, the ninth source (`too_many_sources`) and the hidden password in `toString()` pass
- [x] 2.2 `RemoteSource` from `NextcloudSource`: one instance per source, own originals folder `cache/sources/<id>`, own budget; verify: the existing sync tests (new photo, ETag unchanged, replaced, vanished, failed listing, storage) pass against it unchanged in meaning, plus a test with two sources that removing one leaves the other's photos and originals
- [x] 2.3 Scheduler per source with `SyncSchedule` reused and one lock for all syncs on the device; verify: tests with a fake clock: a failing source retries after 60 s while the other keeps its interval, two sources never sync at the same time, a failed sync frees the lock
- [x] 2.4 `SourceRegistry` (add, change, remove, look up by ID, wake all on a network change) and `SourceOriginals` finding the source of a photo; `FrameCore` builds the registry instead of the single Nextcloud objects; verify: `FrameCoreTest` and the library tests pass, and a photo of `local` and of two sources renders
- [x] 2.5 Migration at load: `nextcloud.json` and `nextcloud-status.json` become one source with ID `nextcloud` (name = host), `cache/nextcloud` moves to `cache/sources/nextcloud`, the old file stays until that source is removed; verify: a test with an old `nextcloud.json`, an index with a hidden `nextcloud-<sha1>` photo and cached originals shows the same photo IDs, hidden state and originals after the migration, and that removing the source deletes `nextcloud.json`

## 3. Share links

- [x] 3.1 Link parser: `https://host/s/<token>`, with `/index.php/s/<token>`, with a sub-path, with a trailing slash, with `http`; refuses other schemes, no `/s/`, empty token; verify: unit tests for each case pass
- [x] 3.2 `ShareFolder` as a `RemoteFolder` over `public.php/webdav/` with Basic `token:password`, the same listing and download code as `WebDavFolder` (share the common part, do not copy it); verify: tests with the fake server of the existing WebDAV tests list subfolders, send the share credentials and download by ETag
- [x] 3.3 Adding a share: one `PROPFIND Depth: 0` classifies folder, single file (`share_not_a_folder`), password needed, password wrong, not found, unreachable; field errors `link` and `password` as 400, 502 for an unreachable server, 409 `source_exists` for the same server and token; nothing is stored on failure; verify: a test per outcome, including that nothing is stored
- [x] 3.4 Withdrawn share and changed password at sync time: the status of the source shows the readable error and no photo is removed; verify: tests where the fake server answers 404 and 401 during a sync with photos in the index
- [x] 3.5 Secrecy: the API, the log and error texts contain neither the share token nor the share password nor the app password; verify: a test that adds a share and an account with recognisable secrets, provokes a failed sync and an error response, and searches the API responses and the log for them

## 4. API routes

- [x] 4.1 `SourcesRoutes` replace `NextcloudRoutes`; the service for the login flow keeps its rules (one flow, 20 minutes, replace credentials of a known server and user); verify: `TestHttp(..., checkContract = true)` tests for every endpoint and status in the contract pass
- [x] 4.2 `RouteAccessTest` and token tests: every `/sources*` endpoint needs a token; verify: `./gradlew :core:test` passes and the test fails if a `/sources` route is left open
- [x] 4.3 Update the error text list for the new codes; verify: the test that every code of `:core` has a text in the web UI fails until step 5.2 and passes after it

## 5. Web UI

- [x] 5.1 `SourcesView` (list of cards, add form with the account and share link tabs, settings, sync, remove with confirmation), route `sources` with a redirect from `nextcloud`, navigation entry; verify: `npm run typecheck` and `npm run lint` pass
- [x] 5.2 Texts in `en.json` and `de.json`, error codes for the new API errors, field errors at `link` and `password`; verify: the i18n test that both languages have the same keys and that every API code has a text passes
- [x] 5.3 Unit tests for the helpers of the view (link check on the client side, status text) and an e2e test: add a share, add a second source, sync one, remove one, wrong password at the password field; verify: `npm test` and `npm run e2e` pass

## 6. Device checks and documentation

- [x] 6.1 Rows in `docs/DEVICE_TEST.md`: share link without an account on the Lenovo and the Frameo (with and without a password), two sources side by side with one failing, update from a build that has a Nextcloud connection (photo IDs and hidden state kept), share withdrawn while the frame runs; verify: the rows exist with the spec scenarios as reference
- [x] 6.2 Run the rows on the Lenovo, the Frameo and the Galaxy Tab S7 with a share link that the user gives; ask before installing and before changing anything on a device; verify: results entered in the rows, a finding in `docs/FINDINGS.md` for anything that differs
- [x] 6.3 Document the follow-up providers (table of design.md, decision 8) in `docs/` or the README where the sources are described; verify: the text names the blocker of each provider

## 7. Close out

- [x] 7.1 `./gradlew check`; verify: it ends green (formatting, tests, API 21 check, Android Lint)
- [x] 7.2 `openspec validate multi-source-share-links`; verify: exit 0 without warnings

## Workflow follow-up

- Archive the change with `/opsx:archive` after the device checks.
- Separate changes, in this order of effort: a plain WebDAV address as a source, Dropbox, iCloud shared albums, Google Drive.
