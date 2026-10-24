# Tasks

## 1. Contract and core
- [x] 1.1 `api/openapi.yaml`: `transitionFallback` in `State`; `StateDto` and the route
- [x] 1.2 `TransitionGovernor` and `StoredTransitionGovernor` in `:core`, wired in `FrameCore` (`transitions`, reset on settings change)
- [x] 1.3 Tests: limit, not measurable, no transition, persistence, trying again, `/state`

## 2. Display
- [x] 2.1 `GpuProbe` (EGL pbuffer, two textures blended into a third)
- [x] 2.2 `DisplayActivity` runs the probe when a judgement is due and shows instant changes while the fallback is on

## 3. Web UI
- [x] 3.1 `session.transitionFallback`, `refreshState()`, note in the settings view (en, de)
- [x] 3.2 Playwright test "Note on instant changes"

## 4. Devices
- [x] 4.1 Frameo: the display judges its GPU too slow and changes photos instantly, no stutter (`docs/DEVICE_TEST.md`)
- [x] 4.2 Lenovo: keeps its fades (6 transitions, 2.61 % of the frames)
- [x] 4.3 `docs/FINDINGS.md` 27 fixed, the row in `docs/DEVICE_TEST.md` met
