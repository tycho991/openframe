# Design

## Context

See proposal.md for the motivation. Line references are to `main` at a675a5b (0.15.0 plus the proposal of
`scan-efficiency`). `scan-efficiency` touches only the local scan, so the two changes do not overlap. The parallel
change `webadmin-robustness` touches some of the same web UI files (see Risks).

**The zone on the frame:**

- `Clock.zone()` (`core/.../platform/Platform.kt:15-16`) returns `ZoneId.systemDefault()`, read afresh on each call.
  `AndroidClock` (`android/.../FrameApp.kt:293-297`) keeps that default. On Android 5/6 java.time comes from core
  library desugaring; the default zone is the system's `TimeZone.getDefault()` ID turned into a `ZoneId`.
- What that ID can be: normally an IANA region ID that the user picked in Android's settings (`Europe/Berlin`). Also
  possible are `GMT` (no zone set), a custom ID such as `GMT+01:00` (set by hand), legacy IDs such as `CET` or
  `US/Pacific`, an ID that maps to a fixed offset such as `-05:00`, and an ID that the app's time zone data does not
  know, for which `systemDefault()` throws a `DateTimeException`.
- The display schedule works in that zone: `DisplayController.decide` (`core/.../display/DisplayController.kt:353-359`)
  passes `clock.zone()` to `DisplayPolicy.decide` (`DisplayPolicy.kt:62-67`), which computes `nextChange`.
- Capture times: `parseExifDate` (`android/.../AndroidMediaEngine.kt:321-349`) places an EXIF time without an offset
  in the device's zone, and in UTC when the zone cannot be read (:342-347). The test server's `JvmMediaEngine` reads
  such times as UTC instead (`core/src/test/.../testing/JvmMediaEngine.kt:159-165`), so the fixture `landscape.jpg`
  has the capture time `2024-07-14T12:30:00Z` there (`JvmMediaEngineTest.kt:28`).
- `TestClock` (`core/src/test/.../testing/TestClock.kt:16, :25`) defaults to `ZoneOffset.UTC`, whose ID is `Z`.

**The contract and the route:**

- `GET /state` (`api/openapi.yaml:683-695`) returns `State` (:2047-2066): `libraryVersion`, `settingsVersion`,
  `transitionFallback`, `safeMode`, `appVersion`, all required, `additionalProperties: false`.
- `StateDto` is at `core/.../api/LibraryRoutes.kt:181-189`, `Router.libraryRoutes` at :226-239, the route at
  :407-412. `libraryRoutes` has no `Clock`; `FrameCore` has one (`FrameCore.kt:139`) and calls `libraryRoutes` at
  :768.

**The web UI:**

- `session` (`webadmin/src/lib/session.svelte.ts:7-26`) keeps what `refreshState` (:101-113) reads from `/state`;
  the state is polled at start and every 10 s while the tab is visible (:120-135).
- Frame-local times, formatted with `toLocaleString(i18n.lang, …)` in the browser's zone:
  - capture time: `PhotoDetail.svelte:105-107`, shown at :151 (`dateStyle: "long", timeStyle: "short"`);
  - next switching time: `SettingsView.svelte:121-126` (`weekday: "short", hour, minute`), inside "… until …".
- Other times, also in the browser's zone:
  - paired and last seen: `DevicesView.svelte:65-67`, used at :128-129;
  - deleted and "kept until" in the trash: `lib/trash.ts:26-38`;
  - last synchronisation of a source: `lib/sources.ts:39-43`, used at :93;
  - release date and last check of the update: `SettingsView.svelte:186-189`, used at :623 and :634;
  - the invitation's `expiresAt` is only a countdown (`lib/pairingLink.ts:24-25`); skipped files' `modifiedAt` and log
    entries are not shown.

**Intl facts that shape the helper** (checked in Node 24, which uses ICU like the supported browsers):

- `timeZone: "Z"` and `timeZone: "GMT+01:00"` throw `RangeError`. `UTC`, `GMT`, `Etc/GMT-1`, legacy links such as
  `CET` and `US/Pacific`, and ISO offsets such as `+01:00` are accepted. Offset IDs are a recent addition to ECMA-402;
  older engines throw `RangeError` for them.
