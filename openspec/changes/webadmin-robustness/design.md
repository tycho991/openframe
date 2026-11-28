# Design

## Context

See proposal.md for the motivation. Line references are to `main` at a675a5b (0.15.0, with the drop fix of #141 and
the proposal `scan-efficiency`). `scan-efficiency` touches only `:core`'s scan. The change `frame-time-zone` is
planned in parallel and owns the frame's times (`PhotoDetail.svelte:105-107`, `SettingsView.svelte:121-127`); this
change leaves those lines alone.

**Requests and polls in the web UI:**

- `call()` (`lib/api/client.ts:87-109`) awaits `doFetch` (:98-103) and `JSON.parse` (:106) unguarded. A rejected fetch
  reaches the views as a `TypeError` with the browser's text, which `errorMessage` returns as it is
  (`lib/i18n/errors.ts:6-12`). `ApiError` (`client.ts:44-54`) has no `retryAfter`. Only `photos()` (:161) and
  `update()` (:201) pass a signal.
- Mitigations on `main`: `pairingErrorText` and `redeemPairingLink` treat a non-`ApiError` as "network"
  (`lib/pairingError.ts:10`, `lib/session.svelte.ts:58`), and the photo queue's deadline throws
  `ApiError(0, "network")` (`lib/photos.ts:145-149`). `errorKeys` already maps `network` to `error.network`
  (`lib/i18n/errorCodes.ts:91`).
- `pollUpdate` (`lib/update.ts:17-50`) is a `setTimeout` chain with a deadline, a dynamic interval and no overlap, but
  no visibility pause and no way to discard a late answer. The other polls:

  | Poll | Where | Interval | Visibility | Overlap | Deadline |
  |---|---|---|---|---|---|
  | State | `session.svelte.ts:120-135` | 10 s | paused, at once on return | possible (`setInterval`) | none |
  | Sources | `SourcesView.svelte:42-46` | 3 s | ignored | possible | none |
  | Display | `SettingsView.svelte:58-62` | 30 s | ignored | possible | none |
  | Devices | `DevicesView.svelte:40-51` | 5 s | skipped while hidden, nothing on return | possible | none |
  | Update | `SettingsView.svelte:151-155` | 2 s / 30 s | ignored | no | 10 s |
  | Stale URLs | `PhotosView.svelte:402-407` | 60 s | ignored | joins (`photos.ts:326-333`) | 30 s per page |
  | Source login | `lib/sources.ts:138-170` | 2 s | must run while hidden | no | none |

- `refreshState` (`session.svelte.ts:101-113`) swallows every error, so no view learns that the frame answers again.

**Loads that never retry:**

- `SettingsView` loads the form only when `settingsVersion` or `dirty` changes (:207-211), and a successful load does
  not clear `error` (:197-205). `DevicesView.load` (:32-38) never clears `error`.
- `PhotosView`'s library effect (:372-384) loads the album list, the skipped files and the trash (with the clients for
  "deleted by", :343-346). It runs at `libraryVersion = -1` and again at the first real version, so these four requests
  go out twice on opening; the settings effect has the guard (:391-392). After a failure they wait for a version
  change. `Library.version` changes only with the index, so a plain restart can leave it equal.
- `PhotosView` already separates load and action errors: `errorSource`, `showError`, `guard` and `background`
  (:47-66, :309-327). Only the grids recover today, through the 60 s refresh that clears `stalled` (`photos.ts:336-340`).
- `/info` is loaded once at start and errors are swallowed (`App.svelte:42-47`); the header shows `session.info?.name`
  (:63). `submit` (`SettingsView.svelte:235-256`) does not touch it.

**Late answers:**

- `switchDisplay` (`SettingsView.svelte:64-76`) and `setBrightness` (:86-100) set `display` from their answer; a display
  poll that started earlier can land after it. `DevicesView.rename` (:69-74) races the device poll. `toggleAlbum`
  (`PhotosView.svelte:422-439`) sets `albumList` (:428) while the library effect's `api.albums()` (:379) may land later.
- `replace()` (`PhotosView.svelte:480-483`) sets `detail = photo` unconditionally; it serves `toggleHidden`, `saveCrop`,
  `saveComment` and the quarantine `retry` (:485-504). `askDelete`'s action sets `detail = null` unconditionally (:469).

