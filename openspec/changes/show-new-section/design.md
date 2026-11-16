# Design

## Context

`SlideshowController` (`:core`) decides what the display shows; `SlideState.Showing(photo, file, step, seq)` carries
the photo, its display-size file and a counter that the Android `SlideRenderer` swaps on. `show(step)` takes the first
photo in the requested direction for which `DisplayImages.ready(entry)` returns a file. `CachedDisplayImages.ready`
is `cache.peek(entry, DISPLAY) ?: cache.peekAnyDisplay(entry)`: the second part returns any finished display size of
the same image version, for example one of an older section, so that a photo is not skipped while its new part
renders. Nothing then renders the current part of the photo that is shown: `prefetchUpcoming()` covers only the
photos ahead in the order, and `onLibrary` (a section changed) only calls `prefetchUpcoming()`. The one place that
re-renders the photo on screen is `showCurrentAgain()` after a fill mode or orientation change; it awaits the render
inside the command loop and then `emit`s, which re-arms the timer. The timer sends `Command.Tick(mySeq)` and is
ignored when `seq` has moved on. See proposal.md for why.

## Goals / Non-Goals

**Goals:**
- The photo on screen always converges on its current part within one render, whatever made the part change.
- The command loop is not blocked while a large photo renders; steps, pauses and the display stay responsive.

**Non-Goals:**
- Rendering the photos behind in the order ("previous" history) in advance. The switch after showing them covers it,
  at the cost of the older part for a moment.
- Changing what `GET /slideshow` reports; it already gives the photo's current `presentation`.

## Decisions

### 1. `DisplayImages` says whether a file is the current part

`DisplayImages` gets `current(entry): File?` (the finished display size for the entry's current presentation, `null`
if it is not rendered) next to `ready(entry)`, and `render(entry, priority)` with a priority (`DEMAND` as today by
default). `CachedDisplayImages.current` is `cache.peek(entry, RenderSize.DISPLAY)`. The controller compares the file
it shows with `current(entry)`; equal means nothing to do.

*Alternative:* `ready` returns a pair (file, current). Rejected: every caller and the fake would change for one bit
that only the controller's switch needs.

### 2. One asynchronous switch for every outdated part

`refreshShown()` checks the `Showing` state: if `current(photo)` is not the shown file, it launches a coroutine in the
controller's scope that awaits `render(photo, NEXT)` and then sends `Command.Rendered(photoId, file)`. The handler
switches only if the same photo is still on screen with an older file and the display is not resting; otherwise it
drops the file (it is in the cache for the photo's next appearance). A newer `refreshShown()` cancels the previous
job. It is called after `emit` in `show` (a step to a photo with an older part), from `onLibrary` when the entry of
the photo on screen changed its presentation (a section set or removed), and from `Command.Presentation`
(fill mode, orientation) in place of the blocking `showCurrentAgain()`. On wake with `presentationChangedWhileResting`
it is called as well. A render failure is logged and leaves the older part, as `showCurrentAgain` does today.

*Alternative:* keep `showCurrentAgain` blocking and call it from `show` and `onLibrary`. Rejected: a 50-megapixel photo
takes seconds on the Lenovo, during which steps from the remote or the API would queue up.

### 3. The switch keeps the interval

The switch emits a new `Showing` with `step = 0` and a new `seq`, so that `SlideRenderer` swaps to the new file with
the transition it uses for step 0 today. The timer moves from "tick for `seq`" to a deadline: `armTimer()` stores the
time of the next change, and the switch re-arms the timer for the time left instead of a full interval. A paused or
resting slideshow has no deadline and gets none.

*Alternative:* swap the file without a new `seq`. Rejected: `SlideRenderer` and `CaptionOverlay` key on `seq`; a
second key for "same slide, new file" would touch the Android side for no gain.

## Risks / Trade-offs

- [The older part is visible for a moment after "previous" or a section change on screen] → It is replaced as soon as
  the render finishes (about a second on the Lenovo for a large photo); the spec allows "within a few seconds".
- [A render for the photo on screen competes with prefetch] → `NEXT` priority is the one the next photo gets; `DEMAND`
  stays for the first photo on an empty cache.
- [Step 0 transition on the switch looks like a photo change] → It is the same transition a fill mode change shows
  today; the photo itself is the same.
