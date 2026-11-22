# Design

## Context

See proposal.md for the motivation. The state on `main` (0.13.0, commit 7095820) that shapes the approach:

- **Delete.** `PhotoLibrary.delete` (`library/PhotoLibrary.kt:57-74`) removes the file with `file.delete()`, then the
  index entry and the renderings, notifies the old path and logs `Deleted: <path>` without the client. A missing file,
  also on unmounted storage, only drops the entry. `POST /photos:batch` takes 1 to 10,000 IDs (`api/LibraryRoutes.kt:265`)
  and calls `delete` per ID (:275); `DELETE /photos/{photoId}` is at :300.
- **Scan.** `LocalPhotoSource.listImageFiles` skips every folder and file whose name starts with a dot
  (`library/LocalPhotoSource.kt:177-178`), as it does for the upload staging folder `.upload`. The scan reads the index
  before it lists the folder (:64-65) and applies `entry.withStateOf(current)` (:101). `withStateOf` carries hidden
  state, comment and section but not `quarantinedVersion` (`library/PhotoIndex.kt:63-68`), and a probed entry without a
  previous one starts at version 1. `LocalPhotoSource.add` (:133) does the same for an upload.
- **Uploads.** The space checks before a 507 are at `PhotoLibrary.kt:95` (announced length), :195 (every 4 MiB while
  streaming) and :199 (at the end). `uniqueFile` is at :255; the move into place runs under `synchronized(this)`
  (:168-173).
- **Album states.** `albumStates` holds only settings that differ from the parent. It changes only through
  `LibraryEdit.setAlbumHidden` (`PhotoIndex.kt:372-379`), which drops the settings of the album's sub-albums. Nothing
  prunes the setting of an album that loses its last photo; the album only leaves `Library.albums`, so
  `PATCH /albums` refuses it with 404 (`PhotoLibrary.kt:52`).
- **Quarantine.** `FrameCore.quarantine` (`FrameCore.kt:578-583`) sets `quarantinedVersion` for the entry with the
  crashed version; `RenderCache.get` refuses a quarantined entry before any decoding.
- **Signed images.** `ImageSigner.sign(photoId, clientId)` signs any string (`auth/ImageSigner.kt`).
  `HttpPipeline.authorize` checks a signature only against the path parameter `photoId`, and `RouteAccessTest` pins
  `GET /photos/{photoId}/image` as the only signed route.
- **Pairing.** All pairings go through the private `PairingManager.create(deviceName, inviter)`
  (`auth/PairingManager.kt:235`), called from `pair` (:146, the code) and `pairWithSecret` (:181, the QR secret or an
  invitation; the inviter is known and checked to be paired at :175). `StoredClient` (`auth/ClientRegistry.kt:16-23`)
  has no origin, and the `Client` schema is `additionalProperties: false`. `JsonStore` ignores unknown keys but fails
  on an unknown enum value and then moves the file aside as `.broken-*`.
- **Sources.** `SourceKind.needsFolder` (`sources/SourceKind.kt:12`) marks an account. `normalizeFolder` accepts `/`
  and turns `""` into `/` (`sources/StoredSource.kt:285-290`). `SourceConfigStore.patch` (:160-204) validates and
  replaces under its lock; `SourceRegistry.patch` (`sources/SourceRegistry.kt:151-158`) calls it and then
  `configChanged()`. `PATCH /sources/{sourceId}` is a plain token route (`api/SourcesRoutes.kt:181`).
- **Login.** `NextcloudConnector` keeps one global `flow` (`nextcloud/NextcloudConnector.kt:41-47`); a new start by any
  device replaces it, and any device may poll it. `Flow` stores neither the client nor a source. On `done`, a known
  account goes through `SourceRegistry.reconnect`, which rotates the app password, marks the credentials as replaced,
  revokes the old one and starts a sync (`SourceRegistry.kt:95-118`); any other account becomes a new source
  (`NextcloudConnector.kt:124-129`). A login is valid for 20 minutes.
