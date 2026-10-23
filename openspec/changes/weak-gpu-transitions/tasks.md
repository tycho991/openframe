# Tasks

## 1. Contract and core
- [x] 1.1 `api/openapi.yaml`: `transitionFallback` in `State`; `StateDto` and the route
- [x] 1.2 `TransitionGovernor` and `StoredTransitionGovernor` in `:core`, wired in `FrameCore` (`transitions`, reset on settings change)
- [x] 1.3 Tests: window rule, limit, short transitions, persistence, trying again, `/state`

## 2. Display
- [x] 2.1 `FrameMonitor` (Choreographer) and `SlideRenderer` reports finished transitions
- [x] 2.2 `DisplayActivity` shows instant changes while the fallback is on

## 3. Web UI
- [x] 3.1 `session.transitionFallback`, `refreshState()`, note in the settings view (en, de)
- [x] 3.2 Playwright test "Note on instant changes"

## 4. Devices
- [ ] 4.1 Frameo: the fallback sets in after a few transitions, then no stutter; a changed duration tries again (`docs/DEVICE_TEST.md`)
- [ ] 4.2 Lenovo: keeps its fades over 20 transitions
- [ ] 4.3 `docs/FINDINGS.md` 27 fixed, the row in `docs/DEVICE_TEST.md` met
