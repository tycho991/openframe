# Proposal

## Why

The web UI formats every time from the frame in the browser's time zone. Two of these times are only meaningful
in the frame's own zone:

- **The capture time of a photo** (`takenAt`, photo detail). A camera writes its EXIF time without a zone in most
  cases, and the frame places it in its own zone when it reads the file. Shown in another zone, the detail gives a
  time the camera never showed: a photo taken at 14:30 appears as 08:30.
- **The display's next switching time** (`nextChange`, "off until …" in the settings). The user enters the schedule
  as wall-clock times of the frame ("on at 07:00"). Shown in another zone, the same edge reads "until 01:00".

Both are wrong whenever the phone or PC is in another zone than the frame: an admin on a trip, a relative who looks
after the frame from another country, or a browser whose zone is set wrongly. The web UI cannot do better today,
because the frame does not tell its zone: `State` in the contract has no such field and allows no extra properties.

## What Changes

- **The frame reports its zone.** `GET /state` gets a required `timeZone`: the device's current time zone as an ID a
  browser can use, read afresh on each request.
  - An IANA region ID such as `Europe/Berlin` is reported as it is.
  - A zone with a fixed offset (a custom ID such as `GMT+01:00`, or plain UTC) is reported as `UTC` or as an ISO
    offset such as `+01:00`, because browsers reject `GMT+01:00` and `Z`.
  - A zone the frame cannot read is reported as `UTC`, the zone in which the frame then places capture times.
  - `GET /info`, which needs no token, does not get it.
- **The web UI shows frame times in the frame's zone.** A small helper formats an instant in the zone from the
  state. The photo detail's capture time and the display's next switching time use it.
  - When the browser is in another zone, the zone's short name follows the time ("21:30 GMT+9", "07:00 MEZ"). When
    both are in the same zone, the times look as they do today.
  - A zone ID the browser does not know (an old browser, older time zone data) falls back to the browser's zone, with
    that zone's name, so the time is never shown unlabelled in the wrong zone.
- **Times of events stay in the browser's zone.** Pairing and last contact of devices, deletion and "kept until" in
  the trash, the last synchronisation of a source, and the update's release and check times are moments, not frame
  wall-clock times. They stay as they are.
- **Tests run with two zones.** The test server gets a fixed zone (`Asia/Tokyo`) and a control path to change it, and
  Playwright runs the browser in `America/New_York`. Every e2e test therefore sees a frame in another zone, and the
  display test proves that the switching time is the frame's.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `frame-settings`: new requirement "Time zone of the frame", next to "Frame identity": what the frame reports about
  itself to paired clients. It serves capture times (`photo-library`) and switching times (`display-schedule`) alike,
  so it belongs to neither of those.
- `web-admin`: new requirement "Frame times in the interface". "Edit photos" and "Display state and manual switch"
  keep their text; the new requirement says in which zone their times appear.

## Impact

- **Contract:** `State.timeZone` (string, required) in `api/openapi.yaml`. The web UI comes from the frame, so both
  change together; until the first state has arrived, the web UI formats as today.
- **Code:**
  - `:core`: `StateDto` and the `/state` route in `api/LibraryRoutes.kt`; `libraryRoutes` gets the `Clock`, which
    `FrameCore` passes in; a small function that turns a `ZoneId` into the reported ID. java.time only, through core
    library desugaring as today; no Android import.
  - `:android`: nothing. `AndroidClock` keeps the default `zone()`.
  - `webadmin/`: `npm run gen:api`; `session` keeps the zone from the state; a new `lib/frameTime.ts` with Vitest
    tests; `PhotoDetail.svelte` and `SettingsView.svelte` use it. No new strings, so `de.json` and `en.json` are
    unchanged.
- **Tests:** a Kotlin API test with a `TestClock` zone and contract check; unit tests for the reported ID; the test
  server's fixed zone and `/zone`; Playwright's `timezoneId`; the midnight helper of the e2e tests in the frame's
  zone; the next-change regex in `e2e/display.spec.ts`; a new e2e test for the capture time.
- **Devices:** new rows in the private device checklist for both frames (Android 5.0 and 6.0): the reported
  `timeZone`, and capture time and next switching time from a browser in another zone.
- **Parallel work:** the change `webadmin-robustness` also touches `session.svelte.ts`, `SettingsView.svelte` and
  `PhotoDetail.svelte`. The overlap here is a few lines; whichever lands second rebases.