- **Request identity.** `Authenticator.clientFor` returns the client ID as a `String`
  (`auth/AccessControl.kt:11-17`), stored in the mutable `HttpRequest.clientId`; `Access` knows `PUBLIC`, `TOKEN` and
  `TOKEN_OR_IMAGE_SIGNATURE` (`http/Router.kt:4-13`).
- **Docs.** The README security model (README:263ff) says "Every paired device has full rights for now" and accepts
  the blind server-side request through sources (README:305-309, `nextcloud-hardening` design decision 12, which hands
  the re-check to P6). `devices.inviteHint` and `sources.folderHint` (`en.json:214`, :275) do not mention either risk,
  and the delete dialogs say "permanently" (`en.json:96`, :223).
- **Web UI.** `DevicesView.svelte:22-25` guesses the invited device by comparing IDs; P10 replaces that once
  `invitedBy` exists. `SkippedFiles` sits in `PhotosView.svelte:635`. The login poll lives in `SourcesView.svelte`.

## Goals / Non-Goals

**Goals:**

- No paired device can delete a local photo for good within 24 hours, or within 7 days without storage pressure.
- No paired device can widen what an account source reads without proving, at that moment, that it can log in to that
  account.
- Who paired how, and who deleted and restored what, is visible.
- The code is shaped so that roles (model B) can follow without rework.

**Non-Goals:**

- No roles, no `Access.OWNER`, no 403 `owner_required` (model B, a change of its own).
- No address filter for sources (the blind server-side request stays accepted, decision 18).
- No trash for remote photos: they are not deleted, only hidden.
- No way to empty the trash through the API or on the frame. USB stays the only way to free storage at once.
- No withdrawal of an invitation and no change of the invited-device recognition in `DevicesView` (P10).
- No change of the 100 MB reserve or of the storage of originals.

## Decisions

### 1. Model A now, shaped for B

- **What:** every token keeps the same rights. The two guards (trash, widening proof) are the only limits, and the
  security model states this together with the accepted residual risks (decision 17).
- **Shape for B:** the new code takes the acting client as an explicit `clientId: String` parameter from the route
  (`PhotoLibrary.delete(id, clientId)`, `restore(ids, clientId)`, `SourceRegistry.patch(id, patch, clientId)`,
  `NextcloudConnector.startLogin/pollLogin(…, clientId)`). It never reads `HttpRequest.clientId` deeper down. Model B
  can then replace the `String` with a fixed `Principal(id, role)` from `Authenticator.clientFor` at the route layer
  (as P11 wants) without touching the library, the trash or the sources. Grants and `deletedBy` key on the client ID,
  which a role does not change.
- **For B to cover:** under B, roles must also cover the endpoints added since the audit (`/display`, `/slideshow`,
  `/safe-mode/exit`, `/albums`, the brightness and schedule settings) and the new `/trash` routes. The trash and the
  widening proof stay under B as a second line, against a tapped owner token.
- **Alternative:** B now. About 30 operations, `Access`, the authenticator and 6 to 8 components would change; the
  owner chose A first.

### 2. Trash layout: the file tree is the source of truth

- **Where:** `<photo folder>/.trash/<trashId>/<relative path>`, with `trashId` = `<deletedAtMs>-<8 hex digits>` (13
  digits, a dash, 8 hex). `.trash/.nomedia` is written once, so gallery apps skip it.
- **Why inside the photo folder:** the move is a `renameTo` on the same file system, so it is atomic and takes no extra
  storage, also on an SD card. The scan already skips dot folders, so nothing in the trash comes back.
- **Why one folder per entry:** two photos with the same path (deleted, uploaded again, deleted again) never collide,
  and the deletion time and the original path are in the tree. A lost or stale state file therefore neither orphans
  files nor keeps storage forever.
- **Alternatives:**
  - A flat folder with renamed files needs the state file to know the original path.
  - A folder in the app's private storage may be on another volume, so the move would become a copy.

