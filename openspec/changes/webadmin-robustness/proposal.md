# Proposal

## Why

The web UI works well while the frame answers quickly and the user does one thing at a time. A review of the web UI
against `main` found that it breaks down at the edges: after a short outage, with slow or late answers, with the
keyboard, and with input that is not saved yet.

- **After an outage, views stay broken.** A load that failed is not tried again: the settings form, the device list,
  the album list, the skipped files, the trash and the settings in the photo overview wait for the next change of a
  version, which a restart of the frame may not bring. Only the photo grids and the sources recover. An error from a
  background load stays on screen after the next load succeeds. A rejected request shows the browser's own English
  text ("Failed to fetch", "NetworkError when attempting to fetch resource"), not the interface's.
- **Late answers overwrite newer state.** Every view polls in its own way: the state poll can overlap itself, the
  sources poll runs in a hidden tab, and only the photo list and the update section give up on a request that hangs.
  A poll that started before the user switched the display, set the brightness, renamed a device or switched an
  album can land after the answer to that change and show the old state again. A late answer to a save reopens the
  photo detail or switches it to the saved photo, although the user has closed it or opened another one.
- **The photo list does not always complete.** A reload of the open grids restarts when the library changes again,
  so with changes every few seconds a long reload of many pages never finishes. A photo that moves between pages
  while they load can appear twice in a grid. After a failed renewal of a page's image addresses, its images stay
  broken until the next reload.
- **Typed input is lost.** Leaving the settings, a source's settings or a photo's comment drops what was typed. A load
  of the settings that arrives while the user types overwrites the input. A source's settings form does not count as
  unsaved input at all, so an automatic reload after a frame update loses it.
- **Keyboard and focus.** The section editor cannot be operated with the keyboard: its viewport takes no focus and no
  keys. Closing the upload dialog or the section editor leaves the focus nowhere.
- **Uploads cannot be stopped.** A running upload can be neither cancelled nor retried, and Cancel and Escape do
  nothing while files are sent. "New album" without a name uploads to the top level.
- **Smaller faults.**
  - Content above the photo list that opens or closes without scrolling (skipped files, trash, an error, a banner)
    leaves rows of the grids blank.
  - Numbers are not localized ("Erlaubt: 5 bis 86400", "3.4 MB" in German).
  - The pairing lock text takes its seconds from the frame's English message, while the lockout now grows up to an
    hour.
  - The frame name in the header changes only after a reload.
  - The UI guesses an accepted invitation as any device that was not in a list it may not have loaded yet, so a
    pairing by code in the same minutes counts as accepted.
  - The limits in the UI (ranges, field lengths) are copies of the frame's with no test binding them.

The frame takes every release through the updater now, so these fixes need no particular release.

## What Changes

- **One way to poll.** Every repeated request (state, sources, display, devices, update, the renewal of image
  addresses) runs through one helper: only while the tab is visible, at once when it becomes visible again, never two
  at a time, with a deadline, an interval that may follow the state, and a way to discard an answer that started
  before a change the user made.
- **Recovery after an outage.** A failed load is tried again once the frame answers the state poll, in every view
  that has one. A load error is cleared by the next successful load of the same thing; an error of the user's action
  stays until the next action. A rejected request or an unreadable answer is the error "The frame cannot be reached"
  in the interface language. The frame's info is loaded again when its settings change, so a new frame name shows
  in the header.
- **Late answers.** An answer that started before a newer change of the same thing, before a change of filter or sort
  order, or for a photo that is no longer open, does not replace what the interface shows.
- **Photo list.** A reload that starts while one runs is merged with it and runs once more at its end instead of
  starting over. A grid shows a photo at most once. A failed renewal of a page may be tried again at the next image
  error. The overview no longer loads album list, skipped files and trash twice when it opens.
- **Drafts until reload.** The settings form, each source's settings and the comment of each photo keep unsaved input
  as a draft while the user moves around the interface, until the page reloads. A load never overwrites a draft.
  Saving sends only the fields the user changed. A visible "Discard changes" next to Save drops the draft. A draft
  counts as unsaved input for the reload after a frame update, also while its view is not shown. No confirmation
  prompt, no warning when the page is left.
