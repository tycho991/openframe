# Tasks

One implementation pull request for the whole change. Line references are to `main` at a675a5b; check them again
before editing. Everything in `webadmin/` unless named otherwise. The e2e runs use an own `OPENFRAME_E2E_PORT` (for
example 8190).

## 1. Failing tests first

- [ ] 1.1 Write the Vitest tests for the new behaviour against `main` and note in the pull request which of them fail
  there. Where a helper does not exist yet (`poll.ts`, `drafts.svelte.ts`, `limits.ts`), add the empty module with
  its signature first, so that the tests compile.
  - **`call()`** (`client.test.ts`):
    - a fetch that rejects with `TypeError` gives `ApiError(0, "network")`;
    - a 200 with an HTML body gives `ApiError(0, "network")`;
    - a body whose read fails gives `ApiError(0, "network")`;
    - a 429 with `Retry-After: 1200` gives `retryAfter === 1200`, and `retryAfter` is `null` without the header.
    - (`web-admin` › Unreachable frame in the interface language)
  - **Pairing lock** (`pairingError.test.ts`): `locked` with `retryAfter: 1200` and the message "Try again in 5 s"
    gives the text with `20:00` (› Pairing in the interface › Locked for longer).
  - **`PhotoPages`** (`photos.test.ts`):
    - Merge: two `reload()` calls while a three-page reload runs give one follow-up reload; the first reload's pages
      are applied (› Photo pages stay complete › Library changes every few seconds).
    - Duplicates: page 2 repeats an ID of page 1, and `items` holds it once (› Photo moves between pages).
    - Retry: a failed renewal in `onImageError` lets a later error on the same page ask again (› Renewal failed).
    - Concurrent errors: two image errors of one page at the same time cost one request.
  - **Numbers** (`i18n` test): `t("settings.range", { min: 5, max: 86400 })` in German gives "Erlaubt: 5 bis 86.400",
    and `tn` of 10234 photos gives "10.234 Fotos" (› Language › German numbers).

  Verify: `npm test` runs the new tests, and the ones named fail on `main` for the reason stated.
- [ ] 1.2 Write the Playwright tests against `main` and note which of them fail there. Add a shared fixture in
  `e2e/helpers.ts` that fails a test on a page error or on a console error naming `effect_update_depth_exceeded`.
  - **Unreachable frame** (› Recovery after an outage, › Unreachable frame in the interface language):
    - `page.route("**/api/v1/**", r => r.abort())` while the settings open, then unroute: the form appears within
      30 s, and no error stays;
    - the same for the photo overview (albums, skipped files, trash);
    - while aborted, saving the settings shows the German "nicht erreichbar" text and no "Failed to fetch" or
      "NetworkError".
  - **Action error stays:** revoking a device answers 500 through a route, and after the next device poll the error
    is still shown (› Recovery after an outage › Action error stays).
  - **Focus** (› Keyboard operation): after a drop on `.photos` and Escape, the focus is on "Hochladen"; after "Close"
    of the upload dialog opened by the button, the same; after Save and after Cancel in the section editor, the focus
    is on "Ausschnitt wählen".
  - **Keyboard section move** (› Choose a section › Move with the keyboard, › Zoom buttons stop at the sharp limit ›
    Stop with the plus key): Tab to the viewport, arrow keys change `data-y`, `+` stops at the sharp zoom, Save
    stores the shown part.
  - **Upload:**
    - Cancel while the third of ten files is being sent: the first two are uploaded, the rest show "try again", and
      "try again" uploads each of them once (› Cancel and retry uploads › Cancel during an upload). A request held by
      `page.route` has its body sent already and would count as sent, so this test throttles the upload through the
      Chrome DevTools Protocol with large fixture files and runs in Chromium only, with the reason in its skip;
    - "New album" without a name keeps Start disabled (› Upload in the interface › New album without a name).
  - **Drafts** (› Drafts until reload, › Discard a draft, › Edit settings):
    - a changed interval survives Photos and back;
    - a comment survives opening another photo and coming back;
    - "Änderungen verwerfen" restores the value;
    - a settings change made through the API while typing does not overwrite the field;
    - saving a draft keeps a language changed through the API meanwhile.
  - **Late answers** (› Late answers): a held display poll released after "Turn display off" leaves the display off;
    a held device poll released after a rename keeps the new name; a held album list released after a switch keeps
    the switch; hiding in the detail and opening another tile before the answer keeps the other photo open.
  - **Grid top** (› Grids follow the layout › Panel above the overview closes without scrolling): with skipped files,
    open and close their list without scrolling; the grid has no empty rows.
  - **Invitation** (› Accepted invitation): "Gerät einladen" is absent while `GET /clients` is held; a pairing by code
    while the invitation is shown leaves the QR code visible.
  - **Selection** (› Selection across filter and sort › Filter changed with a selection).
  - **Frame name in the header** (› Edit settings › Rename the frame).

  Verify: `npm run e2e` runs them, and the ones named fail on `main`.