### 3. `TrashStore`: state next to the tree

- **State file:** `trash.json` in the state folder (`JsonStore`), keyed by `trashId`. It holds the whole `PhotoEntry`
  as it was at deletion (ID, version, dimensions, hidden state, comment, section, `quarantinedVersion`, position, file
  size and time) and `deletedBy`, the client ID or `null`.
- **Mirror:** `TrashStore` keeps an in-memory list of the entries with their sizes. It is built from the tree at start
  and at each purge check (decision 8), and updated by delete, restore and purge, so `GET /trash` lists without walking
  the folder. A record without a folder is dropped at that build; a folder without a record is listed without state. A
  file removed over USB is noticed at the next build at the latest; a restore checks that the file exists.
- **Locking:** delete, restore and purge run under `TrashStore`'s lock, inside `PhotoLibrary`'s lock where a file moves
  into or out of the photo folder.
- **Wiring:** `FrameCore` creates `TrashStore` next to `PhotoLibrary` (`FrameCore.kt:261`) and passes it in.

### 4. Delete into the trash

- **Order in `PhotoLibrary.delete(id, clientId)`:**
  1. Unknown ID → `false`; remote photo → 409 `read_only_source`, as today.
  2. `local.available()` false → 503 `storage_unavailable` (new; see below).
  3. A missing file only drops the entry, as today: there is nothing to keep.
  4. Under the library lock: make `.trash/<trashId>/<album>`, put the record, then move the file. If the move fails,
     drop the record and answer 500 `delete_failed`.
  5. Remove the index entry, the renderings and notify the old path, as today. Log `Moved to trash: <path> by
     <clientId>`.
- **Why refuse on unavailable storage:** today the entry is dropped while the file stays and comes back without its
  state after the remount. With the trash this would also skip the trash, so the request is refused instead.
- **Batch:** `POST /photos:batch` passes the client ID; each ID becomes its own entry, and a failed move puts the ID in
  `failed`.
- **Crash between the steps:** a record without its moved file is dropped at the next build; a moved file without its
  record is listed without state; an entry whose file moved but whose index entry stayed is removed by the next scan.

### 5. Restore

- **Per entry, in `PhotoLibrary.restore(ids, clientId)`:**
  1. Unknown ID or no file → `missing`.
  2. If the record exists and the file's size and modification time equal the saved entry's, the saved entry is put
     back as it is: no probe, same version, same `quarantinedVersion`. A quarantined photo is therefore never decoded
     for a restore.
  3. Otherwise (no record, or a file changed over USB) the file is probed outside the locks, like a photo copied over
     USB. With a record, the new entry gets version + 1 and the record's state through `withStateOf`, as for a changed
     file, which lifts a quarantine like any new version; without a record it is a new photo. A failed probe leaves the
     file in the trash and puts the ID in `failed`.
  4. Under the library lock: `uniqueFile` for the original path (a taken path gives a new name and so a new ID, the
     state copied over), `mkdirs` for the album, then the move and the index `put` inside one `index.edit`, so no state
     between them is ever published and a hidden photo never appears in the slideshow.
  5. Drop the record and the empty entry folder, remove the trash thumbnail, notify the new path, and log `Restored:
     <path> by <clientId>`.
- **Safe mode:** `POST /trash:restore` answers 503 `safe_mode`, as an upload does, because step 3 may decode. Listing
  works.
- **Unavailable storage:** 503 `storage_unavailable` for the whole request.
- **Result:** `restored` (pairs of trash ID and photo ID), `missing` and `failed`, so the web UI knows the new IDs.

### 6. A scan keeps what the frame has just put in place

- **The race:** a scan reads the index before it lists the folder. A restore between the two lets the scan probe the
  restored file as a new photo (version 1). Its edit then replaces the restored entry, and `withStateOf` drops the
  quarantine, so a quarantined photo would be rendered again. An upload has the same race, without the quarantine.
