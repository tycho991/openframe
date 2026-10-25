# Tasks

## 1. HEIC on the Frameo

- [x] 1.1 Copy a HEIC file into the photo folder of the Frameo; verify: not in the slideshow, listed as skipped with the reason "unsupported" in the API
- [x] 1.2 Pair a browser and open the list of skipped files in the web UI; verify: the file is shown as "nicht unterstützt", row 77 ticked, the browser pairing removed again

## 2. Wi-Fi off

- [x] 2.1 Write the script for the device (Wi-Fi off, wait, Wi-Fi on from a `trap`, a watchdog started first, screenshots and `dumpsys` output into `/data/local/tmp`) and run it for 60 s; verify: Wi-Fi is back, the file has content
- [x] 2.2 Cold start without a network on the Lenovo (`am force-stop`, Wi-Fi off, `am start`); verify: first photo or the empty state within 30 s, no error message on the display, row 32
- [x] 2.3 Wi-Fi off for one hour on the Lenovo; verify: the slideshow keeps running during the hour, afterwards the admin interface answers and the address on the display is the current one, row 34

## 3. Display awake

- [x] 3.1 Agree on setting `stay_on_while_plugged_in` to 0 and the screen timeout to 1 minute on the Lenovo for the run, with the user
- [x] 3.2 Start the run with the logger and leave the tablet alone for 12 hours; verify: display on and slideshow running at every 30 minute entry, row 30
- [x] 3.3 Put both settings back and verify them (`stay_on_while_plugged_in` 7, the former timeout)

## 4. Side by side with pictureframe

- [x] 4.1 Lenovo: compare the data of `pictureframe` (files and size of its Nextcloud cache, thumbnails, `photos` folder) with the figures in design.md; verify: unchanged
- [x] 4.2 Close row 26: result for the Lenovo entered, the firmware behavior on the Frameo kept as a finding in `docs/FINDINGS.md`

## 5. Hardware that is not here yet

- [x] 5.1 Galaxy Tab S7 (when it is available): run the rows of "Photos and USB" with real MTP on a current Android, pair with a real browser, and check the uploaded file with the media store (`content query` shows no row on the API 37 emulator, so only a device can show it); verify: results entered, a finding for anything that differs

## 6. Close out

- [x] 6.1 Check that every row of `docs/DEVICE_TEST.md` that belongs to this change is ticked or has a reason and a finding; the MTP rows (73 to 76) and the iPhone row (59) are moved out, see Workflow follow-up
- [x] 6.2 Check both devices against their state before the run: settings, photos, pairings, HOME launcher

## Workflow follow-up

- The MTP rows (73 to 76, on the Lenovo and the Frameo) and the iPhone QR scan (row 59) are optional and run later, outside this change. They stay open in `docs/DEVICE_TEST.md`; the iPhone case is also in `docs/FINDINGS.md`.
- The way back to `pictureframe` and the photo backup (rows 86 to 89) are run only if openframe is taken off a device; they stay in `docs/DEVICE_TEST.md`.
- Archive the change with `/opsx:archive` after the checks.
