# Design

## Context

See proposal.md for the motivation. This change is implemented after `library-performance` (P8, PR #126) and builds
on it; the line references below are to `main` (0.13.0, commit 7095820), the behaviour assumed is that of `main` with
`library-performance` applied.

**From `library-performance`** (its design decisions in brackets), which this change uses and does not redefine:

- **Storage reserve** (1): `StorageReserve`, about 600 MiB on the reference frames, replaces the 100 MB of
  `PhotoLibrary.RESERVE_BYTES`; uploads, downloads of originals and renderings ahead keep it. The render cache trims
  itself below the reserve (2). The updater keeps its own rule (`UpdateManager.kt:483-485`, `update_storage`).
- **Removal notification** (4): every way a photo leaves the index removes its renderings through one listener of
  `PhotoIndexStore.edit`; `PhotoLibrary.delete` no longer calls `cache.remove`. The start removes renderings of photo
  IDs that are not in the index.
- **Image versions and tombstones** (5): an entry new in the index gets `edit.newVersion` (the library version before
  the edit plus 1), so a file that comes back at a removed photo's path gets a version its ID never had; the cache
  drops a job whose version is not above the highest removed version of that ID ("Stable photo IDs").
- **Lock order** (6a): `SourceRegistry` → index → cache → `OriginalsStore`; nothing under the cache lock calls
  `index.edit`, `SourceOriginals` or `onCrashed`.
- **Scan** (7): no edit when nothing changed, 5 minutes while the display is off.
- **Batch delete** (8): `PhotoLibrary.deleteAll(ids)` classifies outside the index lock, deletes the files, then one
  edit and one `notifyChanged(files)`; `delete(id)` is `deleteAll` of one ID. While the photo folder is unavailable
  every delete answers 503 `storage_unavailable` for the whole request ("Photo folder kept").
- **Uploads** (9): album levels resolved case-insensitively against existing folders; all files renamed under one
  lock, then one `LocalPhotoSource.addAll` edit.

**State on `main` that shapes the rest:**

- **Scan.** `LocalPhotoSource.listImageFiles` skips every folder and file whose name starts with a dot
  (`library/LocalPhotoSource.kt:177-178`), as for `.upload`. The scan reads the index before it lists the folder
  (:64-65) and applies `entry.withStateOf(current)` (:101), which carries hidden state, comment and section but not
  `quarantinedVersion` (`library/PhotoIndex.kt:63-68`).
- **Uploads.** The space checks before a 507 are at `PhotoLibrary.kt:95` (announced length), :195 (every 4 MiB while
  streaming) and :199 (at the end). `uniqueFile` is at :255.
- **Album states.** `albumStates` changes only through `LibraryEdit.setAlbumHidden` (`PhotoIndex.kt:372-379`), which
  drops the settings of the album's sub-albums. Nothing prunes the setting of an album that loses its last photo; the
  album only leaves `Library.albums`, so `PATCH /albums` refuses it with 404 (`PhotoLibrary.kt:52`).
- **Quarantine.** `FrameCore.quarantine` (`FrameCore.kt:578-583`) sets `quarantinedVersion`; `RenderCache.get` refuses
  a quarantined entry before any decoding. `RenderCache` gets originals through `SourceOriginals.onFrame`, `fileOf`
  (fetch) and `release`.
- **Signed images.** `ImageSigner.sign(photoId, clientId)` signs any string. `HttpPipeline.authorize` checks a
  signature only against the path parameter `photoId`; `RouteAccessTest` pins `GET /photos/{photoId}/image` as the
  only signed route and substitutes `{photoId}` in its 401 loop.
- **Pairing.** All pairings go through the private `PairingManager.create(deviceName, inviter)`
  (`auth/PairingManager.kt:235`), called from `pair` (:146, the code) and `pairWithSecret` (:181, the QR secret or an
  invitation, the inviter checked at :175). `StoredClient` (`auth/ClientRegistry.kt:16-23`) has no origin. `JsonStore`
  ignores unknown keys but fails on an unknown enum value and moves the file aside as `.broken-*`.
- **Sources.** `SourceKind.needsFolder` (`sources/SourceKind.kt:12`) marks an account. `normalizeFolder` accepts `/`
  and turns `""` into `/` (`sources/StoredSource.kt:285-290`). `SourceConfigStore.patch` (:160-204) validates and
  replaces under its lock; `SourceRegistry.patch` (`sources/SourceRegistry.kt:151-158`) then calls `configChanged()`.
- **Login.** `NextcloudConnector` keeps one global `flow` (`nextcloud/NextcloudConnector.kt:41-47`), which any device
  may replace or poll. On `done` a known account goes through `SourceRegistry.reconnect` (rotates the password, marks
  the credentials replaced, revokes the old one, starts a sync, `SourceRegistry.kt:95-118`); another account becomes a
  new source (`NextcloudConnector.kt:124-129`). A login is valid for 20 minutes. `FakeLoginFlow` has one global
  `approved` flag and one `userId`.
- **Request identity.** `Authenticator.clientFor` returns the client ID as a `String` (`auth/AccessControl.kt:11-17`),
  stored in the mutable `HttpRequest.clientId`.
- **Docs.** The README security model (README:263ff) says "Every paired device has full rights for now" and accepts
  the blind server-side request (README:305-309, `nextcloud-hardening` decision 12, which hands the re-check to P6).
  `devices.inviteHint`, `sources.folderHint` and the delete dialogs (`en.json:214`, :275, :96, :223) mention neither
  risk; the dialogs say "permanently".
- **Web UI.** `DevicesView.svelte:22-25` guesses the invited device by comparing IDs (P10 replaces it). `SkippedFiles`
  sits in `PhotosView.svelte:635`; the login poll lives in `SourcesView.svelte`.

## Goals / Non-Goals

**Goals:**

- No paired device can delete a local photo for good within 24 hours, or within 7 days without storage pressure.
- No paired device can widen what an account source reads without proving, at that moment, that it can log in to that
  account.
- Who paired how, and who deleted and restored what, is visible.
- The code is shaped so that roles (model B) can follow without rework.

**Non-Goals:**

- No roles, no `Access.OWNER`, no 403 `owner_required` (model B, a change of its own).
- No address filter for sources (decision 18).
- No trash for remote photos: they are not deleted, only hidden.
- No way to empty the trash through the API or on the frame; USB stays the only way to free storage at once.
- No withdrawal of an invitation and no change of the invited-device recognition in `DevicesView` (P10).
- Nothing `library-performance` owns: the reserve's size, versions and tombstones, the 503 on deletes, batch and upload
  mechanics, the scan cadence.

## Decisions

### 1. Model A now, shaped for B

- **What:** every token keeps the same rights. The two guards (trash, widening proof) are the only limits beyond what
  no device may do through the API (local-only mode), and the security model states this together with the accepted
  residual risks (decision 17).
- **Shape for B:** the new code takes the acting client as an explicit `clientId: String` parameter from the route
  (`PhotoLibrary.deleteAll(ids, clientId)`, `restore(ids, clientId)`, `SourceRegistry.patch(id, patch, clientId)`,
  `NextcloudConnector.startLogin/pollLogin(…, clientId)`) and never reads `HttpRequest.clientId` deeper down. Model B
  can then replace the `String` with a fixed `Principal(id, role)` from `Authenticator.clientFor` at the route layer
  (as P11 wants). Grants and `deletedBy` key on the client ID, which a role does not change.
- **For B to cover:** the endpoints added since the audit (`/display`, `/slideshow`, `/safe-mode/exit`, `/albums`, the
  brightness and schedule settings) and the new `/trash` routes. Trash and widening proof stay under B as a second
  line, against a tapped owner token.
- **Alternative:** B now; about 30 operations, `Access`, the authenticator and 6 to 8 components would change. The
  owner chose A first.

### 2. Trash layout: the file tree is the source of truth

- **Where:** `<photo folder>/.trash/<trashId>/<relative path>`, `trashId` = `<deletedAtMs>-<8 hex digits>`, matching
  `^[0-9]{13}-[0-9a-f]{8}$`. `.trash/.nomedia` is written once, so gallery apps skip it.
- **What is an entry:** only a real directory directly in `.trash` whose name matches the pattern. Everything else in
  `.trash` (a user's own `.Trash`, stray files) is never listed, moved or deleted. An entry holds exactly one image
  file; none or several make it `damaged`: listed, purged by age, never restored.
- **No symlinks followed:** listing, measuring and purging use one walker that skips a symbolic link, recognized on
  API 21 by comparing `canonicalPath` with the path built from the parent's canonical path. `deleteRecursively` is not
  used, because it follows linked directories. A restore checks every ID against the pattern before it builds a `File`.
- **Why inside the photo folder:** the move is a `renameTo` on the same file system, atomic and without extra storage,
  also on an SD card; the scan already skips dot folders.
- **Why one folder per entry:** two photos with the same path never collide, and the deletion time and original path
  are in the tree, so a lost or stale state file neither orphans files nor keeps storage forever.
- **Alternatives:** a flat folder with renamed files needs the state file for the original path; a folder in the app's
  private storage may be on another volume, so the move would become a copy.

### 3. `TrashStore`: state next to the tree

- **State file:** `trash.json` in the state folder, a `JsonStore` with `backupOnLoad = true`, keyed by `trashId`. It
  holds the whole `PhotoEntry` at deletion (ID, version, dimensions, hidden state, comment, section,
  `quarantinedVersion`, position, file size and time) and `deletedBy`.
- **Mirror:** an in-memory list of the entries with their sizes, built from the tree on the core's scope after start
  (not before the server starts, a large trash on an SD card must not delay it) and again at each purge check
  (decision 8). Trash operations wait for the first build. Delete, restore and purge update the mirror. A record
  without a folder is dropped at a build; a folder without a record is listed without state; a file removed over USB is
  noticed at the next build at the latest, and a restore checks that the file exists.
- **Unavailable storage:** `GET /trash` and restore answer 503 `storage_unavailable`, as uploads do.
- **Wiring:** `FrameCore` creates `TrashStore` next to `PhotoLibrary` (`FrameCore.kt:261`) and passes it in.

### 4. Delete into the trash, on top of `deleteAll`

- **`deleteAll(ids, clientId)`** keeps `library-performance`'s shape and its 503 for an unavailable folder. For a local
  photo whose file exists it moves the file into the trash instead of deleting it, outside the index lock and under
  `TrashStore`'s lock: make `.trash/<trashId>/<album>`, put the record, `renameTo`. A failed move drops the record and
  puts the ID in `failed` (500 `delete_failed` for a single delete). A file already gone has nothing to keep and counts
  as deleted, as there.
- **Then**, as there: one edit removes the entries, the removal listener removes their renderings (no `cache.remove`
  here), one `notifyChanged(files)` for the old paths. One log line per photo: `Moved to trash: <path> by <clientId>`.
- **Crash between the steps:** a record without its moved file is dropped at the next build; a moved file without its
  record is listed without state; an entry whose file moved but whose index entry stayed is removed by the next scan.

### 5. Restore with a new version

- **Per request, in `PhotoLibrary.restore(ids, clientId)`:**
  1. An ID that does not match the pattern, an unknown ID or an entry without its file is `missing`; a damaged entry is
     `failed`.
  2. **Unchanged file** (record present, size and modification time equal the saved entry's): no probe. A quarantined
     photo is therefore never decoded for a restore.
  3. **Changed file or no record:** the file is probed outside the locks, like a photo copied over USB. A failed probe
     leaves it in the trash and puts the ID in `failed`.
  4. Under the library lock: each album path goes through `library-performance`'s case resolver (so `mkdirs` on the
     case-insensitive FUSE folder and the indexed path agree), then `mkdirs`, then `uniqueFile` for the original name,
     also against the other targets of the request (a taken path gives a new name and so a new ID).
  5. One `index.edit`, holding the library and trash locks (order: library → trash → index), renames each file into
     place and puts its entry; a failed rename puts that ID in `failed` and leaves the file in the trash. Renaming
     inside the edit means no scan sees the file before its entry, so a hidden photo never appears in the slideshow.
  6. Drop the records and empty entry folders, remove the trash thumbnails, one `notifyChanged(files)`, and one log
     line per photo: `Restored: <path> by <clientId>`.
- **The entry put in step 5:**
  - **Version:** always `edit.newVersion`, as for any entry new in the index (`library-performance` decision 5). A
    restore is a file coming back at a removed photo's path; its tombstone holds the old version, so the old version
    must not come back, or its renderings would be dropped.
  - **Unchanged file:** the saved entry with the new version and ID and path from step 4, keeping hidden state,
    comment, section and position. The quarantine is kept explicitly: `quarantinedVersion = newVersion` when the saved
    entry was quarantined, else `null`.
  - **Changed file with a record:** the probed entry with the new version and the record's state through
    `withStateOf`, as for a changed file; a new file lifts the quarantine, as any new version does.
  - **No record** (for example `trash.json` moved aside as `.broken-*`): the probed entry, hidden. Its earlier hidden
    state is unknown, and showing a photo someone deleted would be the wrong default.
- **Safe mode** (owner's default): `POST /trash:restore` answers 503 `safe_mode`, as an upload does, because step 3 may
  decode. Listing works.
- **Result:** `restored` (pairs of trash ID and photo ID), `missing` and `failed`, so the web UI knows the new IDs.

### 6. A scan keeps what the frame has just put in place

- **The race:** a scan reads the index before it lists the folder. A restore between the two lets the scan probe the
  restored file as new; its edit would then replace the restored entry with another version and, through
  `withStateOf`, without the quarantine, so a quarantined photo would be rendered. An upload has the same race,
  without the quarantine.
- **Fix:** inside `library-performance`'s apply step, an entry already in the index for that path with the same file
  size and modification time is kept as it is, with the version the restore gave it (requirement "Restore during a
  scan"). The same file needs no new entry, and a scan that keeps everything makes no edit.
- **Alternative:** the scan takes the library lock for its listing; a listing of thousands of files on an SD card would
  then block uploads and restores.

### 7. Album state of a restored photo

- **Rule:** a restored photo takes the current effective state of its album, as a photo copied there would, and restore
  changes no setting. This needs no code: `albumStates` keeps the setting of an emptied album (Context), and
  `setAlbumHidden` on a parent drops it, as "Changing an album resets its sub-albums" requires.
- **Pinned:** a test checks that emptying an album keeps its setting, so that a later pruning cannot silently change
  restored photos.
- **Overview:** the album reappears in `GET /albums` after the reload that the new library version triggers. Headings
  keep their remembered open state; a restore opens none.

### 8. Retention and purge

- **When:** after the first mirror build on the core's scope, and then hourly. Also in safe mode: purging decodes
  nothing.
- **What:** entries aged 7 days or more go with their folder (through the walker of decision 2), records and trash
  thumbnails; empty entry folders and records without a folder go too. Log `Trash purged: <n> entries, <MB> MB`.
- **Clock floor:** frames without a battery-backed clock can start at a date years in the past. A constant
  `TRASH_CLOCK_FLOOR`, a fixed date set in the implementation PR, separates plausible times:
  - While the clock is below the floor, nothing is purged by age.
  - An entry dated below the floor, or more than an hour ahead of a plausible clock, is re-stamped with the current
    time once the clock is plausible: its folder is renamed and the record moves to the new `trashId`. The trash ID
    changes then (owner's default); a client that still holds the old ID gets it as `missing`.
- **Alternative:** the folder's modification time; MTP and some file systems do not keep it reliably.

### 9. Storage pressure frees only old entries, and only when it helps

- **`TrashStore.makeRoom(dir, neededFreeBytes)`:** let `eligible` be the entries older than 24 hours, oldest first.
  Only if `freeBytes(dir) + bytes(eligible)` reaches `neededFreeBytes` does it delete the oldest of them until the need
  is met, and return true; otherwise it deletes nothing and returns false, and the caller refuses as before. A trash
  wiped for nothing would be the worst outcome.
- **Same volume:** the trash helps only storage on its own volume. Without `java.nio.file` on API 21, `makeRoom` treats
  `dir` as the trash's volume when `totalBytes` and `freeBytes` of `dir` and of the photo folder, read together, are
  equal; otherwise it returns false at once. On the reference frames cache, originals, updates and photo folder share
  the data volume.
- **Callers:**
  - **Uploads:** at the streaming checks (`PhotoLibrary.kt:195`, :199) with the storage reserve; while streaming, the
    upload goes on if room was made. Not at the check of the announced length (:95): a client could announce any
    length and make the frame delete entries for an upload that never comes.
  - **Render cache and originals:** in `library-performance`'s below-reserve path, after the render cache has trimmed
    its own entries (they render again; trash entries do not come back), before a rendering ahead or a download of an
    original is refused for the reserve.
  - **Updater:** before the `update_storage` refusal (`UpdateManager.kt:484`), with the updater's own need, so a
    security update is not blocked by the trash.
- **Locks:** `makeRoom` takes only `TrashStore`'s lock and removes thumbnails through `cache.removeAll` after leaving
  it. It is never called while holding the cache lock or an `OriginalsStore` lock (decision 10).
- **Why 24 hours:** a hostile device can force a purge by uploading, but only of entries older than a day, which gives
  the owner a day to notice in the log and restore.

### 10. Trash thumbnails through signed URLs

- **Owner's choice:** thumbnails, against the recommendation, through signed URLs like the photo overview.
- **Route:** `GET /trash/{trashId}/thumb` with `Access.TOKEN_OR_IMAGE_SIGNATURE`; only the thumbnail size exists.
- **Signature:** the signed subject is `trash/<trashId>`, so a photo's signature never opens a trash entry and the
  other way round. `HttpPipeline.authorize` derives the subject from the route's path parameter (`photoId` as it is,
  `trashId` with the prefix); `Authenticator.hasValidImageSignature` takes the subject. `RouteAccessTest` lists both
  signed routes and substitutes `{trashId}` in its 401 loop.
- **Rendering:**
  - Through the existing `RenderCache` and decoder process, with a copy of the saved entry whose ID is
    `trash-<trashId>` and whose source is `trash`. `SourceOriginals.onFrame`, `fileOf` (the fetch) and `release`
    resolve that source to the file in the trash (no download, nothing to release).
  - The quarantine callback recognizes the `trash` source and marks the record quarantined instead of the index. It
    runs outside the cache lock, as `library-performance` decision 6a requires for `onCrashed`.
  - Renderings `trash-<trashId>-v<version>-…` are removed with `removeAll` on purge and restore. `library-performance`'s
    start sweep deletes them as well, since no index entry has their ID; that is accepted, they render again when
    asked for.
- **Never rendered:** a quarantined or damaged entry, or one without a record (its quarantine is unknown), has
  `thumb: null`, and its route answers 503 `render_failed` without decoding. In safe mode the route answers 503
  `safe_mode`, as images do.
- **Caching:** an entry never changes, so the answer is `private, max-age=31536000, immutable` with an ETag.
- **Lock order of the whole change:** `SourceRegistry` → library → trash → index → cache → `OriginalsStore`, extending
  `library-performance` decision 6a.
- **Alternative:** no thumbnails (the plan's recommendation). The owner preferred recognizable entries; the delete
  dialog says that every paired device can see the trash.

### 11. Trash API

- **`GET /trash`:** `{ items: TrashEntry[], totalBytes }`, newest first. Each `TrashEntry` has:
  - `id`; `photoId` and `path` (derived from the tree, also without a record; `null` for a damaged entry); `album`;
  - `fileSize`, `deletedAt`, `purgeAfter` (deletion + 7 days; `null` while the clock is below the floor);
  - `deletedBy` (client ID or `null`), `quarantined`, `damaged`, and `thumb` (signed URL or `null`).
- **`POST /trash:restore`:** body `{ ids }` with 1 to 10,000 IDs. Answer `{ restored: [{ id, photoId }], missing,
  failed }`. 503 `storage_unavailable` or `safe_mode`.
- **No delete:** there is no `DELETE` on `/trash` (405) or on an entry (404), and no endpoint to empty it.
- **Path parameter:** `TrashId` with the pattern of decision 2.

### 12. Origin of a pairing

- **Fields:** `StoredClient.pairedVia: String?` (`code`, `qr`, `invite`; `null` for older pairings) and
  `invitedBy: String?` (the inviting client's ID, kept after that client is revoked). `ClientRegistry.create(name,
  pairedVia, invitedBy)`.
- **Where set:** at the one place every pairing passes, `PairingManager.create(deviceName, via, inviter)`: `pair` gives
  `code`, `pairWithSecret` gives `qr` for the session's secret and `invite` with the inviter for an invitation. The
  pairing log line names the origin.
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
- **`AccountGrants`:** a new class in `sources/`, created by `FrameCore`; `NextcloudConnector` writes it and
  `SourceRegistry` reads it, so no cycle arises. It keys on `(clientId, sourceId)` and expires 30 minutes after the
  login on `Clock.monotonicMillis`. It lives in memory only. Removing a source drops its grants; grants of a client no
  longer in `ClientRegistry` are ignored and dropped when touched.
- **Which logins grant:** a completed login by a client for an account: one that adds the source (so the first folder
  needs no second login), one that renews its credentials, and a confirmation login (decision 16).
- **Alternatives (from the plan):** `connectedBy` stored with the source (after the connecting device is revoked,
  nobody could widen); a tap on the frame (new frame UI). The fresh login proves the Nextcloud credentials at the
  moment of widening, which is rare.

### 15. One login flow per device

- **How:** `NextcloudConnector` keeps `Map<clientId, Flow>` instead of one `flow`. `startLogin(serverUrl, clientId,
  confirmSourceId)` replaces only that client's flow; `pollLogin(flowId, clientId)` finds a flow only among that
  client's, else `expired`. At every start and poll, expired flows and flows of clients no longer in `ClientRegistry`
  are swept, so a revoked device's flow ends; their number is bounded by the paired devices.
- **Why bind instead of accepting the risk:** with one global flow another device could cancel a confirmation in
  progress by starting its own login, or poll it. The change is small.
- **Contract:** the `startSourceLogin` description changes from "a new one replaces the old" to one flow per device.
- **Test seam:** `FakeLoginFlow` gets approval and user per flow (keyed by its poll token), so two flows in one test
  can be approved separately and as different users.

### 16. Confirmation login: check, then revoke the new password

- **Start:** `POST /sources/login` with `confirmSourceId` starts the flow on that source's stored server address;
  `serverUrl` may be left out and is ignored then. An unknown ID gets 404, a share-link source 400 at
  `confirmSourceId`. A full frame does not refuse it, because nothing is added.
- **Done:** the approved account is compared with the connection of `confirmSourceId` only: same user and server key,
  by the reported address and then by the start address. No other source is looked at.
  - **Match:** grant `(clientId, sourceId)`, revoke the new app password in the background (`revokeNew`), answer `done`
    with that `sourceId`. No `reconnect`: the source keeps its password, status and stored result.
  - **Any other account,** also the account of another source: revoke the new password and answer `failed` with the new
    `SourceErrorCode` `other_account`. Nothing is added and no source reconnects.
  - **User name unreadable:** the existing failure path, with the revoke.
- **Why not reconnect** (owner's decision): `reconnect` rotates the password, marks the credentials as replaced and
  starts a sync. That is too much for a check and would reset a refused-credentials state.

### 17. Security model in writing

- **README** "Security model": the pairing bullet says that every paired device has the same rights, how it paired is
  shown, and "Unpair all devices" on the frame is the remedy. New bullets name the trash (7 days, earlier only for
  entries older than a day under storage pressure, visible to and restorable by every paired device, thumbnails
  included, freed at once only over USB) and the widening proof. Accepted residual risks:
  - revoking, renaming and inviting other devices;
  - settings, including `autoUpdateCheck` (local-only mode stays on the frame only);
  - hiding photos and overwriting comments and sections;
  - removing a source, which loses the hidden states, comments and sections of its photos and revokes the app password;
  - restoring and seeing photos another device deleted;
  - the log (`GET /logs`, open to every token) shows client IDs and photo paths;
  - an account owner who approves a login page that another device started, for example from a link that device sent,
    gives that device the widening proof;
  - a forward jump of the clock ages every entry and can purge the whole trash at the next check.
- **README** "Photos from other sources": `/` is the whole account and is visible to every paired device, widening
  needs a login, and a share link suits a frame others use. The photo section mentions the trash. No text names
  "100 MB"; the reserve is `library-performance`'s.
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
  The README bullet refers to this change, and the re-check moves to model B, when roles decide who may add sources.

### 19. Web UI

- **Trash panel:** `TrashPanel.svelte` in `PhotosView` next to `SkippedFiles`, shown when the trash is not empty,
  folded by default with count and size. Rows show:
  - a lazily loaded thumbnail, or `QuarantinedPlaceholder` for a quarantined entry and a "no preview" placeholder for
    a damaged entry or one without a thumbnail;
  - name, album and deletion time;
  - the deleting device ("this device", its name, "a removed device", "unknown"), from `GET /clients`;
  - the date until which it is kept, with "earlier when storage runs short, at the earliest a day after deleting".

  "Restore" per row (none for a damaged entry) and "Restore all" in chunks of at most 500 IDs follow. The panel
  reloads with the library version, so the signed URLs stay fresh.
- **Texts:**
  - `photos.confirmDeleteOne/Many` name the trash, 7 days and that every paired device sees it;
  - `error.insufficient_storage` names the reserve without a number and the trash;
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

- **Deleting frees storage late.** On the Frameo a user who deletes to make room may get a 507 for up to a day.
  → The 507 text explains it; USB frees at once; the device row notes whether `.trash` is visible over MTP.
- **Every paired device can see deleted photos for 7 days** (owner's choice of thumbnails).
  → The delete dialog says so; restoring and deleting are logged with the device.
- **A hostile device can force an early purge** after 24 hours by uploading.
  → Documented; the log shows the deletion, and a day is left to restore. `makeRoom` deletes nothing unless the upload
  then fits, and the announced length triggers nothing.
- **The same-volume test is a heuristic.** Two volumes with equal size and free space read at the same moment would
  count as one.
  → Then `makeRoom` frees trash entries without helping, still only those older than 24 hours; on the reference frames
  everything is on one volume.
- **`.trash` may be visible over USB,** as `.upload` was on one tablet.
  → Device row; the trash tolerates files deleted or added there and leaves foreign files alone.
- **A wrong clock purges too early or too late.**
  → The floor and the re-stamp (decision 8). A forward jump remains a documented residual risk.
- **A confirmation link can be phished:** a device starts a confirmation login and gets the account owner to approve it.
  → The approval page is Nextcloud's own and names the app; documented as a residual risk.
- **The scan change could keep a stale entry.**
  → It keeps an entry only when size and modification time match the file, the same test the scan uses to skip
  unchanged files.
- **The widening rule asks for a login on harmless changes,** such as another case or going back up after narrowing.
  → Intended; the card explains it and the login is one approval.
- **The grant is in memory:** a restart within the 30 minutes asks for a new login. Accepted.
- **A confirmation leaves a new app password on the server** if its revoke fails.
  → The revoke runs in the background and logs a failure, as for other unused passwords.
- **Merge order with `library-performance`:** this change rebases on its `deleteAll`, reserve, versions, apply step and
  case resolver.
  → It is implemented after #126 has landed; task 1.1 confirms that first.
- **Residual risks under A** (decision 17): removing a source cannot be fully undone, and settings, names and pairings
  can be changed by any paired device.
  → Visible in the log; documented; model B later.
- **Disclosure:** the proposal describes weaknesses in a public repository.
  → Proposal and implementation land close together, and the release follows soon through the updater.

## Migration Plan

- **Order:** after `library-performance` is merged; both may ship in the same release.
- **Pairings:** `clients.json` gains two string fields; old entries load with `null` and are listed with an unknown
  origin. After the update the owner reviews the device list and revokes unknown devices nobody uses (user action).
- **Trash:** `trash.json` (with its backup) and `.trash` appear with the first delete. `sources.json` and the index
  format do not change.
- **Sources:** existing account sources keep their folder. Only a later widening needs the login.
- **Rollback:** install the previous release over it. It ignores the new fields; its scan skips `.trash`, so photos in
  the trash stay there and are not purged until a newer version runs again or they are deleted over USB.
- **Release:** through the updater; the device rows run afterwards with the owner's consent.

## After implementation

Details the code showed, recorded here; the requirements are unchanged.

- **Task 1.1:** `library-performance` was merged (#131) but not yet archived when this change was implemented; its
  code (`StorageReserve`, `deleteAll`, removal listener, versions and tombstones, apply step, case resolver) was on
  `main`.
- **Announced length of an upload (decision 9):** the check of the announced length deletes nothing, but counts what
  old trash entries could free (`TrashStore.reclaimableBytes`). Otherwise every upload with a `Content-Length`, which
  browsers always send, would be refused there before the streaming checks could make room, and "Upload makes room"
  could not happen.
- **Render cache (decision 9):** the cache asks the trash after its own trim whenever it is still below the reserve,
  also for a rendering somebody awaits, as the requirement "Where storage pressure frees the trash" reads. For
  originals only the download for a rendering asks (`OriginalsStore.ensureRoomOnDisk`), not the downloads of a sync.
- **Clock (decision 8):** `TRASH_CLOCK_FLOOR` is 2026-01-01T00:00:00Z. While the clock is below it, storage pressure
  frees nothing either, and an entry stamped below it is not eligible under pressure until it is re-stamped. Its
  `purgeAfter` is `null` until then.
- **Entries (decision 2):** an image file in a hidden folder inside an entry does not count, so an entry whose only
  image lies there is damaged: a restore would put it out of the scan's sight.
- **Restore (decision 5):** the route refuses in safe mode; `PhotoLibrary.restore` answers 503 `storage_unavailable`.
  A thumbnail of an unknown trash ID is 404.
- **Folder hint (decision 19):** the four points are the hint of an account's folder; a share link keeps the earlier
  hint (`sources.folderHintShare`), since `/` is not a whole account there.
- **Test server:** `/nextcloud/approve` approves the last login started, with `user=` as another Nextcloud user;
  `FakeLoginFlow` approves per flow by its poll token, which is new per start.