- **Fix:** in the apply step, an entry already in the index for that path with the same file size and modification time
  is kept as it is (requirement "Restore during a scan"). The same file needs no new entry.
- **Alternative:** the scan takes the library lock for its listing. A listing of thousands of files on an SD card would
  then block uploads.

### 7. Album state of a restored photo

- **Rule:** a restored photo takes the current effective state of its album, as a photo copied there would, and restore
  changes no setting. This needs no code: `albumStates` keeps the setting of an emptied album (Context), and
  `setAlbumHidden` on a parent drops it, as "Changing an album resets its sub-albums" requires.
- **Pinned:** a test checks that emptying an album keeps its setting, so that a later pruning cannot silently change
  restored photos.
- **Overview:** the album reappears in `GET /albums` with its state and the grouped overview shows it after the reload
  that the new library version triggers. Headings keep their remembered open state; a restore opens none.

### 8. Retention and purge

- **When:** at start, right after `removePartialUploads` (`FrameCore.kt:362`) and before the server accepts requests,
  and then hourly on the core's scope. Also in safe mode: purging decodes nothing.
- **What:** entries whose age is 7 days or more are deleted with their folder, records and trash thumbnails. Empty
  entry folders and records without a folder go too. Log `Trash purged: <n> entries, <MB> MB`.
- **Clock floor:** frames without a battery-backed clock can start at a date years in the past. A constant
  `TRASH_CLOCK_FLOOR` (a fixed date shortly before this release) separates plausible times:
  - While the clock is below the floor, nothing is purged by age.
  - An entry dated below the floor, or more than an hour ahead of a plausible clock, is re-stamped with the current
    time once the clock is plausible. Its folder is renamed and the record moves to the new `trashId`; a client that
    still holds the old ID gets it as `missing`.
- **Alternative:** the folder's modification time. MTP and some file systems do not keep it reliably.

### 9. Storage pressure frees only old entries

- **Where:** `TrashStore.makeRoom(neededBytes)` runs before each of the three 507 points of an upload
  (`PhotoLibrary.kt:95`, :195, :199). It deletes entries older than 24 hours, the oldest first, until the free space
  minus the reserve covers the need, and the check is made again. While streaming, the upload goes on if room was made.
- **Why only uploads:** trash and photo folder share a volume by construction. Originals and renderings live in the
  app's cache folder, which may be another volume, so freeing the trash would not help them.
- **Why 24 hours:** a hostile device can force a purge by uploading, but only of entries older than a day, which
  gives the owner a day to notice in the log and restore.

### 10. Trash thumbnails through signed URLs

- **Owner's choice:** thumbnails, against the recommendation, through signed URLs like the photo overview.
- **Route:** `GET /trash/{trashId}/thumb` with `Access.TOKEN_OR_IMAGE_SIGNATURE`. Only the thumbnail size exists: there
  is no display or full size of a trash entry.
- **Signature:** the signed subject is `trash/<trashId>`, so a photo's signature never opens a trash entry and the
  other way round. `HttpPipeline.authorize` derives the subject from the route's path parameter (`photoId` as it is,
  `trashId` with the prefix); `Authenticator.hasValidImageSignature` takes the subject instead of a photo ID.
  `RouteAccessTest` lists both signed routes.
- **Rendering:**
  - The thumbnail renders through the existing `RenderCache` and its decoder process, with a copy of the saved entry
    whose ID is `trash-<trashId>` and whose source is `trash`. `SourceOriginals.onFrame` resolves that source to the
    file in the trash, and the file names `trash-<trashId>-v<version>-…` are removed with `removeAll` on purge and
    restore.
  - The quarantine callback recognizes the `trash` source and marks the record quarantined instead of the index.
- **Never rendered:** an entry with a quarantined version, or without a record (its quarantine is unknown), has
  `thumb: null` and its route answers 503 `render_failed` without decoding. In safe mode the route answers 503
  `safe_mode`, as images do.
