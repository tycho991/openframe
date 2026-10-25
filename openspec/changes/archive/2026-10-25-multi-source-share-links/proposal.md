# Proposal

## Why

The frame can take photos from one place besides its own folder: one Nextcloud account that the user logs in to. That leaves out the most common way people pass photos on, a link: "here is the folder with the holiday photos" from a Nextcloud share, with nobody having to give the frame an account. It also allows only one source, so a frame cannot show the family's share and the holiday share side by side. iCloud's shared albums, Google Drive and Dropbox are asked for as well, but they differ so much (see Out of scope) that they need a source model that several kinds can plug into, not another copy of the Nextcloud code.

## What Changes

- **Share links.** A public Nextcloud share link of a folder (`https://host/s/<token>`, also behind `/index.php` and in a sub-path) can be added as a source without an account, optionally with the share's password. The frame reads it over the public WebDAV of the share; photos, albums (the first folder below the share), sync, hiding and removal work as for the Nextcloud account.
- **Several sources.** The frame holds up to eight remote sources at the same time. Each has its own connection, settings, status, schedule, photos and original cache; adding, syncing or removing one does not touch the others. Photos of different sources that are in a folder of the same name appear in the same album.
- **The existing connection becomes one kind of source.** The logged-in Nextcloud connection stays as the kind `nextcloud-account`; the connection a frame already has keeps its photo IDs and hidden state and does not have to be connected again after the update.
- **API.** `/nextcloud*` is replaced by `/sources*`: a list, adding a share link, the login flow for an account, and per source settings, sync and removal. Photo IDs may now carry digits in their source part. This breaks the Nextcloud endpoints of 0.1.0; the web UI is the only client.
- **Web UI.** The Nextcloud section becomes a "Sources" section: list, "add source" (account or share link), and for each source its settings, status, sync and removal.
- **Requirements move.** The requirements of `nextcloud-source` that are not about Nextcloud (configuration, sync, ETags, vanished photos, failure, schedule, status, originals, removal) move to a new capability `remote-sources` that every kind of source shares; `nextcloud-source` keeps the login flow, secrecy, trusted connections and the new share links.
- **Further providers are prepared, not built.** The source model is a kind with a connection, a listing and a download. The design documents what iCloud, Google Drive, Dropbox and a plain WebDAV address would need; each of them is a change of its own.

### Out of scope

- OAuth logins and any provider other than Nextcloud: Dropbox and Google Drive need an app key or API key of the project (and Google reviews apps that read Drive), iCloud has no official interface (shared albums are read over an unofficial one that may change without notice). They are follow-up changes.
- Share links of single files, "file drop" shares that only accept uploads, and shares of other products (ownCloud may work, it is not a goal and not tested).
- Writing to a source: sources stay read only.
- Choosing an album name per source, mixing sources into one slideshow weighting, or per-source slideshow settings.

## Capabilities

### New Capabilities

- `remote-sources`: several read-only remote photo sources side by side, each with its own settings, schedule, status, sync, originals cache and removal; the rules that were part of `nextcloud-source` and are not specific to Nextcloud.

### Modified Capabilities

- `nextcloud-source`: adds connecting with a share link (and its password), extends the secrecy of credentials to the share link and password, and gives the generic requirements to `remote-sources`.
- `web-admin`: the Nextcloud section becomes a section for sources with the share link flow.

## Impact

- `api/openapi.yaml` first (new `/sources*`, removed `/nextcloud*`, photo ID pattern, error codes), then the Kotlin DTOs and `npm run gen:api`.
- `:core`: the `nextcloud` package is split into a generic source part (config store with several entries, one scheduler per source, originals per source, `SourceOriginals` finding the source of a photo) and the Nextcloud kinds (`WebDavFolder` for an account, a new one for a share, `LoginFlow`); `FrameCore` builds the registry; a migration of `nextcloud.json`, `nextcloud-status.json` and the `cache/nextcloud` folder to the new files. No Android code changes beyond what `FrameCore` needs.
- `webadmin/`: `NextcloudView` and its helpers, routes, i18n (`en.json`, `de.json`), error codes, unit and e2e tests.
- `docs/DEVICE_TEST.md` (device rows: share link without account, two sources, update from a frame that has a connection) and `docs/FINDINGS.md`.
- Follows the archived `nextcloud-sync` change. No new dependency.