- `resolvedOptions().timeZone` canonicalises: `Etc/UTC` and `GMT` give `UTC`, `CET` gives `Europe/Brussels` in
  Node 24.
- `dateStyle`/`timeStyle` cannot be combined with `timeZoneName`: that throws `TypeError`.

**Tests:**

- The test server's clock (`core/src/test/.../devserver/TestServerMain.kt:91-98`) has no zone override, so the frame
  runs in the machine's zone. `/reset` (:253-262) restores the clock; the control paths are in the `when` at :277.
- Playwright sets `locale: "de-DE"` but no `timezoneId` (`webadmin/playwright.config.ts:15-19`), so the browser also
  runs in the machine's zone (UTC in CI).
- `secondsToSkipMidnight`/`avoidMidnight` (`webadmin/e2e/helpers.ts:30-50`) compute the frame's midnight in Node's
  local time, relying on the shared machine zone.
- "Switch off now" (`e2e/display.spec.ts:93-116`) checks `/^Von Hand ausgeschaltet bis .+00:00$/` (:111).

## Goals / Non-Goals

**Goals:**

- The web UI shows capture time and next switching time as the frame sees them, from any browser zone.
- A zone ID that the browser cannot use never breaks a view and never shows a time unlabelled in the wrong zone.
- The e2e tests prove that the frame's zone is used, not the machine's.

**Non-Goals:**

- Setting the frame's zone from the web UI. That would be a system setting on Android 5/6 and needs permissions the
  app does not have.
- Labelling the schedule's and dim period's `HH:MM` fields with the frame's zone. They are frame times already; a
  hint there needs new strings (see "Open question" below).
- Changing how capture times are read (`parseExifDate`, `JvmMediaEngine`).
- Handling a zone the frame cannot read in the display schedule. `DisplayController` would fail there today; that is
  outside this change, and the device rows would show it as `UTC` in the state.

## Decisions

### 1. `timeZone` in `State`, required, not in `/info`

`State` gets `timeZone: { type: string, minLength: 1 }` in `required`, with a description of the rules of decision 2.
`StateDto` gets `val timeZone: String`.

*Alternatives:*
- **In `GET /info`:** rejected. `/info` needs no token, and the zone tells roughly where a frame is. Nothing without
  a token needs it.
- **In `DisplayState`:** rejected. The photo detail would have to fetch `/display` for a capture time, and the zone
  is not a property of the display.
- **A new endpoint:** rejected. One more route for `RouteAccessTest` and the contract, and one more request. The state
  is polled every 10 s anyway, so a zone change reaches the web UI within 10 s.

### 2. The frame reports an ID that browsers accept

A function in `:core` (for example `reportedZoneId(zone: () -> ZoneId): String` in `api/FrameZone.kt`) turns the
clock's zone into the reported ID:

- `zone()` throws a `DateTimeException` → `UTC`.
- `zone.normalized()` is a `ZoneOffset` (the zone has a fixed offset: `Z`, `UTC`, `GMT`, `GMT+01:00`, `Etc/GMT-1`,
  `-05:00`):
  - offset 0 → `UTC`;
  - otherwise the offset's ISO ID (`+01:00`, `-05:00`, `+05:30`).
- Any other zone → `zone.id` as it is (`Europe/Berlin`, `CET`, `US/Pacific`).

java.time with core library desugaring only; `./gradlew :core:check` (Animal Sniffer) covers it.

**Why `UTC` when the zone cannot be read:** `parseExifDate` then places capture times in UTC (:342-347), so the web
UI shows the EXIF wall time again. The route must not fail either: a 500 from `/state` would stop the version checks
of the whole web UI.

*Alternatives:*
- **Report `zone.id` raw and parse it in the browser:** rejected. Only java.time knows whether an ID has a fixed
  offset; the browser would need its own list of Java's special IDs (`Z`, `GMT+01:00`, mapped short IDs).
