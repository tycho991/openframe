# Proposal

## Why

`bootstrap-openframe-core` and `pair-by-qr` are archived, but rows of `docs/DEVICE_TEST.md` are still open, and tasks 10.1 and 10.2 of the bootstrap change were archived unfinished. What is left cannot be done in a single sitting: it needs a night of waiting or a device that is cut off from the network. Kept as loose rows in the checklist, it blocks nothing but is easily forgotten. A package of its own keeps it visible and lets other work go on.

## What Changes

- The open rows are run on the devices and their results are entered in `docs/DEVICE_TEST.md`: HEIC on the Frameo (77), Wi-Fi off (32, 34), hours without touch (30) and side-by-side operation with `pictureframe` (26).
- USB and MTP (rows 73 to 76) and the iPhone QR scan (59) are optional and run later, outside this change. They need a computer with a USB cable and an MTP client, and an iPhone. They stay open in `DEVICE_TEST.md`.
- The checks that need a device that is not here yet (the Galaxy Tab S7) are listed as a task and wait for it.
- Findings that come out of it go to `docs/FINDINGS.md`. Fixes are separate changes.
- The way back from openframe to `pictureframe` and the photo backup (rows 86 to 89) stay documented. They are run only if openframe is taken off a device.
- No code, no API and no requirement changes. If a check fails, the failing behavior gets its own change.

## Capabilities

### New Capabilities

None. The checks prove scenarios of existing capabilities.

### Modified Capabilities

None. The scenarios are in `kiosk-host`, `slideshow-display`, `photo-library`, `admin-access` and `web-admin` and stay as they are; the change declares `skip_specs`.

## Impact

- `docs/DEVICE_TEST.md` and `docs/FINDINGS.md`.
- The Lenovo Yoga Tablet 2 and the Frameo: some checks change a system setting, switch Wi-Fi off or restart the device, each only after asking (see `CLAUDE.md`).
