# Tasks

## 1. USB and MTP

- [ ] 1.1 Lenovo: copy photos with an MTP client into `Android/data/io.github.tycho991.openframe/files/photos/holiday/`; verify: in the index and in the slideshow after 30 s at most, time entered in row 73
- [ ] 1.2 Lenovo: delete one of them over MTP; verify: gone from the index after 30 s at most, row 74
- [ ] 1.3 Lenovo: upload a photo through the web UI, connect USB and look at the folder on the computer; verify: the file is visible (reconnect once if necessary), row 75
- [ ] 1.4 Lenovo: delete a photo in the web UI; verify: no longer visible over USB, row 76
- [ ] 1.5 Frameo: repeat 1.1 to 1.4 with a real MTP client (so far only `adb push` and `adb shell rm` were used); verify: times entered in rows 73 and 74, result of 75 and 76 noted

## 2. HEIC on the Frameo

- [ ] 2.1 Copy a HEIC file into the photo folder of the Frameo; verify: not in the slideshow, listed as skipped with the reason "unsupported" in the API
- [ ] 2.2 Pair a browser and open the list of skipped files in the web UI; verify: the file is shown as "nicht unterstützt", row 77 ticked, the browser pairing removed again

## 3. Wi-Fi off

- [ ] 3.1 Write the script for the device (Wi-Fi off, wait, Wi-Fi on from a `trap`, a watchdog started first, screenshots and `dumpsys` output into `/data/local/tmp`) and run it for 60 s; verify: Wi-Fi is back, the file has content
- [ ] 3.2 Cold start without a network on the Lenovo (`am force-stop`, Wi-Fi off, `am start`); verify: first photo or the empty state within 30 s, no error message on the display, row 32
- [ ] 3.3 Wi-Fi off for one hour on the Lenovo; verify: the slideshow keeps running during the hour, afterwards the admin interface answers and the address on the display is the current one, row 34

## 4. Display awake

- [ ] 4.1 Agree on setting `stay_on_while_plugged_in` to 0 and the screen timeout to 1 minute on the Lenovo for the run, with the user
- [ ] 4.2 Start the run with the logger and leave the tablet alone for 12 hours; verify: display on and slideshow running at every 30 minute entry, row 30
- [ ] 4.3 Put both settings back and verify them (`stay_on_while_plugged_in` 7, the former timeout)

## 5. Side by side with pictureframe

- [ ] 5.1 Lenovo: compare the data of `pictureframe` (files and size of its Nextcloud cache, thumbnails, `photos` folder) with the figures in design.md; verify: unchanged
- [ ] 5.2 Close row 26: result for the Lenovo entered, the firmware behavior on the Frameo kept as a finding in `docs/FINDINGS.md`

## 6. iPhone

- [ ] 6.1 Scan the QR code of the hint with the camera of an iPhone from 1 m, straight and at an angle; verify: the link opens the admin interface and pairs without input, row 59

## 7. Hardware that is not here yet

- [ ] 7.1 Galaxy Tab S7 (when it is available): run the rows of "Photos and USB" with real MTP on a current Android, pair with a real browser, and check the uploaded file with the media store (`content query` shows no row on the API 37 emulator, so only a device can show it); verify: results entered, a finding for anything that differs

## 8. Close out

- [ ] 8.1 Check that every row of `docs/DEVICE_TEST.md` that belongs to this change is ticked or has a reason and a finding
- [ ] 8.2 Check both devices against their state before the run: settings, photos, pairings, HOME launcher

## Workflow follow-up

- The way back to `pictureframe` and the photo backup (rows 86 to 89) are run only if openframe is taken off a device; they stay in `docs/DEVICE_TEST.md`.
- Archive the change with `/opsx:archive` after the checks.