**`PhotoPages` (`lib/photos.ts:198-438`), after `album-groups-in-photos` decision 4:** generations, a shared queue
with a deadline, identity checks in `refreshStalePages` (:335-359) and `onImageError` (:362-374). Left open:

- `reload()` (:280-301) calls `restart()` (:394-400) and so discards a running reload instead of joining it.
- `items` (:227-229) concatenates pages without removing duplicate IDs, while the grid is keyed by `photo.id`
  (`VirtualGrid.svelte:106`).
- `onImageError` marks the old page `retried` before its request (:367); when the request fails or is dropped, the page
  stays marked until the next reload.

**Unsaved input:** `markUnsaved`/`hasUnsavedInput` (`lib/update.ts:70-80`) feed only the reload decision
(`session.svelte.ts:81-85`). `SettingsView` (:20-23) and `PhotoDetail` (:60-63) clear their flag on unmount;
`PhotoDetail` resets the comment when another photo opens (:48-55). `SourceCard` keeps `base` and `form` and patches
only the difference (:30-32, :47, :62-69), but never registers. `SettingsView.patch` (:214-226) also sends only changed
fields. The add-share fields live in `SourcesView.svelte:13-22`.

**Keyboard and focus:** the section editor's viewport (`SectionEditor.svelte:158-174`) has `role="application"` but no
`tabindex` and no key handler; `change()` (:77-92) takes `stopAtSharp`, which the buttons pass (:189, :195). The
`showModal` effects of `UploadPanel` (:47-49) and `SectionEditor` (:61-63) restore no focus. `ConfirmDialog` (:14-21)
shows the pattern. The Upload button is at `PhotosView.svelte:662`, the drop handler at :517-522, the editor's opener
at `PhotoDetail.svelte:196-197`.

**Uploads:** `upload()` (`client.ts:208-237`) has no signal, no `onabort`, no `ontimeout` and no watchdog, and
`xhr.onerror` (:234) cannot tell "not sent" from "sent, no answer". `UploadPanel` blocks Cancel and Escape while running
(:107, :182), runs only `waiting` items (:75) and disables Start only for `running` or no waiting item (:185-189); a
new album without a name gives `album = null` (:37-40), the top level. The frame stages each file as a `.part`
(`PhotoLibrary.kt:359-361`) and renames it with `uniqueFile` (:432), so a file whose body arrived is stored even if
the answer is lost, and sending it again stores a second copy.

**Grid top:** `PhotosView` measures the list per scroll frame and on resize (:178-258); a `ResizeObserver` watches the
list and the grid boxes. The comment at :232-233 says that content above the list moves it without resizing it.
That content is `SkippedFiles` and `TrashPanel` (both fold open, :665-666), the error line (:668), and the banners in
`App.svelte:107-109`.

**Numbers:** `format()` (`lib/i18n/detect.ts:15-19`) writes params with `String()`, so counts from `tn()`
(`i18n.svelte.ts:64-66`) and the ranges of `settings.range` are raw. `PhotoDetail` uses `toFixed(1)` (:108). The trash
size (`lib/trash.ts:46-50`) and coordinates (`lib/comments.ts:24-28`) are already localized. `admin.spec.ts:160` expects
"Erlaubt: 5 bis 86400".

**Pairing lock:** `pairingErrorText` takes the seconds from the message with `/[0-9]+/` (`pairingError.ts:17`). The
lockout doubles up to an hour (`PairingManager.kt:49-51`), the frame sends `Retry-After` (`PairingManager.kt:336`, :346),
and the contract requires it (`openapi.yaml:1177-1182`). `formatRemaining` (`lib/pairingLink.ts:30-33`) writes `m:ss`.

**Invitation:** `DevicesView` takes `knownIds` from whatever list it has when the user creates the invitation (:23-26,
:56), possibly none, and shows "Invite device" before the list has loaded (:94-96). Any new ID then counts as accepted.
`Client.invitedBy` (`openapi.yaml:1439-1461`) holds the ID of the device whose invitation was redeemed and `null` for
code and QR pairings. Each device has at most one open invitation; a new one replaces it, and redeeming removes it.
The UI learns its own ID from `current: true` in `GET /clients`.

**Limits:** copies on both sides with no test between them.

- Web UI: `ranges` (`SettingsView.svelte:25-33`), `sourceRanges` and `MAX_SOURCES` (`lib/sources.ts:120-127`),
  `MAX_COMMENT_LENGTH` (`lib/comments.ts:4`), and `maxlength="64"` at `SettingsView.svelte:586`, `RenameForm.svelte:39`
  (plus its `valid` check at :18), `PairingDialog.svelte:75` and `UploadPanel.svelte:160`.