- **Caching:** an entry never changes, so the answer is `private, max-age=31536000, immutable` with an ETag.
- **Alternative:** no thumbnails (the plan's recommendation). The owner preferred recognizable entries; the delete
  dialog says that every paired device can see the trash.

### 11. Trash API

- **`GET /trash`:** `{ items: TrashEntry[], totalBytes }`, newest first. Each `TrashEntry` has:
  - `id`, `photoId` (derived from the path, also without a record), `path`, `album`;
  - `fileSize`, `deletedAt`, `purgeAfter` (deletion + 7 days; `null` while the clock is below the floor);
  - `deletedBy` (client ID or `null`), `quarantined`, and `thumb` (signed URL or `null`).
- **`POST /trash:restore`:** body `{ ids }` with 1 to 10,000 IDs. Answer `{ restored: [{ id, photoId }], missing,
  failed }`.
- **No delete:** there is no `DELETE` on `/trash` (405) or on an entry (404), and no endpoint to empty it.
- **Path parameter:** `TrashId` with the pattern `^[0-9]{13}-[0-9a-f]{8}$`.

### 12. Origin of a pairing

- **Fields:** `StoredClient.pairedVia: String?` (`code`, `qr`, `invite`; `null` for older pairings) and
  `invitedBy: String?` (the inviting client's ID, kept after that client is revoked). `ClientRegistry.create(name,
  pairedVia, invitedBy)`.
- **Where set:** at the one place every pairing passes, `PairingManager.create(deviceName, via, inviter)`: `pair` gives
  `code`, `pairWithSecret` gives `qr` for the session's secret and `invite` with the inviter for an invitation. The
  pairing log line names the origin as well.
- **Stored as a string:** an enum value a build does not know would make `JsonStore` move `clients.json` aside, and
  every pairing would be lost. When serving, a value outside the three becomes `null`, as `SourceErrorCodes.served`
  does for sync codes.
- **Contract:** `Client.pairedVia` (`anyOf: [enum, null]`, required) and `Client.invitedBy` (string or `null`,
  required). These names are agreed with P10 now; whether an invitation can be withdrawn
  (`GET /pairing/invites/current`) is left to P10.
- **Web UI:** names come from the client list by ID, because names are free text; an ID not in the list is "a removed
  device".

### 13. The widening rule

- **What:** a pure function `widens(prev, next)` for kinds with `needsFolder`:
  - `prev.folder` empty and `next.folder` set;
  - subfolders from off to on;
  - `next.folder` neither equal to `prev.folder` nor below it;
  - `next.folder` below `prev.folder` while `prev.includeSubfolders` is off.
- **Comparison:** by segment and with exact case, as Nextcloud paths are case-sensitive. `/` is above everything; a
  patch of `""` becomes `/` and therefore widens.
- **Free:** narrowing, name, enabled, removal of vanished photos, interval, budget, and every share-link source, whose
  token bounds what it can read.
- **Fields:** the 403 names `folder` and/or `includeSubfolders` in `fields`, so the web UI can show it at the field.

### 14. The guard and the proof

- **Guard:** `SourceConfigStore.patch(id, patch, guard)` calls `guard(prev, next)` after the field validation (a 400
  comes first) and before `replace`, under the store's lock, so the check and the change are one step.
  `SourceRegistry.patch(id, patch, clientId)` builds the guard: for a kind with `needsFolder`, if `widens` and
  `AccountGrants` has no valid grant for `(clientId, id)`, it throws `HttpException(403, "account_login_required", …)`.
  The store does not know kinds, which is why the registry builds it.
- **`AccountGrants`:** a new class in `sources/`, created by `FrameCore`. `NextcloudConnector` writes it and
  `SourceRegistry` reads it, so no cycle arises. It keys on `(clientId, sourceId)` and expires 30 minutes after the
  login on `Clock.monotonicMillis`, so a clock jump changes nothing. It lives in memory only; a restart asks for a new
  login. Removing a source drops its grants.
- **Which logins grant:** a completed login by a client for an account: one that adds the source (so the first folder
  needs no second login), one that renews its credentials, and a confirmation login (decision 16).
- **Alternatives (from the plan):**
  - `connectedBy` stored with the source: after the connecting device is revoked, nobody could widen any more.
  - A tap on the frame: needs new frame UI.
  - The fresh login proves the Nextcloud credentials at the moment of widening, which is rare.

### 15. One login flow per device

- **How:** `NextcloudConnector` keeps `Map<clientId, Flow>` instead of one `flow`. `startLogin(serverUrl, clientId,
  confirmSourceId)` replaces only that client's flow; `pollLogin(flowId, clientId)` finds a flow only among that
  client's, else `expired`. Expired flows are dropped when touched; their number is bounded by the paired devices.
- **Why bind instead of accepting the risk:** with one global flow another device could cancel a confirmation in
  progress by starting its own login, or poll it. The change is small.
- **Contract:** the `startSourceLogin` description changes from "a new one replaces the old" to one flow per device.

### 16. Confirmation login: check, then revoke the new password

- **Start:** `POST /sources/login` with `confirmSourceId` starts the flow on that source's stored server address;
  `serverUrl` may be left out and is ignored then. An unknown ID gets 404, a share-link source 400 at
  `confirmSourceId`. A full frame does not refuse it, because nothing is added.
- **Done:** the account is matched with the same rule as today: same user and server key, by the reported address and
  then by the start address.
  - **Match:** grant `(clientId, sourceId)`, revoke the new app password in the background (`revokeNew`), answer `done`
    with that `sourceId`. No `reconnect`: the source keeps its password, status and the stored result.
  - **Other account:** revoke the new password and answer `failed` with the new `SourceErrorCode` `other_account`;
    nothing is added.
  - **User name unreadable:** the existing failure path, with the revoke.
- **Why not reconnect** (owner's decision): `reconnect` rotates the password, marks the credentials as replaced and
  starts a sync. That is too much for a check and would reset a refused-credentials state.

### 17. Security model in writing

- **README** "Security model": the pairing bullet says that every paired device has the same rights, how it paired is
  shown, and "Unpair all devices" on the frame is the remedy. New bullets name the trash (7 days, 24 hours under
  storage pressure, visible to and restorable by every paired device, thumbnails included, freed at once only over USB)
  and the widening proof. Accepted residual risks:
  - revoking, renaming and inviting other devices;
  - settings, including `autoUpdateCheck` (local-only mode stays on the frame only);
  - hiding photos and overwriting comments and sections;
  - removing a source, which loses the hidden states, comments and sections of its photos and revokes the app password;
  - restoring and seeing photos another device deleted;
  - the log (`GET /logs`, open to every token) shows client IDs and photo paths.
- **README** "Photos from other sources": `/` is the whole account and is visible to every paired device, widening
  needs a login, and a share link suits a frame others use. The photo section mentions the trash.
- **Web UI:** the invitation dialog and the folder hint (owner's decision; the plan's short-term text change folded in).
- **Spec:** `admin-access` › "Equal rights of paired devices".

### 18. Blind server-side request stays accepted

- **Position:** a paired device can still point a source at any `http(s)` address the frame reaches, and the frame
  reports only status classes. It stays accepted under model A, for the reasons of `nextcloud-hardening` decision 12:
  - a paired device is in the LAN anyway and has full rights;
  - it reaches in addition only the frame's loopback services and, with a SIM, the carrier network;
  - a filter would need a bypass for the test servers on `127.0.0.1` and would exclude home servers on link-local
    addresses.
- **What changes:** nothing in the code. The widening proof does not apply, because adding a share needs no account.
  The README bullet now refers to this change, and the re-check moves to model B, when roles decide who may add
  sources.

### 19. Web UI

- **Trash panel:** `TrashPanel.svelte` in `PhotosView` next to `SkippedFiles`, shown when the trash is not empty. It
  is folded by default and gives the count and size. Rows show:
  - a lazily loaded thumbnail, or `QuarantinedPlaceholder` for a quarantined entry and a "no preview" placeholder
    without a thumbnail;
  - name, album and deletion time;
  - the deleting device ("this device", its name, "a removed device", "unknown"), from `GET /clients`;
  - the date until which it is kept, with "earlier when storage runs short, at the earliest a day after deleting".

  "Restore" per row and "Restore all" follow. The panel reloads with the library version, so the signed URLs stay
  fresh.
- **Texts:**
  - `photos.confirmDeleteOne/Many` name the trash, 7 days and that every paired device sees it;
  - `error.insufficient_storage` names the trash;
  - `devices.inviteHint` names the full rights; `sources.folderHint` gets the four points of the requirement.
- **Device list:** an origin line per device in `DevicesView`.
- **Wider folder:**
  - `SourceCard` catches 403 `account_login_required`, shows a note at the named field and a button "Confirm with
    Nextcloud".
  - It starts the login with `confirmSourceId`, opens the page with the existing http(s) guard, polls, and on `done`
    saves the same patch again.
  - The poll loop moves from `SourcesView.svelte` into a helper in `lib/sources.ts` that both use.
- **Codes:** `other_account` in `sourceErrorKeys`; `account_login_required` in `errorKeys` (checked by
  `errorCodes.test.ts`). Every text in `en.json` and `de.json`.

## Risks / Trade-offs

- **Deleting frees storage late.** On the Frameo a user who deletes to make room gets a 507 for up to a day.
  → The 507 text explains it; USB frees at once; the device row notes whether `.trash` is visible over MTP.
- **Every paired device can see deleted photos for 7 days** (owner's choice of thumbnails).
  → The delete dialog says so; restoring and deleting are logged with the device.
- **A hostile device can force an early purge** after 24 hours by uploading.
  → Documented; the log shows the deletion, and a day is left to restore.
- **`.trash` may be visible over USB,** as `.upload` was on one tablet.
  → Device row; the trash tolerates files deleted there.
- **A wrong clock purges too early or too late.**
  → The floor and the re-stamp (decision 8), with `TestClock` tests for both directions.
- **The scan change could keep a stale entry.**
  → It keeps an entry only when size and modification time match the file, the same test the scan uses to skip
  unchanged files.
- **The widening rule asks for a login on harmless changes,** such as another case or going back up after narrowing.
  → Intended; the card explains it and the login is one approval.
- **The grant is in memory:** a restart within the 30 minutes asks for a new login. Accepted.
- **A confirmation leaves a new app password on the server** if its revoke fails.
  → The revoke runs in the background and logs a failure, as for other unused passwords.
- **Restoring 10,000 entries in one request takes long** when files need a probe.
  → Unchanged files need none; the web UI restores in chunks of at most 500 IDs per request.
- **Residual risks under A** (decision 17): removing a source cannot be fully undone, and settings, names and pairings
  can be changed by any paired device.
  → Visible in the log; documented; model B later.
- **Disclosure:** the proposal describes weaknesses in a public repository.
  → Proposal and implementation land close together, and the release follows soon through the updater.

## Migration Plan

- **Pairings:** `clients.json` gains two string fields; old entries load with `null` and are listed with an unknown
  origin. After the update the owner reviews the device list and revokes unknown devices nobody uses (user action).
- **Trash:** `trash.json` and `.trash` appear with the first delete. `sources.json` and the index format do not change.
- **Sources:** existing account sources keep their folder. Only a later widening needs the login.
- **Rollback:** install the previous release over it. It ignores the new fields; its scan skips `.trash`, so photos in
  the trash stay there and are not purged until a newer version runs again or they are deleted over USB.
- **Release:** through the updater; the device rows run afterwards with the owner's consent.

## Open Questions

- The date of `TRASH_CLOCK_FLOOR`: any fixed date shortly before the release works; it is set when the release is cut.