## 2. Requests and polls

- [ ] 2.1 `call()` and `ApiError` (design decision 2): wrap `doFetch`, `res.text()` and `JSON.parse`
  (`lib/api/client.ts:98-106`), add `retryAfter`, and fill it in `toError` (:111-124) from the response headers.
  Verify: the `call()` tests of 1.1 pass; `client.test.ts` stays green.
- [ ] 2.2 `lib/poll.ts` with `poll()` and `Sequence` (design decisions 1 and 5), from `pollUpdate` (`lib/update.ts:17-50`);
  move `pollUpdate`'s tests and delete it. Verify: Vitest with fake timers and a fake `document.visibilityState`:
  - no attempt while hidden, one at once on return;
  - no overlap when an attempt is slower than the interval;
  - a hanging attempt is aborted at the deadline and the next one comes;
  - `invalidate()` drops the running answer and `apply` is not called;
  - `refresh()` starts at once;
  - `enabled: false` sends nothing;
  - the interval follows the function.
- [ ] 2.3 The state poll on `poll()` (`session.svelte.ts:120-135`), with `session.stateTick` raised on every
  successful answer; `refreshState()` becomes `statePoll.refresh()`. Verify: a Vitest that a failed state request
  leaves `stateTick` and a success raises it; `npm run typecheck` green; the existing reload-after-update tests in
  `update.test.ts` and `update.spec.ts` stay green (› Polling while visible › Request that hangs).
- [ ] 2.4 Move the sources (`SourcesView.svelte:42-46`), display (`SettingsView.svelte:58-62`), devices
  (`DevicesView.svelte:40-51`) and update (`SettingsView.svelte:151-158`) polls and the 60 s renewal
  (`PhotosView.svelte:402-407`) onto `poll()`; `pollLogin` stays. Verify: an e2e that the sources section sends no
  `/sources` request while the page's visibility is hidden and one at once on return (› Polling while visible ›
  Hidden tab); `sources.spec.ts`, `display.spec.ts`, `update.spec.ts` and `photos.spec.ts` stay green.

## 3. Recovery after an outage

- [ ] 3.1 `ViewErrors` in `lib/viewErrors.svelte.ts` from PhotosView's `showError`/`guard`/`background`
  (`PhotosView.svelte:47-66`, :309-327), with the kept failed loads and `retryFailed()` (design decisions 3 and 4).
  Verify: Vitest:
  - a background failure does not replace an action error;
  - a background success clears only its own source's error;
  - `retryFailed()` runs each failed load once and forgets those that succeed.
- [ ] 3.2 Use it in `PhotosView` (library load with trash and clients, settings), `SettingsView` (form load, display)
  and `DevicesView` (list, rename, revoke, invite), each calling `retryFailed()` on `session.stateTick`. Give the
  library effect (`PhotosView.svelte:372-384`) the `-1` guard of the settings effect (:391-392). Verify:
  - the unreachable-frame e2e tests and "Action error stays" of 1.2 pass;
  - an e2e counts one `/albums`, one `/skipped-files` and one `/trash` request when the overview opens.
- [ ] 3.3 `/info` in `App.svelte` (:42-47): loaded at start and on every `settingsVersion` change, retried on the next
  tick after a failure; `SettingsView.submit` (:235-256) writes the saved name into `session.info`. Verify: the e2e
  "Rename the frame" of 1.2 passes; a name changed through the API appears in the header within 30 s.

## 4. Late answers and the photo detail

- [ ] 4.1 `invalidate()` before applying an action's answer (design decision 5): `switchDisplay` and `setBrightness`
  on the display poll, rename and revoke on the device poll, "Check now" and "Install" on the update poll. Verify:
  the display and device cases of "Late answers" in 1.2 pass; `brightness.spec.ts` stays green.
- [ ] 4.2 `Sequence` for the album list (`toggleAlbum` bumps it, `PhotosView.svelte:422-439`), the settings form (a save
  bumps it) and the trash (a restore bumps it). Verify: the album case of 1.2 passes; a Vitest that a ticket taken
  before `bump()` is not current.
- [ ] 4.3 `replace()` sets `detail` only for the open photo, and `askDelete`'s action closes only a deleted open photo
  (`PhotosView.svelte:461-483`). Verify: the e2e "Another photo opened during a save" of 1.2 passes, and a new e2e
  "Detail closed during a save" (› Late answers): a held comment save released after Close leaves the detail closed.

