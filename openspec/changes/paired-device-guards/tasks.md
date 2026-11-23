# Tasks

This change is implemented after `library-performance` (PR #126) and builds on its storage reserve, `deleteAll`,
removal notification, image versions and tombstones, scan apply step and upload case resolver.

## 1. Contract first

- [x] 1.1 Confirm that `library-performance` is merged and archived (its `StorageReserve`, `deleteAll(ids)`,
  `LibraryEdit.newVersion`, removal listener and album case resolver exist on `main`). Then `api/openapi.yaml`, before
  any Kotlin:
  - `Client`: `pairedVia` (`anyOf: [{ enum: [code, qr, invite] }, null]`, required) and `invitedBy` (string or
    `null`, required), described as in design decision 12;
  - `GET /trash` (`listTrash`) with the schemas `TrashList` and `TrashEntry` (design decision 11: `path` and `photoId`
    nullable, `damaged`), with 401, 421, 500 and 503 (`storage_unavailable`);
  - `POST /trash:restore` (`restoreTrash`) with `TrashRestoreRequest` (1 to 10,000 IDs) and `TrashRestoreResult`,
    with 400, 401, 403, 408, 413, 415, 421, 500 and 503 (`storage_unavailable`, `safe_mode`);
  - `GET /trash/{trashId}/thumb` (`getTrashThumb`) with `security: [bearerAuth, imageSignature]`, the parameter
    `TrashId` (`^[0-9]{13}-[0-9a-f]{8}$`), 200 JPEG with ETag and `Cache-Control`, 304, 401, 404, 421, 500 and 503
    (`render_failed`, `safe_mode`);
  - `DELETE /photos/{photoId}` and `POST /photos:batch`: the descriptions say "moved to the trash" (their 503 is
    `library-performance`'s);
  - `SourceLoginRequest`: optional `confirmSourceId`, and `anyOf` requiring `serverUrl` or `confirmSourceId`;
  - `startSourceLogin`: one flow per device, the confirmation login, and a `"404": { $ref: NotFound }` response for an
    unknown `confirmSourceId`;
  - `pollSourceLogin`: only the starting device sees the flow, and the confirmation semantics;
  - `SourceErrorCode`: `other_account`;
  - `patchSource`: the widening rule; `Forbidden` names `account_login_required` besides the foreign origin;
    `InsufficientStorage` mentions the trash and names no fixed size; `info.description` says that a signature is valid
    for one photo or one trash entry.

  Then the DTOs by hand (`ClientDto`, `SourceLoginRequestDto`, new trash DTOs in `LibraryRoutes.kt`) and
  `npm run gen:api`. Verify: `npm run lint` in `webadmin/` is green (Redocly: every new operation has a 4xx);
  `npm run typecheck` is green with the generated types.

## 2. Origin of a pairing and equal rights

- [x] 2.1 `StoredClient.pairedVia` and `invitedBy` as nullable strings; `ClientRegistry.create(name, pairedVia,
  invitedBy)`; `PairingManager.create(deviceName, via, inviter)` sets `code` from `pair`, `qr` for the session secret
  and `invite` with the inviter in `pairWithSecret`; the pairing log line names the origin; `ClientDto` serves an
  unknown stored value as `null` (design decision 12). Verify, tests in `PairingTest`, `QrPairingTest`, `InviteTest`,
  `ClientRegistryPersistenceTest` and `PairingAdminTest` with the contract check on:
  - code, QR and invitation give `code`, `qr` and `invite`, and the invitation names the inviter, also after the
    inviter is revoked and after a restart (`admin-access` › Origin of a pairing › Paired with the code, › Paired by
    invitation);
  - a `clients.json` without the fields loads with `null` (› Pairing from before the update);
  - a `clients.json` with `pairedVia: "nfc"` keeps every pairing and serves `null` (› Origin from a newer version);
  - `DeviceLogTest` sees the origin in the pairing line and no secret;
  - a device paired by invitation revokes its inviter: the inviter gets 401 and the log names both IDs
    (`admin-access` › Equal rights of paired devices › Invited device revokes another one);
  - a client that revokes every new pairing loses its own token through `PairingAdmin.resetAll` ("Unpair all
    devices"), and the next pairing stays (› A hostile device keeps revoking).
- [x] 2.2 Web UI: an origin line per device in `DevicesView.svelte` (code, QR code, "invited by {name}", "invited by a
  removed device", "origin unknown"), the name looked up by ID in the same list; `devices.inviteHint` names the full
  rights; texts in `en.json` and `de.json`. Verify:
  - Vitest for the origin text of each case, an unknown inviter ID included;
  - e2e in `pairing.spec.ts`: after an invitation is redeemed, the list shows "invited by" with the inviting device's
    current name, and the invitation dialog shows the note on rights (`web-admin` › Paired devices › Invited device,
    `web-admin` › Invite a device › Show invitation).
- [x] 2.3 README "Security model": the pairing bullet says that every paired device has the same rights however it
  paired, that the device list shows how it paired, and that "Unpair all devices" on the frame is the remedy (design
  decision 17). Verify: the text is there and names no real device or household detail.

## 3. Trash in the library

- [x] 3.1 `TrashStore` (design decisions 2 and 3): the layout `.trash/<trashId>/<relative path>` with `.nomedia`; only
  pattern-named real directories are entries; one image file per entry, otherwise `damaged`; a walker that skips
  symbolic links (canonical paths, API 21) and no `deleteRecursively`; `trash.json` with `backupOnLoad`, the saved
  `PhotoEntry` and `deletedBy`; the mirror built on the core's scope after start, operations waiting for it; the wiring
  in `FrameCore`. Verify, a new `TrashStoreTest`:
  - a mirror built from a tree without `trash.json` lists every entry without state;
  - a record without a folder is dropped; a file removed from the tree leaves the list at the next build
    (`photo-library` › Trash without its saved state › Deleted from the trash over USB);
  - a folder `.Trash` and a stray file in `.trash` are not listed and survive a purge (› Entries of the trash › A
    folder of the user's own); an entry with two image files is `damaged` (› Entry with two files);
  - a symbolic link inside an entry to a folder outside the photo folder is neither followed when measuring nor when
    purging, and the outside folder keeps its files (skipped where the test file system has no symlinks);
  - the first build runs after the server has started, not before it.
- [x] 3.2 `PhotoLibrary.deleteAll(ids, clientId)` moves local files into the trash per design decision 4, outside the
  index lock, then one edit and one `notifyChanged(files)`; no `cache.remove`; `LibraryRoutes` passes the client ID
  (single delete and batch, `LibraryRoutes.kt:274`). Verify, `LibraryApiTest` and `QuarantineTest` with `TestFrame`:
  - after a delete the file is under `.trash/<trashId>/holiday/beach.jpg`, the entry and its renderings are gone, and
    a scan does not bring it back (`photo-library` › Delete › Delete photo, › Trash for deleted photos › Deleted photo
    leaves the slideshow);
  - a batch of a local and a Nextcloud photo moves one and fails the other, with one library version change
    (› Delete › Batch with Nextcloud photos);
  - a failed move leaves no record and puts the ID in `failed`;
  - a quarantined photo's record keeps its `quarantinedVersion` (› Quarantined photos › Delete a quarantined photo).
- [x] 3.3 `PhotoLibrary.restore(ids, clientId)` per design decision 5: IDs checked against the pattern before any
  `File`, the case resolver for albums, renames and puts in one edit, `edit.newVersion` for every restored entry,
  `quarantinedVersion = newVersion` when the saved entry was quarantined, hidden without a record, 503 in safe mode and
  while the folder is unavailable. Verify, JVM tests:
  - a hidden photo with a comment and a section comes back with the same ID, a new version and the same state, and a
    collector of `visiblePhotos` never sees it in between (`photo-library` › Restore from the trash › Restore a hidden
    photo with a comment);
  - the restored photo's thumbnail renders (a tombstone of the old version does not drop the job);
  - a quarantined photo comes back with a new version and still quarantined, and `JvmMediaEngine` counts no probe and
    no render (› Quarantined photos › Restore a quarantined photo);
  - a taken path gives `beach (1).jpg` with a new ID and the old state (› Path taken meanwhile);
  - a removed album folder is created again (› Album folder gone), and an album `Trip` restored after an upload into
    `trip` lands in `trip` (› Album folder in another letter case);
  - after a new `TestFrame` on the same folders, the entry restores with its ID and state (› Restore after a restart);
  - without `trash.json` the file is probed and added hidden; a probe crash leaves it in the trash and reports it as
    failed (› Trash without its saved state › State file lost);
  - a damaged entry and an ID like `../x` are `failed` and `missing`, and nothing outside `.trash` is touched;
  - in safe mode the request answers 503 `safe_mode` (› Restore from the trash › Safe mode).
- [x] 3.4 Scan keeps an entry the frame has put in place, inside `library-performance`'s apply step (design decision
  6). Verify: a `LocalPhotoSourceTest` case restores a quarantined photo between the scan's index read and its listing
  (through the `listFiles` seam); after the scan the photo has the version the restore gave it and is still
  quarantined, and the scan made no edit. Before keeping it, check once that the test fails without the fix
  (`photo-library` › Restore during a scan).
- [x] 3.5 Album state of a restored photo (design decision 7). Verify, `AlbumTreeTest`: the setting of an emptied
  album is kept; the two scenarios of `photo-library` › Album state of a restored photo pass as written.
- [x] 3.6 Purge per design decision 8 (after the first build, hourly, the clock floor `TRASH_CLOCK_FLOOR` fixed in this
  PR, and the re-stamp with a new trash ID). Verify, tests with `TestClock`:
  - an entry 7 days and 1 hour old is gone, one 6 days old stays (`photo-library` › Retention of the trash › After 7
    days);
  - an entry stamped below the floor is re-stamped once the clock passes it, gets a new ID and stays 7 more days
    (› Clock not yet set at the deletion);
  - while the clock is below the floor nothing is purged by age.
- [x] 3.7 `makeRoom(dir, neededFreeBytes)` per design decision 9 and its callers: the upload's streaming checks (not the
  announced length), `library-performance`'s below-reserve path of the render cache and originals after the cache's
  own trim, and the updater before `update_storage`. Verify, tests with `TestClock`, `FakePlatform.free` and
  `FakePlatform` total bytes:
  - an upload short of room deletes only entries older than 24 hours, oldest first and only as many as needed, and
    succeeds (`photo-library` › Trash under storage pressure › Upload makes room);
  - when all old entries together would not be enough, nothing is deleted and the upload gets 507 (› Not enough to
    gain); with only recent entries likewise (› Only recent entries);
  - an announced length beyond the free storage deletes nothing before the body arrives (› Where storage pressure
    frees the trash › Announced size of an upload);
  - `UpdateApiTest`: a download short of storage succeeds after old entries are deleted (› Update on a full frame);
  - a render below the reserve frees old trash entries only after the cache's own entries;
  - a `dir` reported on another volume (different total bytes) frees nothing.
- [x] 3.8 Log lines `Moved to trash`, `Restored` and `Trash purged` with client IDs (design decisions 4, 5, 8).
  Verify: a test with two clients finds both IDs and the path in the log and no token (`photo-library` › Log of trash
  actions › Deleted and restored by two devices). `./gradlew :core:check` is green (API 21 check included).

## 4. Trash API and thumbnails

- [x] 4.1 `GET /trash`, `POST /trash:restore` in `LibraryRoutes` (design decision 11). Verify, `TestHttp(checkContract
  = true)`:
  - the list after a delete has path, album, the deleting client's ID, `deletedAt` and `purgeAfter` 7 days later
    (`photo-library` › Trash list through the API › List after a delete); with unmounted storage it answers 503
    (› Storage unavailable);
  - restore of a known, an unknown and a damaged ID fills `restored`, `missing` and `failed`;
  - `DELETE /api/v1/trash` gives 405 and `DELETE /api/v1/trash/{id}` gives 404 (› Trash for deleted photos › No way to
    empty the trash);
  - `RouteAccessTest` stays green with the new routes in router and contract, its 401 loop substituting `{trashId}`.
- [x] 4.2 Thumbnails per design decision 10: the signed subject `trash/<trashId>`, the subject from the route parameter
  in `HttpPipeline`, `Authenticator.hasValidImageSignature(request, subject)`, rendering through `RenderCache` with the
  `trash` source in `SourceOriginals.onFrame`, `fileOf` and `release`, the quarantine callback for that source outside
  the cache lock, no URL and 503 without decoding for quarantined, damaged and record-less entries, 503 `safe_mode`,
  removal of the thumbnail on purge and restore. Verify:
  - `SignedImageTest` serves a trash thumbnail without a token while the signature is valid, and 401 for the signature
    of another trash entry, of a photo, and after the client is revoked (`admin-access` › Protected endpoints › Signed
    thumbnail of a trash entry; `photo-library` › Thumbnails in the trash › Signature of a photo for a trash entry);
  - a crashing `JvmMediaEngine` render marks the entry quarantined, a second request does not render, and its restore
    gives a quarantined photo (› Crash on a trash thumbnail);
  - `RouteAccessTest` lists the two signed routes;
  - after a restart, `library-performance`'s start sweep has removed `trash-*` renderings and the thumbnail renders
    again on request.

## 5. Trash in the web UI

- [x] 5.1 `TrashPanel.svelte` in `PhotosView` (design decision 19): count and size, rows with thumbnail or placeholder
  (quarantined, damaged, no preview), name, album, deletion time, deleting device, "kept until", "Restore" (not for a
  damaged entry) and "Restore all" in chunks of at most 500 IDs; the delete confirmations and
  `error.insufficient_storage` name the trash and no fixed size; texts in `en.json` and `de.json`. Verify:
  - Vitest for the device label (this device, named, removed, unknown), the "kept until" text, and the
    `insufficient_storage` text in both languages without "100 MB" (`web-admin` › Storage full and the trash);
  - e2e in `photos.spec.ts`: delete a photo, open the trash, restore it, and the photo is back in its album
    (`web-admin` › Trash in the interface › Restore a deleted photo); "Restore all" with three photos (› Restore all);
    the confirmation text names the trash (`web-admin` › Edit photos › Confirm delete);
  - e2e in `quarantine.spec.ts`: a deleted quarantined photo shows the placeholder and the browser requests no
    thumbnail (› Quarantined entry).
- [x] 5.2 README: the photo section says that deleting moves photos to the trash for 7 days; the security model gets
  the trash bullet (design decision 17); neither names "100 MB". Verify: both texts are there and match the spec.

## 6. Widening proof in the core

- [x] 6.1 `widens(prev, next)` (design decision 13). Verify, a table test: `/Photos`→`/`, →`/Photos/2024` with
  subfolders on and off before, →`/Other`, →`/photos`, subfolders switched on, empty→`/Photos`, `""` (stored as `/`),
  narrowing, and a share-link kind (`remote-sources` › Widening the folder of an account, all scenarios).
- [x] 6.2 `AccountGrants` and the guard (design decision 14): `SourceConfigStore.patch(id, patch, guard)`,
  `SourceRegistry.patch(id, patch, clientId)`, `SourcesRoutes` passes the client ID, 403 `account_login_required` with
  `fields`, grants dropped with the source and ignored for revoked clients. Verify, `SourcesApiTest` with the contract
  check on and `TestClock`:
  - a client without a grant sets `/` and gets 403; the folder stays and no sync starts (`remote-sources` › Proof for
    widening an account folder › Visitor widens to the whole account);
  - with a grant from a confirmation login the change is applied and `FakeClients.accountFolder` records `/`
    (› After a confirmation login);
  - at 31 minutes the grant has expired (› Proof expired), and another client's grant does not count (› Proof of
    another device);
  - connecting a new account and setting its first folder needs no second login (› First folder after connecting);
  - an invalid interval with a wider folder gives 400 naming the interval (› Invalid value and widening together);
  - narrowing, renaming and the interval need no grant (› Narrowing and other settings);
  - `SourceRegistryTest` and the existing `SourcesApiTest` cases pass with the new signatures.
- [x] 6.3 One login flow per device (design decision 15), with `FakeLoginFlow` approving per flow and per user.
  Verify, `NextcloudConnectorTest`: two clients log in at once, each flow approved separately, and both complete; a
  poll with another client's flow ID gives `expired` and the starting client still completes it (`nextcloud-source` ›
  Login flows per device, both scenarios); after the starting client is revoked, its flow is gone at the next start or
  poll.
- [x] 6.4 Confirmation login (design decision 16): `confirmSourceId` in the start, no `too_many_sources` for it, match
  against that source's connection only, grant plus `revokeNew` on a match, `failed`/`other_account` plus `revokeNew`
  otherwise, 404 for an unknown and 400 for a share-link source. Verify, `NextcloudConnectorTest` with `FakeLoginFlow`:
  - the same user gives `done` with the source's ID, `revoked` counts one, the stored connection and the result are
    unchanged, and no sync is requested (`nextcloud-source` › Confirm an account by login › Same account confirmed);
  - another `userId` gives `failed` with `other_account`, one revoke and no new source (› Another account approves);
  - with two account sources, a confirmation for the first approved as the second's user gives `other_account`, and
    neither source reconnects (› Account of another source);
  - a share-link source gives 400 and an unknown ID 404, and no flow starts (› Share-link source);
  - a confirmation on a full frame starts.
- [x] 6.5 `./gradlew :core:check` is green. Verify: the command ends with exit 0.

## 7. Wider folder in the web UI

- [x] 7.1 The poll loop moves from `SourcesView.svelte` into a helper in `lib/sources.ts`; `SourceCard` handles 403
  `account_login_required` with the note at the named field and "Confirm with Nextcloud", starts the login with
  `confirmSourceId`, opens it through the existing http(s) check, and saves the same patch again on `done`;
  `other_account` in `sourceErrorKeys`, `account_login_required` in `errorKeys`; `sources.folderHint` with the four
  points; texts in `en.json` and `de.json`. The dev server's approve hook takes `user=` to approve as another Nextcloud
  user. Verify:
  - Vitest for the helper (pending, done, failed, expired) and `errorCodes.test.ts` green;
  - e2e in `sources.spec.ts`: set `/`, save, confirm, approve, and the card shows `/` saved (`web-admin` › Confirm a
    wider folder › Whole account confirmed); approve as `user=other` and the card shows the German text for
    `other_account` and keeps the form (› Other account); the folder hint is shown (`web-admin` › Folder of an account
    explained);
  - the existing e2e "An account: connect, first sync, …" passes unchanged (first folder without a second login).
- [x] 7.2 README: "Photos from other sources" says that `/` is the whole account and visible to every paired device,
  that a wider folder needs a login on the device that sets it, and recommends share links for frames others use; the
  security model gets the widening bullet, the list of accepted residual risks (confirmation phishing and a forward
  clock jump included), and the bullet "Sources point anywhere (accepted)" refers to this change and to roles as the
  point to re-check (design decisions 17 and 18). Verify: the texts are there; `cloud.example.org` stays the only
  example address.

## 8. Checks

- [ ] 8.1 `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e`
  with an own `OPENFRAME_E2E_PORT` (for example 8190) are green. Verify: the command outputs.
- [ ] 8.2 `openspec validate paired-device-guards --strict` exits 0 without warnings. Verify: the output.

## 9. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 9.1 The main session adds rows to the private device checklist, each with its spec reference, for the Lenovo and
  the Frameo:
  - delete a test photo uploaded for the row: it leaves the slideshow and its folder in the USB (MTP) view
    (`photo-library` › Trash for deleted photos › Deleted photo leaves the slideshow);
  - note whether `.trash` is visible over MTP right away, after a media scan and after a restart;
  - restore after a restart, also for a photo in an album (› Restore from the trash › Restore after a restart);
  - the trash thumbnail appears in the web UI on a phone (› Thumbnails in the trash › Thumbnail of a deleted photo);
  - note whether the photo folder, the cache folder and the update folder report the same volume, as `makeRoom`'s
    test assumes (› Where storage pressure frees the trash), read-only;
  - after the update, existing devices are listed with an unknown origin (`admin-access` › Origin of a pairing ›
    Pairing from before the update).

  Verify: the rows exist with their references.
- [ ] 9.2 Run the rows of 9.1 after the release, updating through the updater, with the owner's consent for the update
  and for each test photo. Verify: results entered; anything that differs gets a finding in the private findings list.

## Workflow follow-up

- After the update, the owner reviews the device list on both frames and revokes pairings with an unknown origin that
  nobody uses.
- Archive the change after the device rows of 9.2 are done.
