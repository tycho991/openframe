# Design

## Context

Finding 27 (`docs/FINDINGS.md`): on the Frameo (1280×800 at 56.65 Hz) a crossfade of two full-screen photos runs at about 28 frames per second. The cause was narrowed down on the device: the stutter follows the texture data that is drawn per frame, not the decoder, `BitmapFit`, the sync or `withLayer()`. Only textures with a quarter of the pixels at 16 bit made the fade smooth. The Lenovo manages the same fade with 2 %.

## Decisions

1. **Cut instead of fade, decided by the device itself.** The user chose sharp photos with instant changes over soft photos with a fade. A device list (`PFF_1024` always cuts) would not help the next tablet that behaves like the Frameo, so the display measures.

2. **Measuring the transitions themselves does not work.** The first version counted late frames with a `Choreographer` callback during each transition. On the Frameo it reported 0 to 15 % late frames while `SurfaceFlinger --latency` showed 55 %. The transitions run on the render thread, the UI thread stays idle, and the buffer queue is deep enough (the frames waited 45 to 77 ms to be shown, growing during the 1.5 s) that the render thread's backlog never reaches the UI thread within one transition. On Android 6 nothing inside the app sees the presented frames (`FrameMetrics` is API 24, `gfxinfo` and `SurfaceFlinger` dumps need the DUMP permission).

3. **Measuring what a fade costs.** `GpuProbe` renders blended frames the way the transition does: two RGBA textures of the screen's size mixed into a third, through an EGL pbuffer context, with `glFinish` after each frame. It takes the median of 20 frames. The display runs it three times and keeps the best, so that a transition running at the same moment cannot condemn the device. Measured: Lenovo 5.4 to 6.0 ms at 1920×1128 (33 to 36 % of the 16.7 ms interval), Frameo 13.9 ms at 1280×800 (79 % of 17.65 ms), each run within 1 % of the others. The real fade on the Frameo needs 26 ms per frame, almost twice the probe, because the window, the scaling and the compositor come on top.

4. **Rule.** One blended frame must take at most half a refresh interval (`TransitionGovernor.LIMIT` = 0.5). The two measured devices lie at 0.33 and 0.79, the limit between them leaves room for a third kind. A device that cannot be measured (no EGL, shader fails) keeps its transitions.

5. **`TransitionGovernor` in `:core`.** The rule is pure and needs persistence and the settings, so it lives in `:core` (JVM tests). It keeps `transitions.json` (`key`, `judged`, `cut`), exposes `cut` and `judgementDue` as `StateFlow`s. `DisplayActivity` runs the probe 5 s after the first photo when a judgement is due and calls `judge(frameMs, refreshMs)`; while `cut` is set it hands `Transition.NONE` to the renderer, so `SlideRenderer` has no new mode.

6. **Trying again.** The key is `transition/duration`. When the settings show another key (also after a restart) the verdict is cleared and the display measures anew. Changing the transition to `none` and back, or the duration by 1 ms, is the way to try again; there is no extra button. With the transition `none` or the duration 0 nothing is measured.

7. **The setting stays.** The settings are not rewritten: the user chose "Fade" and the web UI shows "Fade", with a note beside it. `GET /api/v1/state` carries `transitionFallback` next to the versions, because the interface already polls it every 10 s and the value is the frame's condition, not a setting. The note shows for the saved values only; while the field is edited the frame will try again anyway. After a save the interface asks for the state at once.

8. **Not done.** Smaller textures (visibly soft photos for 1.5 s of fade), RGB_565 (banding), a cheaper transition (slide was measured: 2.3 % on the old app, but the same 50 % with full-size photos) and a switch for the user (a setting that nobody can judge). `slide` is measured by the same rule.

## Risks

- The probe is a model: it costs less than the real fade (2× less on the Frameo). A device between the two measured ones could be judged wrongly either way; the limit is a single constant, and the fallback is undone with one field.
- The probe allocates two textures of the screen's size (9 MB each on the Lenovo) for about a second, while the first photo is already up. It runs off the UI thread; if EGL or a shader is not available it reports "not measurable" and the transitions stay.
