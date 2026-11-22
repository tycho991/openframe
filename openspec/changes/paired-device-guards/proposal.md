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

## What Changes

- **Trash for deleted local photos** (owner's decision):
  - Deleting a local photo moves its file into the hidden folder `.trash` inside the photo folder instead of removing
    it. The scan skips dot folders, so the file leaves the index and the slideshow at once. The file tree is the
    source of truth; a small state file keeps hidden state, comment, section, quarantine and the deleting device.
  - Entries stay 7 days and are then deleted. Before an upload is refused with 507, entries older than 24 hours are
    deleted, oldest first, until the upload fits. A clock that is still at its factory date cannot purge early.
  - Every paired device can list the trash and restore from it. There is no endpoint to empty the trash.
  - A restored photo gets back its ID, hidden state, comment, section and quarantine. On an occupied path it gets a new
    name and so a new ID. A deleted album folder is created again, and the photo takes its album's current state.
  - Thumbnails in the trash use signed URLs like the photo overview (owner's choice, against the recommendation). A
    quarantined photo is never rendered: its entry shows a placeholder, and a decoder crash on a trash thumbnail
    quarantines the entry.
  - Deleting while the photo folder is unavailable is refused with 503 `storage_unavailable` instead of dropping the
    entry.
  - Moving to the trash, restoring and purging are logged with the acting device's ID.
- **Widening an account folder needs a fresh login** (owner's decision):
  - A change that widens what an account source reads (the first folder, a higher or other folder, subfolders switched
    on, or a deeper folder while subfolders are off) succeeds only if the same device completed a login for exactly
    this account within the last 30 minutes. Otherwise the whole change is refused with 403 `account_login_required`.
  - Narrowing, the other settings and share-link sources stay free.
  - A new confirmation login for a source checks only that server and user match, then revokes its new app password:
    no reconnect, the source keeps its password and state. Another account fails with `other_account`, its app password
    is revoked, and no source is added.
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

- `photo-library`: "Delete" and "Quarantined photos" change. New requirements for the trash, deleting while the photo
  folder is unavailable, the trash's retention, storage pressure, its list, its thumbnails, restoring, the album state
  of a restored photo, a restore during a scan, a lost trash record and the log of trash actions.
- `admin-access`: "Protected endpoints" covers signed thumbnails of the trash. New requirements "Equal rights of paired
  devices" and "Origin of a pairing".
- `remote-sources`: new requirements "Widening the folder of an account" and "Proof for widening an account folder".
- `nextcloud-source`: new requirements "Confirm an account by login" (an exception to "Connect with the login flow",
  which stays as it is) and "Login flows per device".
- `web-admin`: "Edit photos", "Paired devices" and "Invite a device" change. New requirements "Trash in the
  interface", "Storage full and the trash", "Confirm a wider folder" and "Folder of an account explained".

## Impact

- `core`:
  - `library/`: `PhotoLibrary` (delete into the trash, restore, room before a 507), a new `TrashStore`, and
    `LocalPhotoSource` (a scan keeps an entry the frame has just put in place). `SourceOriginals` and the quarantine in
    `FrameCore` know trash thumbnails.
  - `auth/`: `StoredClient.pairedVia` and `invitedBy`, `ClientRegistry.create`, and `PairingManager.create(deviceName,
    via, inviter)`. `AccessControl` and `HttpPipeline` verify a signature for a trash entry.
  - `sources/`: the widening rule, `AccountGrants`, a guard in `SourceConfigStore.patch` called from
    `SourceRegistry.patch`.
  - `nextcloud/NextcloudConnector`: flows per device, confirmation login.
  - `api/`: `LibraryRoutes` (trash routes, client ID to delete), `PairingRoutes` (`ClientDto`), `SourcesRoutes` (client
    ID, `confirmSourceId`).
  - `FrameCore` wiring: purge at start and hourly. The dev server gets a login as another user.
- `api/openapi.yaml`, first:
  - `GET /trash`, `POST /trash:restore` and `GET /trash/{trashId}/thumb` (token or signature).
  - `Client.pairedVia` and `Client.invitedBy`.
  - `SourceLoginRequest.confirmSourceId`, the `SourceErrorCode` `other_account`, and 403 `account_login_required` at
    `PATCH /sources/{sourceId}`.
  - 503 `storage_unavailable` at `DELETE /photos/{photoId}`, and the new texts of the delete, login and 403
    descriptions.
  - Then the DTOs by hand and `npm run gen:api`.
- `webadmin`:
  - A trash panel in the photo overview, the delete and storage texts, and the origin in the device list.
  - The full-rights sentence in the invitation dialog, the folder hint, and the confirmation of a wider folder on the
    source card.
  - `en.json` and `de.json`, error codes, Vitest and e2e.
- Docs: the README security model, and its source and photo sections.
- Behaviour visible to users:
  - Deleting frees storage only after 7 days, or after a day when an upload needs the room.
  - Widening an account folder asks for a Nextcloud login.
  - Existing pairings show "unknown" as their origin.
- Shipped through the updater. Rows in the private device checklist follow the release (`.trash` over USB, restore
  after a restart, trash thumbnails).
