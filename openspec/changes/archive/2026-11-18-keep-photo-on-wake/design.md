# Design

## Context

See proposal.md for the motivation. What is in place:

- `FrameCore` feeds `!display.state.on` into `SlideshowController.setResting`. Every way the display goes off or
  on passes through that one flow: schedule, manual switch, `PUT /display`, power button, end of a wake period. The
  dark wake (screen on for the network, display off by the schedule) keeps `resting` true.
- `SlideshowController` handles everything in one command loop. `Command.Rest(true)` cancels the timer.
  `Command.Rest(false)` calls `wake()`, which shows `+1` unless the slideshow is paused and the current photo still
  exists. While resting, `Step`, `Tick`, `Undecodable` and `Presentation` are ignored, and `onLibrary` updates the
  playlist but returns before touching the screen.
- The paused path already keeps the current photo across a night. "Paused before the night" holds on both frames,
  so the Android side already shows the kept photo again after the screen comes back: `DisplayActivity.onStop` clears
  the renderer, and `onStart` collects `slideshow.state` again and draws `SlideState.Showing` from its file.

## Goals / Non-Goals

**Goals:**

- The change stays inside `SlideshowController` and its tests. No change to the API, the settings, Android or the
  web interface.

**Non-Goals:**

- Continuing the remaining time of the interval. The interval starts over (decision 2).
- Making the "same photo" setting configurable. The owner wants it as the only behavior.

## Decisions

### 1. `wake()` keeps the current photo when it is still in the order

`wake()` becomes:

- empty playlist → `SlideState.Empty`, as today
- nothing shown yet → `show(0)`, as today
- the current photo is no longer in the order, because it was deleted or hidden → `show(+1)`, also while paused
- otherwise → keep `SlideState.Showing` unchanged, `prefetchUpcoming()` (nothing was rendered ahead while resting,
  and the library may have changed) and `armTimer()`

"In the order" is checked with the playlist that `onLibrary` already updated during the rest, and not with
`exists()`. `exists()` only knows about deleted photos. A hidden photo would stay for another interval, although
hiding is a reason to leave it.

- *Alternative:* keep `exists()` and let a hidden photo stay until the next change, as a hidden photo does while
  the display is on ("hidden, not deleted: no immediate change"). Rejected: while the display is on, the photo is
  hidden in front of the user, who sees it go at the next change. After a night the user would see a photo they hid
  in the evening appear as the first one in the morning.

If the playlist no longer contains the photo, `playlist.next()` gives the photo after its former position. That is
how a deletion while the display is on works today ("The order already points to the next photo").

### 2. The interval starts over

`armTimer()` arms a full interval, as after a swipe. The photo just came back after the display was off, so a
photo that changed after a few seconds would look like a glitch.

- *Alternative:* store the remaining time when resting starts and arm only that. That adds state for little gain,
  and after a night a remaining time of 3 s is pointless.

### 3. A presentation change while resting is remembered

`Command.Presentation` while resting sets a flag `presentationChanged`. On wake, if the flag is set and the photo
stays, `showCurrentAgain()` renders it in the new presentation. `showCurrentAgain()` already handles the paused case
of a presentation change while the display is on. This also fixes the paused path, which today keeps a display size
from before the change after a night.

`showCurrentAgain()` renders synchronously in the command loop, as it does today, and emits with `step = 0`, which
gives no slide direction. Until it finishes, the old display size stays on screen for the render time (under a
second on the frames). `nextUp` is prefetched first so that the render queue does not starve.

- *Alternative:* render the new size during the rest. That breaks "nothing rendered while the display is off",
  which protects the Frameo's memory and CPU at night.

### 4. `next` and `previous` while off are unchanged

The API still answers 409 `display_off`, and the controller still ignores a step while resting. Only the outcome
after waking changes, and it now matches "as it is without the command" again.

## Risks / Trade-offs

- [The current photo's display file was evicted from the image cache during a long night] → `SlideState.Showing`
  carries the file path, and the paused path has already relied on that file surviving a night. If the file is
  gone, the renderer reports the photo as undecodable through `reportUndecodable`, and the slideshow moves on, as
  for any broken file. Check on the devices that the photo shows after a night (task 3.1).
- [A user who liked the morning's new photo loses it] → That is what the owner asked for. `next` from the card or
  a swipe gives a new photo in one tap.
- [Home Assistant's image entity does not change on wake] → It changes only when the photo changes. That is now
  correct: the card shows the same photo as the frame.

## Migration Plan

None. The behavior changes with the app update. Rollback is the previous release.