- `:core`: `Settings.kt:160-161`, :187, :223-227; `StoredSource.kt:81-87`; `Comments.kt:9`; the album level literal
  `64` in `PhotoLibrary.kt:636`; the device name literal `64` in `PairingRoutes.kt:106` and :132.
- Contract: `Photo.comment` has no `maxLength` (`openapi.yaml:1824`). The only shared fixture is
  `api/fixtures/presentation-cases.json`, passed to `:core`'s tests as a system property (`core/build.gradle.kts:44-47`)
  and imported by `presentation.test.ts:2`.

## Goals / Non-Goals

**Goals:**

- One poll helper that every repeated request uses, except the source login (it must keep asking while the user
  approves in another tab).
- Every view that can fail to load recovers without a reload once the frame answers again.
- No answer that started before a newer local change is shown over that change.
- Typed input survives moving around the interface, and a reload after a frame update never loses it silently.
- No change of behaviour in `:core` and no new endpoint; one contract keyword.

**Non-Goals:**

- The frame's times and its time zone (`frame-time-zone`).
- Drafts that survive a page reload, a confirmation before leaving, `beforeunload`, router or history changes.
- Drafts for the add-share fields, the server address, the rename form, the upload list or the unsaved section in the
  editor: these are closed by an explicit act.
- Withdrawing an invitation on Close (no endpoint).
- Ending the selection on a change of filter or sort order (decision 13).

## Decisions

### 1. `lib/poll.ts`: `pollUpdate` generalized

```ts
export interface Poll {
  /** Discards the running attempt and asks now; the interval starts again after it. */
  refresh(): void;
  /** Discards the answer of the running attempt; the next attempt comes at the interval. */
  invalidate(): void;
  stop(): void;
}

export function poll<T>(options: {
  load: (signal: AbortSignal) => Promise<T>;
  /** Called only with an answer whose attempt was not invalidated. */
  apply: (value: T) => void;
  onError?: (e: unknown) => void;
  intervalMs: number | (() => number);
  timeoutMs?: number;            // default 10 s, as UPDATE_POLL_TIMEOUT_MS
  enabled?: () => boolean;       // e.g. paired
}): Poll;
```

- **Chain:** one attempt at a time; the next is scheduled after the attempt ends, failed or not, at the interval
  the function returns then (`updatePollMs(phase)` for the update).
- **Visibility:** no attempt while `document.visibilityState` is `hidden`. On `visibilitychange` to visible, an
  attempt starts at once unless one runs.
- **Deadline:** a timer and `AbortController.abort()`, not `AbortSignal.timeout()`, which older phone browsers lack
  (as `photos.ts:144`). A timed-out attempt counts as failed.
- **Generation:** `invalidate()` and `refresh()` raise a generation and abort the running attempt; `apply` and
  `onError` run only for the current generation. An answer from before a local change is therefore dropped, and the
  next attempt reads the frame after the change.
- **Start:** the first attempt runs in a microtask, so that creating a poll inside an effect does not read state the
  effect would track.

**Moved onto it:** the state poll (10 s, `enabled: () => session.paired`), sources (3 s), display (30 s), devices
(5 s), update (2 s / 30 s) and the 60 s renewal of image addresses (`load` runs `refreshStale` of every grid).
`refreshState()` after an action becomes `statePoll.refresh()`. `pollUpdate` and its tests move into `poll.ts`;
`updatePollMs` stays in `update.ts`.

**Not moved:** `pollLogin` (`lib/sources.ts:138`): the user approves the login in another tab, so this tab is hidden
exactly when the answer matters.

*Alternatives:*
- **A second helper next to `pollUpdate`:** rejected; two ways to poll is what this change removes.
- **Visibility pause as an option:** rejected; no poll in scope needs to run hidden, and an option invites the next
  ad-hoc exception.

### 2. Errors of `call()` and `Retry-After`

- **Rejected fetch** (network gone, connection refused, aborted through the signal) → `ApiError(0, "network")`.
- **Unreadable body** (`res.text()` fails mid-body, or the text is not JSON) → `ApiError(0, "network")`, also on a
  2xx: a proxy's HTML page means the frame was not reached.
