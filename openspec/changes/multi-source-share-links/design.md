# Design

## Context

Everything about remote photos today is one Nextcloud connection: `NextcloudConfigStore` holds one `StoredNextcloud` (`nextcloud.json`, with the app password), `NextcloudSource` is the one `PhotoSource` with id `nextcloud`, `NextcloudScheduler` runs one loop and keeps one status (`nextcloud-status.json`), `OriginalsStore` caches originals in `cache/nextcloud`, `SourceOriginals` picks "local" or "nextcloud" by `entry.source`, and the API has the singular `/nextcloud*` endpoints. What is already generic and stays: `RemoteFolder` (list, download) with `RemoteFile` (path, ETag, size, mtime), the index with a `source` field per photo and per skipped file, `PhotoEntry.idFor(source, path)`, the sync algorithm in `NextcloudSource.sync()`, `NextcloudHttp` with the trust anchors. The server is read through WebDAV with `PROPFIND Depth: 1` and the parser in `PropfindParser`. See proposal.md for scope and what is left out.

## Goals / Non-Goals

**Goals:**
- A share link works like any other source, with no account on the frame.
- Several sources run side by side without influencing each other's photos or schedule.
- A frame that is updated keeps its Nextcloud connection, photo IDs, hidden state and originals.
- A new kind of source needs a connection type and a `RemoteFolder`, not a copy of the sync, schedule, status, API and cache code.

**Non-Goals:**
- No OAuth, no provider other than Nextcloud, no writing to a source (proposal.md, Out of scope).
- No per-source slideshow weighting and no album name per source.
- No change to how the display gets its data: it still reads `StateFlow`s from `FrameCore`.

## Decisions

### 1. A source is a config entry plus one object that does the work

`StoredSource` holds the common part (id, kind, name, enabled, folder, subfolders, remove vanished, interval, budget) and a connection that depends on the kind: `Account` (server, user ID, login name, app password) or `Share` (server, token, password). `NextcloudSource` becomes `RemoteSource`, one instance per entry, with the code of `sync()`, `original()`, `clearPhotos()` unchanged apart from taking its config and its folder from the entry. A small `SourceKind` interface holds what differs: `remoteFolder(entry)`, `revoke(entry)` (no-op for a share), `needsFolder` (true for an account, false for a share) and a `listsFlat` flag for a later iCloud kind without folders. `FrameCore` keeps a `SourceRegistry` that maps IDs to their `RemoteSource` and scheduler and is what `SourceOriginals`, the API and `FrameCore.onNetworkChanged()` talk to.

*Alternative:* a second class `ShareSource` next to `NextcloudSource`. Rejected: the sync, cache, status and removal would exist twice, and the third provider would make it three.

### 2. Storage: `sources.json`, the old file stays until the source is removed

`sources.json` (a list with a version, secrets inside, private storage like today) replaces `nextcloud.json`; the status becomes `sources-status.json` (a map from ID to the stored result). Originals live in `cache/sources/<id>`. At the first start with an old frame: if `sources.json` is missing and `nextcloud.json` exists, the registry writes `sources.json` with one entry of kind `nextcloud-account`, ID `nextcloud` (so every photo ID `nextcloud-<sha1>` stays valid), moves the status, and renames `cache/nextcloud` to `cache/sources/nextcloud` (a failed rename only costs a re-download). `nextcloud.json` is left in place, so going back to an older build still works, and is deleted when that source is removed. The name of a migrated source is the host of the server.

*Alternative:* migrate in place and delete the old file. Rejected: a failed or reverted update would lose the account.

### 3. IDs

Photo IDs are `<source>-<sha1(path)>` and the contract allows `[a-z]+` for the source. New sources get `s` plus eight random hex digits (all lowercase, at most 16 characters); the contract pattern becomes `^[a-z][a-z0-9]*-[0-9a-f]{40}$`. `local` and `nextcloud` fit both. The ID of a source is not derived from its link, so changing a link (not possible; remove and add) never reuses photo IDs.

### 4. One scheduler per source, one sync at a time on the device

Each source gets its own loop with its own retry state and status (`SyncSchedule` is pure and reused). Syncs of all sources take turns on one lock: the frames have a few weak cores and the slideshow shares them (see the priority lowering in `scan()`), and downloads of two sources at once would only be slower. A failed sync releases the lock at once, so a failing source does not hold the others up. A source added, changed or removed wakes only its own loop; a network change wakes all.

*Alternative:* sync all sources in parallel. Rejected for the reason above; it can be raised later with a constant.

### 5. Reading a share