## 5. Photo pages

- [ ] 5.1 In `PhotoPages` (`lib/photos.ts`): merge `reload()` with a running one (:280-301), unique IDs in `items`
  (:227-229), reset `retried` after a failed renewal (:362-374) (design decision 6). Verify: the `PhotoPages` tests of
  1.1 pass; the existing tests in `photos.test.ts` (reversed `setQuery` answers, the queue, the deadline) and
  `photos.spec.ts` stay green.

## 6. Drafts

- [ ] 6.1 `lib/drafts.svelte.ts` (design decision 7) with the settings draft, the source drafts and the comment drafts;
  `hasUnsavedInput()` reads it, and `markUnsaved` is removed from `lib/update.ts`. Verify: Vitest:
  - a draft appears on a change and goes when edited back;
  - `hasUnsavedInput()` stays true with no view mounted;
  - all drafts go when the pairing is lost.
- [ ] 6.2 `SettingsView` on the draft: the load updates `saved` only (:197-211), save sends `patch(base, form)`, and
  "Discard changes" (`common.discard`) appears next to Save while a draft exists. Verify: the settings draft tests of
  1.2 pass (› Drafts until reload › Back to the settings, › Other field changed on the frame, › Discard a draft, ›
  Edit settings › Settings change elsewhere while typing).
- [ ] 6.3 `SourceCard` on a source draft per ID (:30-32, :62-69) with "Discard changes"; the draft goes when the source
  is removed. Verify:
  - an e2e that a changed source interval survives Photos and back;
  - a Vitest of `reloadAction` with a source draft and no mounted view gives "notice" (› Drafts until reload ›
    Source draft and a frame update);
  - `sources.spec.ts` stays green.
- [ ] 6.4 `PhotoDetail` on comment drafts per photo (:41-63) with "Discard changes" next to "Save comment"; the draft
  goes when the photo is deleted here. Verify: the comment draft e2e of 1.2 passes (› Comment of another photo);
  `captions.spec.ts` and `detail.spec.ts` stay green.

## 7. Keyboard and focus

- [ ] 7.1 Section editor keys (design decision 8): `tabindex="0"`, `onkeydown` with the arrow keys (2 %, Shift 10 %)
  and `+`/`=`/`-` through `change(…, true)`, `section.keysHint` in `en.json` and `de.json`, and a visible focus ring.
  Verify:
  - `SectionEditor.test.ts`: arrow keys move `data-x`/`data-y` like a drag; `+` from zoom 1.8 on 4000×3000 at
    1920×1200 stops at about 2.08;
  - the keyboard e2e of 1.2 passes; `section.spec.ts` stays green.
- [ ] 7.2 Focus return in `UploadPanel` (:47-49) and `SectionEditor` (:61-63) as in `ConfirmDialog` (:14-21), with
  the `returnFocus` fallback from PhotosView (Upload button) and PhotoDetail ("Choose section") (design decision 9).
  Verify: the focus e2e tests of 1.2 pass in Chromium, Firefox and WebKit.

## 8. Uploads

- [ ] 8.1 `api.upload(file, album, onProgress, signal)` with the `sent` mark, the 60 s progress watchdog and the
  5-minute answer timeout (design decision 10). Verify: Vitest with a fake `XMLHttpRequest`:
  - abort before `upload.onload` → cancelled;
  - `onerror` before → network;
  - no progress for 60 s → network;
  - `onerror` or no answer after `upload.onload` → unclear;
  - abort after `upload.onload` is ignored.
- [ ] 8.2 `UploadPanel`: Cancel and Escape while running, the item states, "Try again" (`upload.retry`) for retryable
  files, the unclear text (`upload.unclear`), `upload.cancelled`, and Start disabled with `upload.nameRequired` for a
  new album without a name. Verify:
  - the upload e2e tests of 1.2 pass;
  - a component test with a fake `api.upload` shows "try again" for a network error before the file was sent and
    retries it once (› Retry after a network error), and shows the unclear text with no retry for an unclear
    outcome (› Sent without an answer);
  - `unusedKeys.test.ts` is green with the new keys.

## 9. Grid top, numbers and the pairing lock

- [ ] 9.1 PhotosView's `ResizeObserver` also observes the nearest `main` (design decision 11). Verify: the grid-top e2e
  of 1.2 passes; the existing "Grids follow the layout" e2e tests stay green.