- **Keyboard and focus.** The section editor's viewport takes the focus: the arrow keys move the part, `+` and `-`
  zoom and stop at the sharp limit like the buttons. Closing the upload dialog or the section editor returns the focus
  to the control that opened it, or to "Upload" after a drop.
- **Uploads.** Cancel stops a running upload: files not fully sent are aborted, files fully sent finish. A file that
  failed before it was fully sent can be tried again without choosing it again. A file whose outcome is unclear (sent,
  but no answer) is not retried and says so. "New album" needs a name before the upload starts.
- **Grid top.** The grids measure again when content above the photo list changes its height without scrolling.
- **Numbers** are written with the separators of the interface language: counts, megabytes and the allowed ranges.
- **Pairing lock.** The dialog shows the waiting time from the frame's `Retry-After`, as minutes and seconds.
- **Accepted invitation.** "Invite device" waits until the device list has loaded. Accepted is the first device that
  this device invited and that was not in the list loaded right after the invitation was created; a pairing by code
  or by the frame's QR code meanwhile does not count.
- **Limits.** One fixture of the limits that the UI shares with the frame, checked by tests in `:core` against the
  contract and the Kotlin constants and in the web UI against its own constants. The contract gets `maxLength: 500`
  on `Photo.comment`.
- **Unchanged on purpose:**
  - The selection stays when the filter or the sort order changes, as for folded albums. A requirement states this.
  - The router, the browser history and leaving the page.
  - The add-share fields keep no draft.
  - The times of the frame (capture time, next switching time) are a change of their own, `frame-time-zone`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `web-admin`:
  - MODIFIED: "Pairing in the interface", "Choose a section", "Upload in the interface", "Edit settings", "Language",
    "Keyboard operation", "Zoom buttons stop at the sharp limit" and "Grids follow the layout".
  - ADDED: "Polling while visible", "Recovery after an outage", "Unreachable frame in the interface language", "Late
    answers", "Photo pages stay complete", "Cancel and retry uploads", "Drafts until reload", "Discard a draft",
    "Accepted invitation", "Selection across filter and sort" and "Limits shared with the frame".
  - "Invite a device" (414 characters) keeps its text; the new rule is the ADDED "Accepted invitation".

## Impact

- **Web UI (`webadmin/`):**
  - New: `lib/poll.ts` (from `pollUpdate` in `lib/update.ts`), `lib/drafts.svelte.ts`, `lib/limits.ts`.
  - Changed:
    - `lib/api/client.ts`: `call()` errors, `retryAfter`, upload with a signal.
    - `lib/session.svelte.ts`: the state poll, a counter of successful state polls, the frame's info.
    - `lib/photos.ts`, `lib/pairingError.ts`, `lib/i18n/` (number formatting, new keys in `en.json` and `de.json`).
    - `PhotosView`, `SettingsView`, `DevicesView`, `SourcesView`, `SourceCard`, `PhotoDetail`, `UploadPanel`,
      `SectionEditor`, `InvitePanel`, `App.svelte`.
  - `npm run gen:api` after the contract change.
- **Contract:** `api/openapi.yaml` gets `maxLength: 500` on `Photo.comment`. No new endpoint, no new field, no
  changed status code.
- **`:core`:** no behaviour change. The literal `64` of the device name (`PairingRoutes.kt`) and of an album level
  (`PhotoLibrary.kt`) become named constants. A new test checks `api/fixtures/limits.json` against the constants and
  the contract.
- **Tests:**
  - Vitest for the poll helper, `call()`, drafts, limits, number formatting and the invitation match.
  - Playwright for an unreachable frame, focus after the dialogs, keyboard moves in the section editor, upload
    cancel and retry, drafts and the grid top.
- **Devices:** rows in the private device checklist for upload cancel and an unclear outcome on the slower frame,
  and recovery after a restart of the frame.