The classic public WebDAV of Nextcloud: base `https://host[/path]/public.php/webdav/`, `Authorization: Basic base64(<token>:<password>)` with an empty password for a share without one, and the same `PROPFIND Depth: 1` and parser as for an account. The ETag, size and mtime come from the same properties, so "No repeated downloads" works unchanged. A link is parsed by finding the last `/s/<token>` (token of letters and digits), dropping a `/index.php` before it, and taking the rest in front as the server base, so installs in a sub-path work. Adding a share is one `PROPFIND Depth: 0` on the root: `207` with a collection is a folder share; `207` with a plain file is `share_not_a_folder`; `401` is "password missing" if none was given and "password wrong" otherwise; `404` is "share not found". Nextcloud answers a token it does not know with the same `401` as a wrong password (the legacy public WebDAV authenticates the token as a user name), so on a `401` or `404` the frame asks for the share page `index.php/s/<token>` once: `404` there means the share does not exist or was withdrawn. These come back as field errors in a 400 with codes of their own, so that the web UI can name the case at the field: `share_not_found`, `share_not_a_folder` and `share_not_readable` (a "file drop" share, which the public WebDAV refuses to list with `405`; found on the devices) at `link`, `share_password_required` and `share_password_wrong` at `password`; a link that is no share is `validation_failed` at `link`, a server that does not answer 502 `nextcloud_unreachable`. The same check at sync time maps to the status text of the source; `401` and `404` there never remove a photo ("A failed sync loses nothing").

*Alternative:* the "download as ZIP" URL of a share. Rejected: no ETags, so every sync would download everything. *Alternative:* the OCS sharing API. Rejected: it needs an account.

### 6. The API is `/sources`

Written in `api/openapi.yaml` first. `GET /sources` lists the sources with state and status; `POST /sources` adds a share (`{kind: "nextcloud-share", link, password?, name?}`) and answers 201 with the source; `POST /sources/login` starts the login flow for an account and `GET /sources/login/{flowId}` polls it (at most one flow, as now; on `done` the source is created or, for a server and user that already exist, its credentials are replaced); `GET`, `PATCH` and `DELETE /sources/{id}`; `POST /sources/{id}/sync`. Unknown IDs are 404, a ninth source 409 `too_many_sources`, a duplicate 409 `source_exists`, syncing an account without a folder 409 `source_no_folder` (was `nextcloud_no_folder`; `nextcloud_not_connected` goes, an account source is always connected). A login at a frame with eight sources is refused at the start only if none of them is an account it could renew; one that turns out to be a new account then ends as `failed`. The response never contains the app password, the share password or the token; a share shows its server, its name and the kind. A share added without a name is called after the shared folder (`displayname` of the top of the share in the same `PROPFIND`; the host only if the server names none), so that several shares of one server can be told apart (asked for on the devices). The old `/nextcloud*` paths are removed (the web UI is the only client); `RouteAccessTest` keeps router and contract in step.

### 7. Web UI

`NextcloudView` becomes `SourcesView`: a list of cards (name, kind, state, last sync), an "Add source" form with two tabs (account: server address; share link: link and optional password) and the settings, sync and remove controls per card. The route is `sources`; `#/nextcloud` redirects to it so that bookmarks work. Texts in `en.json` and `de.json`, error codes in `errorCodes.ts`, the unit tests of `nextcloud.ts` and the e2e test follow.

### 8. Where further providers would plug in (documented, not built)

| Kind | Connection | Listing and ETag | What blocks it today |
|---|---|---|---|
| WebDAV address | URL, user, password | the existing WebDAV code, ETag as is | nothing technical; a change of its own |
| Dropbox | OAuth 2 with PKCE, needs an app key of the project and a redirect | `files/list_folder`, `content_hash` as ETag; a shared link can be read with `sharing/list_shared_link_files` | app registration, the OAuth page needs a way back to the frame |
| Google Drive | OAuth, scope `drive.readonly`, or a public folder with an API key | `files.list`, `md5Checksum` as ETag | Google reviews apps that read Drive; the API key is shared by all frames |
| iCloud shared album | the public album link, no login | an unofficial web endpoint (`sharedstreams`), checksums as ETag, no folders | the endpoint can change without notice; needs `listsFlat` |

The kind interface of decision 1 holds all of these without a change to the sync, status or cache code.

## Risks / Trade-offs

- [Several budgets of 2,000 MB on a frame with little storage] → each download still keeps 100 MB free, a failed download shows as an error of its source; the default stays 2,000 MB and the web UI shows the budget per source.
- [A migration that fails halfway] → the old file is untouched, `sources.json` is written last and atomically (as every `JsonStore`), and a failed rename of the cache only costs a re-download.
- [Nextcloud changes the public WebDAV path] → the path is in one place; `public.php/dav/files/<token>/` is the newer form and can be tried on a `404`; a device row checks the share on a current server.
- [A share token is a capability] → treated like a password (decision 6, "Secrecy of the credentials"); the name in the list is what a person recognizes.
- [A breaking API change] → the only client is the web UI of the same build; RouteAccessTest and the contract check catch a missed endpoint.
- [One lock for all syncs makes a slow source delay the next] → a sync is bounded by what one source has to download and a failed one frees the lock at once; accepted for the weak devices.

## Migration Plan

1. A frame with a connection updates: at the first start the registry migrates it (decision 2); nothing is asked of the user.
2. Going back to the previous build is possible until the migrated source is removed, because `nextcloud.json` is kept. Sources added after the update do not exist there.
3. No data of the local photos is touched.

## Open Questions

None that change the specs or the task list. Assumptions recorded: the limit of eight sources, folder shares only, and that shares with "hide download" or "file drop" behave as the spec says (the first is read as usual if the server allows WebDAV on it, the second is refused as not readable).
