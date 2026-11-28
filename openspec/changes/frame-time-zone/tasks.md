# Tasks

One implementation pull request for the whole change. Line references are to `main` at a675a5b; check them again
before editing.

## 1. Failing tests first

- [ ] 1.1 Kotlin tests, written against `main` and noted in the pull request:
  - **Reported ID** (design decision 2), a table test of the function in `api/FrameZone.kt`: `Europe/Berlin`,
    `CET` and `US/Pacific` stay as they are; `ZoneOffset.UTC` (`Z`), `UTC`, `GMT` and `GMT+00:00` give `UTC`;
    `GMT+01:00` and `Etc/GMT-1` give `+01:00`; `ZoneOffset.of("-05:00")` gives `-05:00`; `GMT+05:30` gives
    `+05:30`; a `zone` that throws `ZoneRulesException` gives `UTC` (`frame-settings` › Time zone of the frame ›
    Custom offset, › Plain UTC, › Zone the frame cannot read).
  - **State** (`LibraryApiTest`, `TestFrame` with a `TestClock` and the contract check): `/state` contains
    `"timeZone":"Europe/Berlin"` for `zoneId = Europe/Berlin` (› Zone of the frame); after setting
    `zoneId = America/New_York` the next `/state` contains it, with no restart (› Zone changed on the device); a clock
    whose `zone()` throws still gets 200 with `UTC`.
  - **Info** (`InfoRoutesTest`): the body of `GET /info` has no `timeZone` key (› Info without a token).

  Verify: the table test does not compile on `main` until 2.2 adds the function; the state tests fail on `main`
  because the field is missing; the info test passes already and stays as a guard.
