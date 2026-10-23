# Proposal

## Why

On the Frameo the crossfade of two full-screen photos runs at about 28 instead of 56 frames per second: 20 to 24 % of the frames are dropped (`gfxinfo`) and half of the frames are late (SurfaceFlinger), while the Lenovo manages 2 %. The spec asks for at most 5 %. It is the GPU, not the code: the stutter follows the amount of texture data that is drawn per frame, and only textures of a quarter of the pixels at 16 bit (an eighth of the bytes) make the fade smooth, which would make every photo on the Frameo visibly soft for the sake of 1.5 seconds of fade. The measurements are in `docs/FINDINGS.md`, finding 27.

## What Changes

- **The display measures its own transitions** (the frames that arrive late while a fade or slide runs) and, when a device cannot keep up, **changes photos instantly** instead of stuttering. The photos stay sharp.
- The rule: three of the last four transitions had more than a fifth of their frames late. The fallback is kept across restarts and ends when the user changes the transition or its duration, which tries again.
- The setting for the transition is not changed. The API tells the interface (`transitionFallback` in `GET /api/v1/state`), and the interface says next to the setting that this frame shows instant changes and how to try again.
- Devices that manage the transition (the Lenovo) are not affected.

Out of scope: smaller or compressed textures, a transition that is cheaper than the fade (the slide was measured and is not smooth enough either), and a setting to switch the fallback off.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `slideshow-display`: A device that cannot show a transition smoothly changes photos instantly.
- `web-admin`: The interface notes that a frame shows instant changes and how to try again.

## Impact

- `:android` (`SlideRenderer`, a small policy class, the preferences), `:core` (`FrameCore`, the state of the API), `api/openapi.yaml`, `webadmin/` (settings view, texts), `docs/FINDINGS.md`, `docs/DEVICE_TEST.md`.
- Frameo and Lenovo for the check on the devices.
