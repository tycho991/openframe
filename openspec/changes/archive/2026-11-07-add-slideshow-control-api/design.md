# Design

## Context

`FrameCore` owns the `SlideshowController`. The display renders its `state` (`SlideState.Showing` with the `PhotoEntry` on screen) and its `paused` flow, and reports swipes with `next()`/`previous()` and the hint's button with `setPaused()`/`togglePause()`. All of these put a command into an unbounded channel that one coroutine handles in order; the methods return before the command has taken effect. While the display is off, `resting` is true and the controller ignores steps. In safe mode `FrameCore` never calls `slideshow.start()`, so nothing reads the channel. The photo record of the API is built by a local `dto()` function inside `Router.libraryRoutes`, together with the signed image URLs and the place name. See proposal.md for why.

## Goals / Non-Goals

**Goals:**
- A command from the API and a gesture on the frame go through the same path in the controller, so they cannot behave differently.
- The answer of `POST /slideshow` reflects the command, so the Home Assistant integration can update its entities without a second request or a fixed delay.
- `GET /slideshow` costs about as much as `GET /display`, so a client can poll it every few seconds even on the Frameo.

**Non-Goals:**
- Changing how the display reacts to the controller. `:android` stays as it is.
- A version counter for the slideshow in `GET /state`. A client that wants to know the photo polls `GET /slideshow` directly.

## Decisions

### 1. One path `/slideshow` with an action, not one path per command

`GET /slideshow` returns `SlideshowState` (`photo`: `Photo` or `null`, `paused`, `resting`). `POST /slideshow` takes `SlideshowCommand` (`{"action": "next" | "previous" | "pause" | "resume"}`) and returns `SlideshowState`. This matches `PUT /display` (one resource, a small body) and `pictureframe`'s `/api/control/slideshow`, which the integration's structure is modelled on.

*Alternative:* `POST /slideshow/next`, `/previous`, `/pause`, `/resume`. Rejected: four paths for one resource, each with the same responses in the contract and in `RouteAccessTest`. *Alternative:* `PATCH /slideshow` with `{"paused": true}` for the pause. Rejected: next and previous are no state but events, and one verb for all four keeps the client simple. *Alternative:* `toggle` as a fifth action. Rejected: a client that knows the state sends `pause` or `resume`, which is idempotent; a toggle sent twice by a retry does the opposite of what was meant.

### 2. The full `Photo` record in the state

`SlideshowState.photo` is the same `Photo` schema as an item of the photo list, built by the same function, so the integration gets the comment, the album, the section, `presentation` and the signed image URLs without a second request. There is no `GET /photos/{id}`, so a bare ID would force the client to page through the list. `dto()` and `urls()` move out of `libraryRoutes` into a small `PhotoDtos` class in `core/.../api/` that both routes use. The entry comes from the current library (`index.library.value.photos[id]`), falling back to the entry in `SlideState.Showing`, as `FrameCore.caption` already does, so an edited comment shows at once.

*Alternative:* only `photoId`. Rejected for the reason above.

### 3. Waiting until a command has been handled

The controller gets `suspend fun control(action: SlideshowAction): Unit`, which sends the command with a `CompletableDeferred<Unit>` and waits until the handler loop has handled it. `next()`, `previous()`, `setPaused()` and `togglePause()` stay as they are for the display. The route waits at most two seconds (`withTimeoutOrNull`) and then answers with whatever state there is. A step that needs to render the first photo on a cold cache can take longer than that, and a client must not hang because of it.

The route reads `resting` before it sends a step and refuses with 409 `display_off`; the controller's own check stays as the last line of defence for the race where the display goes off in between, in which case the step is ignored and the answer shows the unchanged photo.

*Alternative:* answer 202 at once and let the client poll. Rejected: Home Assistant would show the old photo for one polling interval after a button press. *Alternative:* wait on `state` until `seq` changes. Rejected: a pause changes no `seq`, a step at the end of a one-photo library may change nothing, and an ignored step would always run into the timeout.

### 4. Safe mode answers before touching the controller

`slideshowRoutes(slideshow, photos, safeMode)` takes the same `safeMode: () -> Boolean` as `libraryRoutes` and throws `safeModeRefusal()` (503 `safe_mode`) first, for `GET` too. Since the handler loop does not run in safe mode, `control()` would otherwise wait for the full timeout. `GET` returns 503 rather than an empty state because no slideshow exists in safe mode, and `GET /state` already tells a client that the frame is in safe mode.

### 5. Errors and the contract

New error code `display_off` in the `Conflict` response; the existing `safe_mode` gets the `Unavailable` response on both operations. An unknown or missing action is 400 with `action` in `fields`, unknown fields are 400 like in `PUT /display`. The new tag `slideshow` gets a description. `RouteAccessTest` checks that both operations need a token.

## Risks / Trade-offs

- [A client polls `GET /slideshow` often, and every call signs three URLs and looks up a place name] → Signing is an HMAC and `PlaceIndex` caches; the cost is that of one photo list item. The integration polls every 10 to 15 seconds (planned in `ha-openframe`).
- [The two-second timeout hides a slow render: the answer shows the old photo, the frame shows the new one a little later] → The next poll corrects it; the timeout is only reached on a cold cache.
- [A step races with the display going off] → The controller ignores it as today; the answer shows the unchanged photo instead of a 409.
- [The signed image URLs in the state expire after two hours] → A client reads the state again before it fetches an image, or uses the token with `GET /photos/{id}/image`.

## Migration Plan

Additive endpoints; no stored data changes. Older clients are not affected. The integration requires an openframe version with these endpoints and refuses older frames with a clear message (planned in `ha-openframe`).
