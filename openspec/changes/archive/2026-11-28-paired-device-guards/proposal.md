# Proposal

## Why

Every paired device has the same full rights, whether it paired with the code on the frame, its QR code or an
invitation. Audit package P6 found two ways in which one such device, a visitor's phone for example, does harm that
cannot be undone: it deletes local photos for good (`PhotoLibrary.delete` calls `file.delete()`, and a batch takes
10,000 IDs), and it widens the folder of a Nextcloud account source to `/`, after which the frame downloads and serves
every photo of the account, with path and place. Nobody had weighed these against the role-less model. The re-check of
22 Nov 2026 against `main` (0.13.0) confirmed that both still apply and that their prerequisites (P1, P3, P4, P5, P7)
have landed. On the same day the owner decided to keep equal rights for now (model A) and to close these two ways in a
targeted manner. Owner and guest roles (model B) follow later as a change of their own.

This change is implemented after `library-performance` (P8, PR #126) and builds on it: its storage reserve, its
`deleteAll` with one index edit and the 503 for deletes while the photo folder is unavailable, its removal
notification for renderings, its image versions and tombstones, its scan apply step, and its upload case resolver.

## What Changes

- **Trash for deleted local photos** (owner's decision):
  - Deleting a local photo moves its file into the hidden folder `.trash` inside the photo folder instead of removing
    it. The scan skips dot folders, so the file leaves the index and the slideshow at once. The file tree is the
    source of truth; a small state file keeps hidden state, comment, section, quarantine and the deleting device.
  - Entries stay 7 days and are then deleted. Under storage pressure (an upload while it is written, the render cache
    or a download of originals below the storage reserve, an update short of storage) entries older than 24 hours are
    deleted, oldest first, but only if that frees enough; otherwise none goes. A clock that is still at its factory
    date cannot purge early; such an entry is re-stamped later and gets a new trash ID (owner's default).
  - Only folders named like trash entries count; anything else in `.trash` is left alone, symbolic links are not
    followed, and an entry with more or fewer than one image file is listed as damaged and cannot be restored.
  - Every paired device can list the trash and restore from it. There is no endpoint to empty the trash.
  - A restored photo gets back its ID with a new image version (so `library-performance`'s tombstones do not drop its
    renderings), its hidden state, comment, section and quarantine. On an occupied path it gets a new name and so a
    new ID. A deleted album folder is created again, in the letter case of an existing folder, and the photo takes its
    album's current state. Without its saved state it comes back hidden. In safe mode a restore answers 503
    `safe_mode`, as an upload does (owner's default).
  - Thumbnails in the trash use signed URLs like the photo overview (owner's choice, against the recommendation). A
    quarantined photo is never rendered: its entry shows a placeholder, and a decoder crash on a trash thumbnail
    quarantines the entry.
  - Moving to the trash, restoring and purging are logged with the acting device's ID.
- **Widening an account folder needs a fresh login** (owner's decision):
  - A change that widens what an account source reads (the first folder, a higher or other folder, subfolders switched
    on, or a deeper folder while subfolders are off) succeeds only if the same device completed a login for exactly
    this account within the last 30 minutes. Otherwise the whole change is refused with 403 `account_login_required`.
  - Narrowing, the other settings and share-link sources stay free.
  - A new confirmation login for a source checks only that server and user match that source's account, then revokes
    its new app password: no reconnect, the source keeps its password and state. Any other account, also that of
    another source, fails with `other_account`, its app password is revoked, and no source is added or changed.
  - Each paired device has its own login flow, and only the device that started a flow can poll it. So another device
    can no longer cancel or read a login in progress.
- **Origin of each pairing** (owner's decision): each pairing stores whether it came from the code, the QR code or an
  invitation, and which device invited. `pairedVia` and `invitedBy` are part of the `Client` record and appear in the
  device list. Older pairings show "unknown". The value is stored as a string, so a value the build does not know
  cannot make `clients.json` unreadable.
- **Security model written down** (owner's decision): the equal rights, the two guards and the accepted residual risks
  are stated in `admin-access`, in the README and in the invitation dialog. The blind server-side request through
  sources stays accepted and is documented again; it is to be checked again with roles. The short-term texts of the
  audit plan for `inviteHint` and `folderHint` are folded in here.
- **Not in this change:** roles (model B), withdrawing an invitation and recognizing the invited device by
  `invitedBy` (P10), and a way to empty the trash on the frame.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-library`: "Delete" and "Quarantined photos" change. New requirements for the trash, its entries, its
  retention, storage pressure and where it applies, its list, its thumbnails, restoring, the album state of a restored
  photo, a restore during a scan, a lost trash record and the log of trash actions. The 503 for deletes while the
  photo folder is unavailable comes from `library-performance` ("Photo folder kept").
- `admin-access`: "Protected endpoints" covers signed thumbnails of the trash. New requirements "Equal rights of paired
  devices" and "Origin of a pairing".
- `remote-sources`: new requirements "Widening the folder of an account" and "Proof for widening an account folder".
- `nextcloud-source`: "Connect with the login flow" excepts confirmation logins. New requirements "Confirm an account
  by login" and "Login flows per device".
- `web-admin`: "Edit photos", "Paired devices" and "Invite a device" change. New requirements "Trash in the
  interface", "Storage full and the trash", "Confirm a wider folder" and "Folder of an account explained".

## Impact

- `core`:
  - `library/`: `PhotoLibrary` (`deleteAll` into the trash, restore, room at the streaming checks), a new `TrashStore`,
    and `LocalPhotoSource` (the apply step keeps an entry the frame has just put in place). `SourceOriginals` and the
    quarantine in `FrameCore` know trash thumbnails; the render cache's below-reserve path and the updater's
    `update_storage` check ask the trash for room.
  - `auth/`: `StoredClient.pairedVia` and `invitedBy`, `ClientRegistry.create`, and `PairingManager.create(deviceName,
    via, inviter)`. `AccessControl` and `HttpPipeline` verify a signature for a trash entry.
  - `sources/`: the widening rule, `AccountGrants`, a guard in `SourceConfigStore.patch` called from
    `SourceRegistry.patch`.
  - `nextcloud/NextcloudConnector`: flows per device, confirmation login.
  - `api/`: `LibraryRoutes` (trash routes, client ID to delete), `PairingRoutes` (`ClientDto`), `SourcesRoutes` (client
    ID, `confirmSourceId`).
  - `FrameCore` wiring: mirror build and purge on the core's scope, hourly. `FakeLoginFlow` gets approval per flow, and
    the dev server a login as another user.
- `api/openapi.yaml`, first:
  - `GET /trash`, `POST /trash:restore` and `GET /trash/{trashId}/thumb` (token or signature).
  - `Client.pairedVia` and `Client.invitedBy`.
  - `SourceLoginRequest.confirmSourceId`, the `SourceErrorCode` `other_account`, and 403 `account_login_required` at
    `PATCH /sources/{sourceId}`.
  - 404 at `POST /sources/login`, and the new texts of the delete, login and 403 descriptions.
  - Then the DTOs by hand and `npm run gen:api`.
- `webadmin`:
  - A trash panel in the photo overview, the delete and storage texts, and the origin in the device list.
  - The full-rights sentence in the invitation dialog, the folder hint, and the confirmation of a wider folder on the
    source card.
  - `en.json` and `de.json`, error codes, Vitest and e2e.
- Docs: the README security model, and its source and photo sections.
- Behaviour visible to users:
  - Deleting frees storage only after 7 days, or after a day under storage pressure.
  - Widening an account folder asks for a Nextcloud login.
  - Existing pairings show "unknown" as their origin.
- Shipped through the updater. Rows in the private device checklist follow the release (`.trash` over USB, restore
  after a restart, trash thumbnails).