- [ ] 1.2 Vitest `webadmin/src/lib/frameTime.test.ts` for `formatFrameTime` (design decision 4), each with an explicit
  `browserZone`:
  - `2024-07-14T12:30:00Z`, frame `Asia/Tokyo`, browser `America/New_York`, `de`, `dateStyle: "long"`,
    `timeStyle: "short"`: contains `21:30` and ends with the short name of Tokyo (`web-admin` › Frame times in the
    interface › Capture time from another zone);
  - the same with browser `Asia/Tokyo`: `21:30` and no zone name (› Browser in the frame's zone);
  - frame `Etc/UTC`, browser `UTC`: no zone name (canonical IDs equal);
  - frame `GMT+01:00` (unknown to `Intl`), browser `Europe/Berlin`: the Berlin time, followed by the Berlin zone name
    (› Zone unknown to the browser);
  - frame `null`: today's text in the browser zone, no zone name;
  - the weekday/hour/minute options of the display text: `00:00` and the zone name, no `TypeError`.

  Verify: the tests fail on `main` because `lib/frameTime.ts` does not exist.

## 2. Contract and frame

- [ ] 2.1 `api/openapi.yaml`, before any Kotlin: `State` (:2047-2066) gets `timeZone`
  (`type: string`, `minLength: 1`, in `required`) with a description of the rules of design decision 2, and the
  summary of `getState` (:683-695) mentions the zone. Then `npm run gen:api` in `webadmin/`. Verify: `npm run lint`
  (Redocly included) is green, and the generated `State` type has `timeZone: string`.
- [ ] 2.2 `:core` (design decisions 2 and 3): the function in `api/FrameZone.kt`; `StateDto`
  (`LibraryRoutes.kt:181-189`) gets `timeZone`; `Router.libraryRoutes` (:226) gets `clock: Clock` after `platform`;
  the route (:407-412) fills `timeZone` on each request; `FrameCore.kt:768` passes `clock`. Contract and DTO go into
  the same commit, since `State` allows no extra properties. Verify: the tests of 1.1 pass; `RouteAccessTest` and
  `./gradlew :core:check` (formatting, `checkNoAndroidImports`, Animal Sniffer) are green.

## 3. Web UI

- [ ] 3.1 `webadmin/src/lib/frameTime.ts` with `formatFrameTime` (design decision 4): `RangeError` from the frame
  zone's formatter falls back to the browser zone with its name; the label comes from `formatToParts` of a separate
  `timeZoneName: "short"` formatter; canonical IDs are compared through `resolvedOptions().timeZone`. Verify: the
  tests of 1.2 pass; `npm run typecheck` and `npm run lint` are green.
- [ ] 3.2 `session.svelte.ts`: `timeZone = $state<string | null>(null)` in `Session` (:7-26), set in `refreshState`
  (:101-113). `PhotoDetail.svelte:105-107` and `SettingsView.svelte:121-126` call `formatFrameTime` with their current
  options and `session.timeZone`. The other times of design decision 5 stay unchanged. Verify: `npm test` is green;
  `de.json` and `en.json` are unchanged (`unusedKeys.test.ts` green).

## 4. Test server and e2e

- [ ] 4.1 `TestServerMain.kt` (design decision 6): `TestServerClock` (:91-98) gets `@Volatile var zone` with
  `Asia/Tokyo` and overrides `zone()`; `reset()` (:253-262) restores it; the control path `/zone` answers the current
  ID without a parameter and sets `ZoneId.of(id)` with `?id=`, 400 for an invalid ID. Document it in the class comment
  next to the other paths. Verify: a test in `TestServerMainTest` for `/zone`, `/zone?id=Europe/Berlin`, an invalid
  ID and `/reset`; `./gradlew :core:test` is green.
- [ ] 4.2 `playwright.config.ts` (:15-19): `timezoneId: "America/New_York"` in the global `use`. `e2e/helpers.ts`
  (:30-50): `secondsToSkipMidnight(now, zone)` computes the time of day in `zone` with `Intl.DateTimeFormat`
  (`hourCycle: "h23"`), `avoidMidnight` passes the zone from `/zone`, and a helper `frameZoneLabel(page)` reads the
  short name of the frame's zone in the page. Verify: `npm run typecheck` and `npm run lint` are green.
- [ ] 4.3 e2e tests:
  - "Switch off now" (`e2e/display.spec.ts:111`): the regex expects `00:00` followed by the frame zone's label
    (› Next switching time from another zone). Before 4.2 and 3.2 it would show New York's time.
  - New test for the capture time: `landscape.jpg` over USB, the API reports `takenAt` `2024-07-14T12:30:00Z`, the
    detail shows `21:30` followed by the label (› Capture time from another zone). After `/zone?id=UTC` and a reload
    it shows `12:30` and `UTC`.
  - New test with `test.use({ timezoneId: "Asia/Tokyo" })`: the capture time shows `21:30` without a zone name (›
    Browser in the frame's zone).
  - The device list in the default setup shows the pairing time without the frame zone's label (› Pairing time from
    another zone).

  Verify: `npm run e2e` with an own `OPENFRAME_E2E_PORT` (for example 8190) is green in Chromium, Firefox and WebKit;
  the new capture-time test fails when `PhotoDetail.svelte` is switched back to `toLocaleString`.

## 5. Checks

- [ ] 5.1 `./gradlew check` with the Android SDK, and in `webadmin/` `npm run lint`, `npm run typecheck`, `npm test`
  and `npm run e2e` with an own `OPENFRAME_E2E_PORT` are green. Verify: the command outputs.
- [ ] 5.2 `openspec validate frame-time-zone --strict` exits 0 without warnings. Verify: the output.
- [ ] 5.3 The pull request body names the new `State.timeZone` field and the visible change (a zone name after
  capture time and switching time when the browser is in another zone). Verify: the body.

## 6. Device rows after the release (main session, privately)

Each persistent device change only with the owner's consent at that moment.

- [ ] 6.1 The main session adds rows to the private device checklist, each with its spec reference. Verify: the rows
  exist with their references.
  - **Both frames (Android 5.0 and 6.0):** the zone the system reports (read-only, `getprop persist.sys.timezone`)
    and the `timeZone` in `/state`; the reported ID is accepted by current Chrome, Firefox and Safari
    (`frame-settings` › Time zone of the frame › Zone of the frame).
  - **Both frames, from a browser in another zone** (the browser's or the computer's zone changed, not the frame's):
    the capture time of a photo with a known EXIF time without offset shows that EXIF time with the frame zone's name,
    and "off until …" shows the frame's switching time with it (`web-admin` › Frame times in the interface › Capture
    time from another zone, › Next switching time from another zone).
  - **Both frames, from a browser in the frame's zone:** the same times without a zone name (› Browser in the frame's
    zone).
- [ ] 6.2 Run the rows of 6.1 after the release, updating through the updater with the owner's consent. Verify:
  results entered; a frame that reports `UTC` although a zone is set, or an ID a browser rejects, gets a finding in the
  private findings list.

## Workflow follow-up

- Archive the change after the device rows of 6.2 are done.
