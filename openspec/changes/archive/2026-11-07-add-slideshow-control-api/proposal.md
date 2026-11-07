# Proposal

## Why

The slideshow can only be operated on the frame itself: swiping changes the photo, the hint pauses and resumes it. The API offers neither the photo on screen nor these commands, so no client can show what the frame shows or step through the photos from afar. The planned Home Assistant integration (`tycho991/ha-openframe`) needs both for its "current photo" image, its next/previous buttons, its pause switch and its dashboard card. `pictureframe` had this as `/api/control`; the bootstrap change left it for the Home Assistant follow-up on purpose.

## What Changes

- **Reading the slideshow.** `GET /slideshow` returns the photo on screen as the same `Photo` record as the photo list (or `null` while none is shown), whether the slideshow is paused and whether it rests because the display is off.
- **Operating the slideshow.** `POST /slideshow` with `{"action": "next" | "previous" | "pause" | "resume"}` does what the gestures and the hint on the frame do: the next or previous photo with the interval starting over, pause and resume. It answers with the state after the command has taken effect, so a client needs no second request.
- **Limits.** While the display is off, `next` and `previous` are refused with 409 (`display_off`), because the resting slideshow changes no photo; pause and resume still apply. In safe mode both endpoints answer 503 (`safe_mode`), because the slideshow does not run.
- Both endpoints need a token like every other endpoint outside info and pairing. All of it is additive for clients.

### Out of scope

- Buttons for next, previous and pause in the web UI. The web UI can use the endpoints in a later change.
- Push notifications (SSE, WebSocket) of a photo change. Clients poll `GET /slideshow`; it is cheap.
- Jumping to a specific photo, and a pause that survives a restart of the app.
- The Home Assistant integration itself; it is planned in its own repository.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `slideshow-display`: reading the photo on screen and the pause and resting state through the API, operating the slideshow through the API, and how the commands behave while the display is off and in safe mode.

## Impact

- `api/openapi.yaml` first: path `/slideshow` with `getSlideshow` and `controlSlideshow`, schemas `SlideshowState` and `SlideshowCommand`, the error codes `display_off` and `safe_mode` in the responses, a new tag `slideshow`; then the Kotlin DTOs and `npm run gen:api` (types only, no web UI change).
- `:core`: `SlideshowController` gains a way to wait until a command has been handled; new `SlideshowRoutes` next to `DisplayRoutes`, registered in `FrameCore` with the safe-mode flag; `RouteAccessTest` covers the new path.
- `:android`: none. The display already follows `SlideshowController.state` and `paused`, so a command from the API looks the same as a swipe.
- `../openframe-private/DEVICE_TEST.md`: a row for the delay between the request and the photo change on the Frameo.
- Prerequisite of the three changes in `tycho991/ha-openframe`; this change is released first.
