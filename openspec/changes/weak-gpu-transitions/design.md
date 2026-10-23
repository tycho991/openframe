# Design

## Context

Finding 27 (`docs/FINDINGS.md`): on the Frameo (1280×800 at 56.65 Hz) a crossfade of two full-screen photos runs at about 28 frames per second. The cause was narrowed down on the device: the stutter follows the texture data that is drawn per frame, not the decoder, `BitmapFit`, the sync or `withLayer()`. Only textures with a quarter of the pixels at 16 bit made the fade smooth. The Lenovo manages the same fade with 2 %.

## Decisions

1. **Cut instead of fade, measured on the device itself.** The user chose sharp photos with instant changes over soft photos with a fade. A device list (`PFF_1024` always cuts) would not help the next tablet that behaves like the Frameo, and a Lenovo with a lot of memory pressure would be treated like it. So the display measures.

2. **Measuring.** `FrameMonitor` posts a `Choreographer.FrameCallback` only while a transition runs. A frame is late when it arrives more than 1.5 refresh intervals after the one before (the interval is taken from the display, 60 Hz if it does not say). It reports the number of frames and of late frames when the transition ends as planned; a transition that another one interrupts is not reported. This is the same measure as the analysis of `SurfaceFlinger --latency` in the findings, so the numbers on the device and in the code agree.

3. **Rule.** A transition is late when more than a fifth of its frames are late (the spec allows 5 %; the Frameo measures 50 %, the Lenovo 2 %, so the limit sits far from both). Three of the last four late transitions switch to instant changes. One bad transition (a sync that hogs the CPU, a decoder that runs behind) must not be enough, and a window of four decides after about 4 × the interval of the slideshow. Transitions of fewer than 10 frames say nothing and are ignored.

4. **`TransitionGovernor` in `:core`.** The rule is pure and needs persistence and the settings, so it lives in `:core` (JVM tests) and not in `:android`. It keeps `transitions.json` (`key`, the verdicts, `cut`), exposes `cut` as a `StateFlow` and is fed by the display through `FrameCore.transitions.report(frames, late)`. The display never decides, it only measures and obeys: `DisplayActivity` hands `Transition.NONE` to the renderer while `cut` is set, so `SlideRenderer` has no new mode.

5. **Trying again.** The key is `transition/duration`. When the settings show another key (also after a restart) the verdicts and the fallback are cleared and the next transitions are measured anew. Changing the transition to `none` and back, or the duration by 1 ms, is the way to try again; there is no extra button. Nothing is measured while `cut` is set, so the fallback ends only this way.

6. **The setting stays.** The settings are not rewritten: the user chose "Fade" and the web UI shows "Fade", with a note beside it. `GET /api/v1/state` carries `transitionFallback` next to the versions, because the interface already polls it every 10 s and the value is the frame's condition, not a setting. The note shows for the saved values only; while the field is edited the frame will try again anyway. After a save the interface asks for the state at once.

7. **Not done.** Smaller textures (visibly soft photos for 1.5 s of fade), RGB_565 (banding), a cheaper transition (slide was measured: 2.3 % on the old app, but the same 50 % with full-size photos) and a switch for the user (a setting that nobody can judge). `slide` is measured by the same rule.

## Risks

- A device that is slow only for a while (first start after an update, many downloads) could drop to cuts. Three of four transitions with a fifth of the frames late is a lot, and the user can try again with one field. Logged as a warning (`transitions`) so that it shows in the log.
- Choreographer callbacks cost a few microseconds per frame, only during the 1.5 s of a transition.