- **`retryAfter`:** `ApiError` gets `readonly retryAfter: number | null`, the header's whole seconds when it is a
  non-negative integer, otherwise `null`. The frame never sends the date form.
- `errorMessage` keeps `e.message` for other errors; those are programming errors, and hiding them would hide bugs.

*Alternative:* own codes for "aborted" and "invalid response". Rejected: the user can do nothing different, and the
callers that abort discard the result by generation anyway.

### 3. Recovery: a counter of successful state answers

- `session.stateTick` (a `$state` number) goes up on every successful state answer.
- A view's one-off loads that failed are run again at the next tick. Polls recover at their own interval and do not
  register.
- At 10 s per state poll and at most a few requests per view, a view recovers within 30 s after the frame answers, the
  bound of "Current state".
- **Which loads:**
  - `SettingsView`: the form.
  - `DevicesView`: none beyond its poll.
  - `PhotosView`: the library load (album list, skipped files, trash, clients) and the settings.
  - `App.svelte`: `/info`.
- **Library effect:** returns at `libraryVersion === -1`, like the settings effect, so opening the overview loads
  each of these once.
- **`/info`:** loaded at start and again when `settingsVersion` changes, so a frame name saved elsewhere shows in the
  header. After its own save, `SettingsView` also writes the saved name into `session.info` at once.

*Alternatives:*
- **Each failed load on its own back-off timer:** rejected. The state poll already says when the frame answers, and
  timers would send requests at a dead frame.
- **Every view reloads everything at every tick:** rejected. That costs requests every 10 s.

### 4. Load errors and action errors: PhotosView's pattern as a helper

PhotosView's `errorSource`/`showError`/`guard`/`background` (:47-66, :309-327) moves into a small class
`ViewErrors` in `lib/viewErrors.svelte.ts`, used by `PhotosView`, `SettingsView` and `DevicesView`:

- `guard(fn)` is an action of the user: it clears the error, and shows its own failure, which stays until the next
  action.
- `background(source, fn)` is a load: its failure shows only if no action error is on screen, its success clears only
  the error of the same source, and the failed `fn` is kept for the next tick (decision 3).
- `retryFailed()` runs the kept loads; each view calls it from an effect on `session.stateTick`.

Field errors of a save stay where they are (`errors` in `SettingsView` and `SourceCard`). The update section keeps its
own `updateError` and `restarting` (`SettingsView.svelte:131-147`).

*Alternative:* a separate `loadError` variable per view, as the October plan had. Rejected: the pattern exists, is
tested, and keeps one error line per view.

### 5. Late answers

- **Polls:** an action calls `invalidate()` on the poll of the same thing before it applies its answer:
  - `switchDisplay` and `setBrightness`: the display poll.
  - Rename and revoke: the device poll.
  - "Check now" and "Install": the update poll.
- **One-off loads:** a `Sequence` in `lib/poll.ts` (`begin()` gives a ticket, `bump()` after a local write,
  `current(ticket)` before applying):
  - The album list: `toggleAlbum` bumps it.
  - The settings form: a save bumps it.
  - The trash: a restore bumps it.
- **Photo detail:**
  - `replace(photo)` updates the grids always, and sets `detail` only when `detail?.id === photo.id`.
  - `askDelete`'s action closes the detail only when the open photo is among the deleted IDs.
  - A save never opens a closed detail.
- **Filter and sort:** already handled by `PhotoPages` generations (`album-groups-in-photos` decision 4).

### 6. `PhotoPages`: merge reloads, unique items, retry after a failed renewal

- **Merge:** a `reload()` while a reload of the same generation runs sets a flag and returns a promise for the end of
  the follow-up. When the running reload has applied its pages, one more reload runs if the flag is set; any number of
  calls in between give one follow-up.
  - `setQuery()` and `close()` still restart, since their old pages are wrong.
  - A grid without pages keeps today's behaviour (restart; the grid asks again), since no chain of pages is at risk.
  - This also covers the 10-second version change of a frame that syncs all the time: each reload completes and
    shows its pages.
- **Unique items:** the `items` getter keeps the first occurrence of each ID. A photo that moved to an earlier page
  between two page loads shows once; `total` stays the frame's.
- **Retry:** when the renewal in `onImageError` fails or is dropped, the old page's `retried` goes back to `false` if
  the page is still in `pages`, so a later image error asks again. Image errors of one page at the same time still
  cost one request.