- **`Etc/GMT-1` for whole-hour offsets:** rejected. Its sign is inverted, it has no half hours, and the browsers the
  web UI supports (the two latest majors, `web-admin` › Supported browsers) accept ISO offsets. An older browser takes
  the fallback of decision 4.
- **An offset in minutes next to the ID:** rejected. An offset is right only for now; a capture time from another
  season needs the zone's rules. For fixed zones the ID already carries the offset.

### 3. The `Clock` goes into `libraryRoutes`; read on each request

`Router.libraryRoutes` gets `clock: Clock` after `platform`, and `FrameCore.kt:768` passes its `clock`. The route
calls the function of decision 2 on each request; nothing is cached, so a zone change shows in the next state
(`frame-settings` › Time zone of the frame › Zone changed on the device), as `Clock.zone()` already promises.

### 4. `frameTime`: format in the frame's zone, label when the browser differs, fall back on `RangeError`

`webadmin/src/lib/frameTime.ts`:

```ts
export function formatFrameTime(
  iso: string,
  frameZone: string | null,
  lang: string,
  options: Intl.DateTimeFormatOptions,
  browserZone: string = new Intl.DateTimeFormat().resolvedOptions().timeZone,
): string;
```

- **No zone yet** (`frameZone` is `null`, before the first state): format in the browser's zone without a label, as
  today. The views derive the text from `session.timeZone`, so it changes once the state arrives.
- **Frame zone usable:** format with `{ ...options, timeZone: frameZone }`. If the canonical IDs of the frame zone and
  the browser zone (`resolvedOptions().timeZone` of a formatter for each) differ, append a space and the zone's short
  name.
- **Frame zone unknown to the browser** (`RangeError` from the constructor): format in the browser's zone and always
  append the browser zone's short name. Other errors are not caught.
- **The label** comes from `formatToParts` of a second formatter `{ timeZone, timeZoneName: "short" }` (the part of
  type `timeZoneName`), because `dateStyle`/`timeStyle` cannot be combined with `timeZoneName`. The date and time keep
  today's format: "14. Juli 2024 um 21:30 GMT+9", "Von Hand ausgeschaltet bis Di., 00:00 GMT+9".
- `browserZone` is a parameter so that Vitest can fix it; the views use the default.

`session` gets `timeZone = $state<string | null>(null)`, set in `refreshState`. `PhotoDetail.svelte:105-107` and
`SettingsView.svelte:121-126` call `formatFrameTime` with their current options and `session.timeZone`. No new
strings.

*Alternatives:*
- **Always show the zone:** rejected. In the common case phone and frame share the zone, and a label on every time is
  noise.
- **Show the IANA ID instead of the short name:** rejected. "Asia/Tokyo" is long and not translated; the short name
  is localised ("MEZ" in German, "CET" in English).
- **Label only when the formatted times differ:** rejected. With zones that share an offset only part of the year, the
  label would come and go between photos of the same view.
- **Fall back to the frame zone's offset:** rejected. The browser cannot get the offset of a zone it does not know,
  and decision 2 already turns every fixed-offset zone into an ID it knows.

### 5. Which times use the frame's zone

| Time | Zone | Why |
|---|---|---|
| Capture time (`takenAt`) | frame | The frame placed the EXIF wall time in its zone; only that zone shows it again. |
| Next switching time (`nextChange`) | frame | The schedule is set in the frame's wall-clock times; "until 07:00" must match "on at 07:00". |
| Paired, last seen | browser | Moments of the user's own actions; the user relates them to their own clock. |
| Deleted, kept until | browser | Moments and a deadline counted from one; nothing on the frame is set in wall time. |
| Last synchronisation | browser | A moment; the user compares it with now. |
| Update released, last check | browser | Moments from GitHub and the updater. |

The invitation countdown and log entries show no wall-clock time in the web UI.

### 6. Tests in two zones

