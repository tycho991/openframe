# Tasks

## 1. Contract first

- [x] 1.1 `api/openapi.yaml`: tag `slideshow`; path `/slideshow` with `getSlideshow` (200 `SlideshowState`, 401, 503) and `controlSlideshow` (body `SlideshowCommand`; 200 `SlideshowState`, 400, 401, 403, 409, 503); schemas `SlideshowState` (`photo`: `Photo` or null, `paused`, `resting`, all required) and `SlideshowCommand` (`action` enum `next|previous|pause|resume`, required, no other fields); `display_off` in the description of `Conflict` and `safe_mode` in the 503 descriptions (design decision 5); verify: `npm run lint` in `webadmin/` passes, including Redocly
- [x] 1.2 Run `npm run gen:api` in `webadmin/`; verify: `webadmin/src/lib/api/schema.d.ts` has `SlideshowState` and `SlideshowCommand`, `git diff --stat` shows only generated changes there, and `npm run typecheck` passes

## 2. Slideshow control in `:core`

- [x] 2.1 `SlideshowController.control(action)`: the command carries a `CompletableDeferred` that the handler loop completes after handling it, also when the handler throws (design decision 3); the display's methods unchanged; verify: unit tests that `control(NEXT)` returns after `state` shows the next photo and the timer restarted, that `control(PAUSE)` returns with `paused` true, and that a step while resting returns with the photo unchanged
- [x] 2.2 Move `dto()` and `urls()` out of `libraryRoutes` into `PhotoDtos` in `core/.../api/` (design decision 2); verify: `LibraryApiTest`, `QuarantineTest` and `PhotoListPerformanceTest` pass unchanged
- [x] 2.3 `SlideshowRoutes`: `GET`/`POST /api/v1/slideshow` with the safe-mode refusal first, the 409 `display_off` for steps while resting, 400 for a missing, unknown or extra field, the two-second wait, and the photo from the current library; registered in `FrameCore.registerRoutes` with `health::safe`; verify: `TestHttp(..., checkContract = true)` or `TestFrame` tests for every scenario of "Slideshow state through the API", "Operating the slideshow through the API", "Operating the slideshow while the display is off" and "Slideshow API in safe mode" in this change's `slideshow-display` delta, and `RouteAccessTest` passes with both operations behind the token
- [x] 2.4 Run `./gradlew check`; verify: green, including formatting, `checkNoAndroidImports`, the API 21 check and Android Lint

## 3. Devices

- [x] 3.1 Row in `../openframe-private/DEVICE_TEST.md` with its reference `slideshow-display` › Delay of a command from afar › Next photo on the Frameo, measured with `curl` and a token from a paired test client; verify: the row exists with its reference
- [x] 3.2 Run the row of 3.1 on the Frameo; ask before installing a build and before any change on the device; verify: result entered, a finding in `../openframe-private/FINDINGS.md` if the delay is above two seconds

## 4. Integration

- [x] 4.1 `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck` and `npm test`; verify: all green
- [x] 4.2 `openspec validate add-slideshow-control-api`; verify: exit 0 without warnings

## Workflow follow-up

- Release this change before `ha-openframe`'s `add-home-assistant-integration` is implemented; the integration pins the openframe version that has `/slideshow`.
- Archive the change after the device row of 3.1 is done.