- [ ] 9.2 Number params through `Intl.NumberFormat` in `t()` (design decision 12), `PhotoDetail`'s megabytes
  localized (:108), and every `t()` call with a number param that must stay raw checked. Verify: the number tests of
  1.1 pass; `admin.spec.ts:160` expects "Erlaubt: 5 bis 86.400"; a German e2e sees "3,4 MB" in the detail of a
  fixture photo of that size.
- [ ] 9.3 `pairingErrorText` uses `formatRemaining(retryAfter * 1000)`, falling back to 60 s without the header;
  `pair.locked` takes `{time}` in `en.json` and `de.json`. Verify: the pairing lock test of 1.1 passes;
  `pairing.spec.ts` stays green.

## 10. Accepted invitation

- [ ] 10.1 `DevicesView` and `InvitePanel` (design decision 14): "Invite device" only with a loaded list that holds
  the current device; the snapshot loaded after `createInvite`; the match by `invitedBy`; a new snapshot on renew;
  `knownIds` removed. Put the match in a pure function in `lib/devices.ts`. Verify:
  - Vitest for the function: a code pairing is not accepted; an invited device in the snapshot is not; one not in it
    is; with no snapshot nothing is;
  - the invitation e2e tests of 1.2 pass;
  - an e2e that a device redeeming the invitation shows as accepted within 30 s (› Accepted invitation › Invitation
    redeemed).

## 11. Limits and the contract

- [ ] 11.1 Contract first: `maxLength: 500` on `Photo.comment` (`api/openapi.yaml:1824`), then `npm run gen:api`.
  Verify:
  - `npm run lint` (Redocly) is green;
  - a contract-checked `:core` test that stores a comment of 500 emoji passes the response check, and one of 501 is
    refused with 400 as before.
- [ ] 11.2 `api/fixtures/limits.json` and `LimitsFixtureTest` in `:core` (design decision 15). Pass the fixture as a
  system property in `core/build.gradle.kts` next to `presentationCases` (:44-47). Name the literals
  `PhotoLibrary.kt:636` and `PairingRoutes.kt:106` and :132. Verify:
  - `./gradlew :core:test` is green;
  - changing any value in the fixture makes the test fail with the name of the limit.
- [ ] 11.3 `lib/limits.ts` with `limits.test.ts`. The web UI's copies use it: `ranges` (`SettingsView.svelte:25-33`),
  `sourceRanges` and `MAX_SOURCES` (`lib/sources.ts:120-127`), `MAX_COMMENT_LENGTH` (`lib/comments.ts:4`), the
  `maxlength` attributes and `RenameForm`'s check. Verify:
  - `limits.test.ts` compares with the fixture and finds no `maxlength="<digits>"` literal in `src/components`;
  - an e2e types 70 characters into the frame name and finds 64 (› Limits shared with the frame › Long frame name).

## 12. Checks

- [ ] 12.1 In `webadmin/`: `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e` with an own
  `OPENFRAME_E2E_PORT`; at the root `./gradlew check`. Verify: the command outputs are green.
- [ ] 12.2 `openspec validate webadmin-robustness --strict` exits 0 without warnings. Verify: the output.
- [ ] 12.3 In the pull request body, list the tests of group 1 that failed on `main`, and name the device rows of
  group 13. Verify: the body.

## 13. Device rows after the release (main session, privately)

Each persistent device change only with the owner's consent at that moment.

- [ ] 13.1 The main session adds rows to the private device checklist, each with its spec reference. Verify: the rows
  exist with their references.
  - **Slower frame, from a phone on Wi-Fi:** upload ten large photos, cancel during the third: the uploaded ones
    stay, the rest offer "try again", and a retry stores each once with no second copy in the photo folder
    (`web-admin` › Cancel and retry uploads › Cancel during an upload).
  - **Slower frame:** upload one large HEIC: it finishes without the watchdog or the answer timeout firing (› Cancel
    and retry uploads).
  - **Both frames:** with the settings and the photo overview open in a browser, restart the app on the frame. Within
    30 s after it answers, the views show their content without an error (› Recovery after an outage › Frame
    restarts while the settings are open, › Photo overview after a restart).
  - **Both frames:** a source draft and an update through the updater: the notice to reload appears, and the draft
    stays until the reload (› Drafts until reload › Source draft and a frame update).
- [ ] 13.2 Run the rows of 13.1 after the release, updating through the updater, with the owner's consent for the
  update and the test photos, and remove the test photos afterwards. Verify: results entered; anything that differs
  gets a finding in the private findings list.

## Workflow follow-up

- Archive the change after the device rows of 13.2 are done.
- If `frame-time-zone` is archived first, rebase this change's delta specs onto the synced `web-admin` spec before
  archiving.