*Alternative:* keep the restart and rely on the old pages staying visible. Rejected: the reload then never shows new
photos while the library keeps changing.

### 7. Drafts in module state

`lib/drafts.svelte.ts` holds:

- `settings: { base: Settings; form: Settings } | null`;
- `sources: SvelteMap<sourceId, { base: SourceSettings; form: SourceSettings }>`;
- `comments: SvelteMap<photoId, { base: string; text: string }>`.

How a draft lives:

- **Exists:** while its `form` (or `text`) differs from its `base`. Editing back to the base removes it.
- **Mount:** a view takes its draft when one exists, otherwise the loaded values.
- **Load:** a load updates the view's copy of the frame's values (`saved`) and never touches a draft. This also fixes
  a load overwriting typed input.
- **Save:** sends `patch(draft.base, draft.form)` (`SettingsView.svelte:214-226`) or `settingsPatch(base, form)`
  (`SourceCard.svelte:47`), so fields changed on the frame meanwhile and not touched here keep the frame's value.
  After a successful save the draft goes and the form shows the answer.
- **No rebase** (owner's decision): fields the user did not touch show the values from when the draft started, until
  it is saved or discarded.
- **Discard:** "Discard changes" drops the draft and shows `saved`, the latest values loaded from the frame. The
  control shows only while a draft exists.
- **`hasUnsavedInput()`:** true while any draft exists, whatever view is shown. `markUnsaved` and its set go away;
  `reloadAction` (`lib/update.ts:59-68`) is unchanged.
- **When drafts go:**
  - All drafts, when the session loses its pairing.
  - A source draft, when the source is removed or no longer listed.
  - A comment draft, when its photo is deleted from this interface.
- **Comment:** the effect at `PhotoDetail.svelte:48-55` takes the new photo's draft when another photo opens. A late
  save answer for a photo writes the frame's form of the comment only into that photo's state, as today
  (`comment === sent` at :73).

*Alternatives:*
- **Ask before leaving:** rejected by the owner. A prompt per navigation, back gesture and photo switch needs history
  handling, which breaks Android's back gesture.
- **Drafts in `sessionStorage`:** rejected. They would bring old settings back after a reload, and the reload after an
  update is exactly when the form should start fresh.
- **Rebase on a change from the frame:** rejected by the owner.

### 8. Section editor with the keyboard

- **Viewport:** gets `tabindex="0"` and an `onkeydown`. It keeps `role="application"`, so screen readers pass the
  keys through, and is described by a hint that names the keys (`section.keysHint`).
- **Arrow keys:** call `change(dx, dy, 1)` like a drag in that direction: 2 % of the viewport's width per press, 10 %
  with Shift.
- **Zoom keys:** `+` (also `=`) and `-` call `change(0, 0, ZOOM_STEP or 1/ZOOM_STEP, true)` and stop at the sharp
  limit like the buttons.
- **Other keys:** handled keys call `preventDefault`, so the page does not scroll. Escape still cancels the dialog.
- **Focus ring:** the viewport shows the visible focus of "Keyboard operation".

*Alternative:* nudge buttons around the viewport. Rejected: more targets on a phone, and the arrow keys are what a
keyboard user tries first.

### 9. Focus after the upload dialog and the editor

`UploadPanel` and `SectionEditor` take `ConfirmDialog`'s pattern: the opener is the active element before `showModal`,
focused again on close if it is still connected. Both also get a `returnFocus?: HTMLElement` prop, used when the
active element was not a control (`document.body`, or the drop target):

- PhotosView passes the Upload button for a drop.
- PhotoDetail passes "Choose section".

### 10. Uploads: cancel, watchdog, retry

- **API:** `api.upload(file, album, onProgress, signal)`. The XHR marks the file `sent` at `xhr.upload.onload`.
- **Item states:** `waiting`, `uploading`, `sent`, `done`, `failed`, `cancelled` and `unclear`.
- **Before the body is fully sent:**
  - Abort through the signal → `cancelled`.
  - `onerror` → `failed` with "network".
  - No progress event for 60 s → abort, `failed` with "network".
  - All three are retryable.
- **After the body was sent:**
  - Cancel does not abort it; the file finishes.
  - No answer within 5 minutes, or `onerror` → `unclear`: "Sent, but the frame did not answer; check the overview
    before uploading it again". No retry button. The frame may store it (`.part`, then `uniqueFile`), and a retry
    would make a second copy.
- **Retry:** "Try again" on a retryable file sets it back to `waiting`, and Start uploads the waiting files. Files the
  frame refused for their content (415, `image_too_large`) or refused with a validation error get no retry button.
  Nothing is retried automatically.
- **Cancel and Escape while running:** stop the workers from taking new files, abort the files still `uploading`,
  and mark `waiting` files `cancelled`. The dialog stays open until the `sent` files have their answer; then it shows
  the summary, and Close closes.
- **New album:** Start is disabled while "New album" is chosen and the name is empty after trimming. The name field
  gets the hint `upload.nameRequired` through `aria-describedby`.
- **New keys:** `upload.retry`, `upload.cancelled`, `upload.unclear`, `upload.nameRequired`, `section.keysHint`,
  `common.discard`, all used in code, as `unusedKeys.test.ts` requires. `common.retry` was deleted on purpose by
  `core-cleanup`; `upload.retry` is specific.

*Alternatives:*
- **Abort after the body was sent:** rejected. The frame stores the file anyway, so the user would see "cancelled"
  for a stored photo.
- **Automatic retry of a failed send:** rejected. On the slower frame a stall can be the frame decoding, and a second
  attempt doubles the load.

### 11. Grid top: observe the page, not only the list

PhotosView's `ResizeObserver` also watches the nearest `main` element. Its height changes when anything above the list
opens, closes or wraps: `SkippedFiles`, `TrashPanel`, the error line, and the banners in `App.svelte`. On every resize
it calls `measureGrids`. A grid that grows resizes `main` too; `measureGrids` already skips an unchanged result
(:197-205), so the extra calls are cheap.

*Alternatives:*
- **Callbacks from `SkippedFiles` and `TrashPanel`:** rejected. They miss the banners and the error line.
- **A `MutationObserver`:** rejected. It reports DOM changes, not changes of height.

### 12. Numbers through `Intl.NumberFormat`

- `t()` formats every number param with an `Intl.NumberFormat` of the interface language, cached per language, before
  `format()` replaces it. `tn()`, the ranges, the upload summary and every other count follow.
- A number that must stay raw is passed as a string. The implementation checks the `t()` calls for such params; none
  is known.
- `PhotoDetail`'s megabytes use one decimal through `toLocaleString`, like `trash.ts:46-50`. The unit stays as it is
  in each place.
- `format()` in `detect.ts` stays language-free.

### 13. Selection across filter and sort: unchanged

The owner keeps the selection when visibility or sort order changes, in line with `album-groups-in-photos` decision
10. The selection bar already counts `selected.size`, and batch actions use all IDs. The new requirement records this,
and an e2e test pins it. Deleting goes to the 7-day trash, which limits the cost of acting on a photo no longer shown.

### 14. Accepted invitation by `invitedBy`, clock-free

- **Before the invitation:**
  - `ownId` is the ID of the entry with `current: true` in the loaded list.
  - "Invite device" shows only once the list has loaded and holds such an entry.
- **Snapshot:**
  - Right after `createInvite` answers, the view loads `GET /clients` once more and keeps its IDs as `snapshot`.
  - Until that answer, nothing counts as accepted.
  - If that load fails, nothing counts as accepted for this invitation: the QR code and the link stay until expiry or
    Close.
- **Match:** accepted is the first client in the polled list with `invitedBy === ownId` that is not in `snapshot`. A
  new invitation (renew) takes a new snapshot.
- **Edge cases:**
  - **Pairing by code or by the frame's QR code meanwhile:** `invitedBy` is `null`, so it never matches.
  - **Redeemed before the snapshot answered:** the device is in the snapshot and is not shown as accepted. It still
    appears in the list, and the panel shows the QR code until expiry or Close. This fails safe.
  - **Two tabs of the same device:** they share the token and so `ownId`. The second invitation replaces the first on
    the frame, so the first tab's QR code no longer works. A device that redeems the second shows as accepted in both
    tabs, as long as each tab's snapshot was taken before it paired.
  - **A redemption of the previous invitation** that completes on the frame just after the new invitation was
    created, but before the snapshot: it is in the snapshot, so it does not count. If it completes after the
    snapshot (milliseconds), it counts as accepted for the new invitation. That is a device this device invited
    either way, and the name shown is right.
  - **Close** does not withdraw the invitation; no endpoint exists. The remaining validity was shown, and a new
    invitation replaces it.

*Alternative:* by time, with `pairedAt >= expiresAt - 5 min`. Rejected: it needs the 5-minute constant, which the
contract states only in prose (`openapi.yaml:137`), and it breaks when the frame's clock jumps.

### 15. One fixture of limits

- **Fixture:** `api/fixtures/limits.json` holds:
  - the settings ranges (`intervalSeconds`, `transitionDurationMs`, `clock.sizePercent`, `cacheBudgetMb`,
    `displaySchedule.wakeMinutes`, `displaySchedule.dimLevel`, `brightness.level`);
  - the source limits (`intervalMinutes`, `originalsBudgetMb`, name and folder length, number of sources);
  - the comment length (500 code points);
  - the length of an album level (64) and of an album path (255);
  - the frame name (64), the device name (64) and the pairing code length (6).
- **`:core`:**
  - `LimitsFixtureTest` reads the fixture through a system property, as `presentationCases` is read
    (`core/build.gradle.kts:44-47`).
  - It compares each value with its Kotlin constant and with the contract's `minimum`, `maximum` or `maxLength` at
    the schema that carries it.
  - The literal `64` becomes a constant in `PhotoLibrary.kt:636` (album level) and in `PairingRoutes.kt:106` and
    :132 (device name). Behaviour is unchanged.
- **Web UI:**
  - `lib/limits.ts` exports the constants. `ranges`, `sourceRanges`, `MAX_SOURCES`, `MAX_COMMENT_LENGTH`,
    `RenameForm`'s check and every `maxlength` attribute use them.
  - `limits.test.ts` compares them with the fixture, as `presentation.test.ts` does.
  - A source scan in the same test fails on a `maxlength="<digits>"` literal in `src/components`.
- **Contract:** `maxLength: 500` on `Photo.comment` (`openapi.yaml:1824`), the one contract change, then
  `npm run gen:api`. JSON Schema counts `maxLength` in code points, as `Comments.MAX_CODE_POINTS` does.
  `PhotoPatch.comment` stays without it, since the frame normalizes longer input before counting.

*Alternative:* import the JSON in the web UI's production code. Rejected: the bundle would then depend on a file
outside `webadmin/`, which Vite serves only with an `fs.allow` exception. A test binds the copy just as well.

### 16. Order of the work

The poll helper and `call()` come first, since recovery, late answers and the polls build on them. Drafts come
before the focus and upload work, since `SettingsView` and `PhotoDetail` change for both. Limits and numbers are
independent. Each group lands its own tests.

## Risks / Trade-offs

- **[A new effect invalidates itself, as the drop loop of #141 did]** → The poll helper starts in a microtask.
  Components read their own state in effects through `untrack`. The e2e tests get a check, new with this change, that
  fails a test on a page error or on a console error naming `effect_update_depth_exceeded`. `svelte-check
  --fail-on-warnings` flags `state_referenced_locally`.
- **[A paused poll misses an installation finishing in a hidden tab]** → On return the state poll runs at once, and
  the reload decision follows within one answer.
- **[Drafts hide newer values of untouched fields]** → Accepted by the owner; saving does not send them, and
  "Discard changes" shows the frame's values.
- **[A comment draft for a photo deleted elsewhere keeps the interface from reloading by itself after an update]** →
  It shows the reload notice instead, which is the safe side; the draft goes with the page.
- **[The upload watchdog stops a slow but alive send]** → It counts progress events, not total time, and 60 s
  without progress is far beyond a phone on Wi-Fi. The answer timeout after a full send is 5 minutes, for a large
  HEIC on the slower frame. A device row checks both.
- **[Grouped numbers change visible texts and e2e expectations]** → `admin.spec.ts:160` and similar expectations
  change in the same pull request. The German and English catalogs need no edits, except `pair.locked` (`{time}`
  instead of `{seconds} seconds`).
- **[Observing `main` costs a measurement per resize]** → `measureGrids` compares before it assigns.
- **[Two tabs and the previous invitation]** → described in decision 14. Both fail safe or show a device this device
  did invite.
- **[`frame-time-zone` changes `SettingsView` and `PhotoDetail` at the same time]** → Disjoint lines (the times
  only). Whichever change lands second rebases. This change modifies none of the requirements that change is expected
  to touch.

## Migration Plan

Web UI only, apart from one contract keyword and two named constants in `:core`. The frames take the release through
the updater. Rollback is the previous release. Drafts live in memory, so nothing is stored that a rollback would need
to read.
