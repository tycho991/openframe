# Proposal

## Why

The two frames still show their photos through `pictureframe`, which fetches them from a Nextcloud folder. openframe only knows the local photo folder, so it cannot take over the Lenovo and the Frameo until it can do the same. This change adds the second photo source that the library was prepared for (`PhotoSource`, the `source` field of every photo, the `nextcloud` placeholder in the code): a Nextcloud folder, read-only, kept in step with the frame in the background.

## What Changes

- **A Nextcloud source.** The frame lists a configured folder of a Nextcloud server over WebDAV, downloads new and changed photos (an unchanged ETag means no download), and puts them into the same index, albums, hidden states and slideshow as local photos. Photos that disappear from the folder disappear from the frame (switchable). A failed run never removes what is already there.
- **Connecting without a password.** The web UI starts Nextcloud's Login Flow v2 through the frame: the user approves in a browser tab, the frame receives an app password and keeps it in its private storage. The password is never returned by the API and never logged.
- **Schedule and status.** The source syncs every 20 minutes by default (at least 5), tries again after a failure after 60 seconds with doubling delays up to the interval, and reports last run, result, error and counts.
- **Old devices.** Android 5 and 6 do not know the root certificate of Let's Encrypt, so the app carries the trust anchors it needs and uses them next to the system's.
- **Storage.** Downloaded originals are a cache with a budget of their own. When it is exceeded the originals unused for the longest time are removed and fetched again when a new rendering needs them. Rendered sizes stay as they are.
- **Web UI.** A section "Nextcloud": connect, folder, subfolders, interval, removal of vanished photos, storage budget, sync now, status, disconnect.
- API (contract first): every photo gets a `source`, deleting a Nextcloud photo answers `409`, and there are `GET` and `PATCH /api/v1/nextcloud`, `DELETE /api/v1/nextcloud`, `POST /api/v1/nextcloud/login`, `GET /api/v1/nextcloud/login/{flowId}` and `POST /api/v1/nextcloud/sync`, all with token. The set of endpoints without a token does not change.

Out of scope:

- Videos (a follow-up change), writing to Nextcloud, several folders or accounts, and Nextcloud features beyond files such as shares and public links.
- Importing the settings or the photo state (hidden, comments, crops) of `pictureframe`; the frame starts from what is in the folder.

## Capabilities

### New Capabilities

- `nextcloud-source`: Connecting to a Nextcloud server, the schedule, the sync of a folder into the library, handling of failures and of storage, and the secrecy of the credentials.

### Modified Capabilities

- `photo-library`: Photos of a remote source cannot be deleted through the API, and the storage rule distinguishes originals that are the only copy from originals that are a cache.
- `web-admin`: The Nextcloud section of the interface, and no delete button for photos from Nextcloud.

## Impact

- `:core`: new package `nextcloud` (source, WebDAV client, login flow, trust anchors, scheduler), changes in `FrameCore` (source wiring, routes), `RenderCache` (original lookup per source), `PhotoLibrary` (delete), `PhotoEntry` (an ETag for remote photos).
- `:android`: the platform side stays as it is; the trust anchors are resources of `:core`.
- `api/openapi.yaml`, `webadmin/` (view, i18n, e2e with a fake Nextcloud in the test server), `docs/DEVICE_TEST.md`.
- Both devices: a real Nextcloud account and folder are needed for the acceptance rows; the credentials stay out of the repository.