- **Test server:** `TestServerClock` gets `@Volatile var zone: ZoneId = ZoneId.of("Asia/Tokyo")` and overrides
  `zone()`. Tokyo has no summer time, so its label is the same all year ("GMT+9" in German), and it differs from CI's
  UTC and from the browser's zone. `/reset` restores it.
- **Control path `/zone`:** without a parameter it answers the current zone ID; `?id=Europe/Berlin` sets
  `ZoneId.of(id)` (400 for an invalid ID). The e2e helpers read the frame's zone from it.
- **Playwright:** `timezoneId: "America/New_York"` in the global `use`. Every e2e test thus sees a frame in another
  zone. One test uses `test.use({ timezoneId: "Asia/Tokyo" })` for the case without a label.
- **Midnight helper:** `secondsToSkipMidnight(now, zone)` computes the hour, minute and second of `now` in `zone`
  with `Intl.DateTimeFormat` (`hourCycle: "h23"`). `avoidMidnight` passes the zone from `/zone`. The doc comment no
  longer claims a shared machine zone.
- **Assertions:** the expected label is read in the page with `Intl.DateTimeFormat("de-DE", { timeZone: "Asia/Tokyo",
  timeZoneName: "short" })`, so the browsers' naming does not matter. "Switch off now" expects `00:00` followed by that
  label. The new capture-time test expects `21:30` (12:30 UTC in Tokyo) followed by it.

*Alternatives:*
- **Same zone for test server and browser:** rejected. The tests could not tell the zones apart.
- **A different browser zone only in a few tests:** rejected. The other tests would keep depending on the machine's
  zone without anyone noticing.
- **`Europe/Berlin` for the test server:** rejected. Summer time makes the label "MEZ" or "MESZ" by date.

### 7. Where the requirements go

- **"Time zone of the frame" in `frame-settings`:** the spec of what a frame reports about itself through the API
  (identity, settings version). The zone serves capture times (`photo-library`) and switching times
  (`display-schedule`) alike. `display-schedule` › Switching on time already requires that a zone change takes effect
  within a minute; that stays, and the new requirement adds the next-state rule for the reported ID.
- **"Frame times in the interface" in `web-admin`:** a new requirement instead of longer texts of "Edit photos" (421
  characters) and "Display state and manual switch" (452), which would go over 500.

## Risks / Trade-offs

- **[Risk] The browser's time zone data is older than the frame's, and a new IANA ID throws `RangeError`.** → The
  fallback of decision 4 shows the browser's zone with its name. The device rows check that both frames report IDs
  that current browsers accept.
- **[Risk] The device's zone is unknown to the app's time zone data.** → The state reports `UTC` (decision 2), which
  matches how capture times were stored then. The schedule's behaviour in that case is outside this change.
- **[Risk] Engines canonicalise differently** (`CET` gives `Europe/Brussels`), so a frame and a browser with the same
  rules but different IDs show a label. → The label is right, only redundant.
- **[Risk] A browser names a zone differently** ("GMT+9", "JST"). → e2e tests read the expected name from the
  browser's own `Intl` (decision 6); Vitest runs in Node with fixed zones.
- **[Risk] The global `timezoneId` changes the browser zone of every e2e test.** → Only "Switch off now" checks a
  frame time today; the other formatted times are not asserted by value. The full e2e run in all three browsers is a
  task.
- **[Risk] Merge overlap with `webadmin-robustness`** (`session.svelte.ts`, `SettingsView.svelte`,
  `PhotoDetail.svelte`). → A few lines each; whichever lands second rebases. If that change moves the state poll into
  a shared helper, `timeZone` moves with the other state fields.
- **[Trade-off] A label that appears depending on the browser.** → Intended: without it, a time in the frame's zone
  could be mistaken for one in the browser's.

## Migration Plan

Nothing is stored. The frame and its web UI update together; a web UI that has not yet received a state formats as
today. Rollback is the previous APK.

## Open question

- **Hint at the schedule fields:** when the browser's zone differs, the settings could say once, near the display
  schedule and the dim times, that these times are in the frame's zone (with its name). That needs one new string in
  `en.json` and `de.json`. Left out to keep the change small; the owner may want it added.
