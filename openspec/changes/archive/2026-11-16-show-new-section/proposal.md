# Proposal

## Why

A new section of a photo reaches the screen late or not at all. When the photo comes back through "previous" or a
quick step, the frame shows its old part and keeps it for as long as the photo is on screen: `SlideshowController.show`
takes the display size of the old section (`RenderCache.peekAnyDisplay`) and never renders the new one for that
photo; only photos still ahead in the order are rendered in advance. A section saved for the photo on screen, which
is what the Home Assistant card's editor does, also waits for the photo's next turn, which in a shuffled library of
100 photos at a 30-second interval is about an hour away. A device run of `ha-openframe`
`add-home-assistant-photo-management` found this on a Lenovo with openframe 0.7.0.

## What Changes

- When the frame shows a photo with a display size made for an older section, fill mode or orientation, it SHALL
  render the current display size at once and switch to it when it is ready, while the photo is still on screen. The
  older part may stay on screen until then. This covers "previous", quick steps and a photo whose turn comes before
  its new display size is ready.
- A section set or removed for the photo on screen SHALL apply to it at once, as soon as its display size is ready,
  instead of from its next appearance. Today this already holds for a change of the fill mode or the orientation.
- The switch to the new display size SHALL NOT start the photo's interval over.
- The scenarios "Set a section" (`photo-library`) and "Move the section" (`web-admin`) change from "from the next
  change on" to "at once if it is on screen, otherwise when it appears next".

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `slideshow-display`: new requirement "Current part of the photo on screen": a photo on screen that shows an older
  part of itself switches to its current one when that is rendered, including a section changed while it is on
  screen and a photo brought back with "previous".
- `photo-library`: "Section of a photo", scenario "Set a section": the slideshow shows the new section at once if the
  photo is on screen, otherwise when it appears next.
- `web-admin`: "Choose a section", scenario "Move the section": the frame shows the upper part at once if the photo is
  on screen, otherwise when it appears next.

## Impact

- `:core`: `SlideshowController` (`show`, `onLibrary`, a new asynchronous switch to the current display size),
  `DisplayImages` and `CachedDisplayImages` (whether a finished display size is the current one, render with a
  priority), `RenderCache` unchanged apart from what `DisplayImages` needs.
- Tests: `SlideshowControllerTest` with `FakeImages`.
- API unchanged: `GET /slideshow` already reports the photo's current `presentation`; the screen now follows it.
- `ha-openframe`: the card's editor saves the section of the photo on screen; with this change the frame shows it within
  a second or two. Its spec ("when the photo appears next") stays true.
- Device rows in `../openframe-private/DEVICE_TEST.md` (finding 90).
