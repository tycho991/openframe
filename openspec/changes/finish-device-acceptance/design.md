# Design

## Context

Both devices run the build of `main` from 22 Oct 2026. On the Lenovo openframe is the HOME launcher and `pictureframe` is stopped but installed; on the Frameo `pictureframe` is uninstalled with its data kept. The device rows were worked through in two runs, one per device, from a computer over the network. What remains is what that setup cannot do: a USB data connection, a phone camera of a certain make, hours without any contact, and a network that is switched off. The way back to `pictureframe` is described in the archived design of `bootstrap-openframe-core` under "Migration Plan".

## Goals / Non-Goals

**Goals:**
- Every open row ends as ticked, or as open with a reason and a finding.
- The devices end up in the state they had before, unless the owner decides otherwise.

**Non-Goals:**
- No code changes, and no new checklist rows.
- No attempt to remove the firmware behavior of the Frameo (it ends the app when another activity starts).

## Decisions

### 1. Rows are grouped by what they need, not by their order in the checklist

| Group | Rows | Needs |
|---|---|---|
| USB and MTP | 73 to 76 | a computer with a USB cable and an MTP client, per device |
| HEIC | 77 | the Frameo and a HEIC file; the pairing of a browser |
| Wi-Fi | 32, 34 | the Lenovo; Wi-Fi off, so no remote access for a while |
| Display awake | 30 | the Lenovo, a change of two system settings for about 12 hours |
| Side by side | 26 | a look at the data of `pictureframe` on the Lenovo, a decision for the Frameo |
| iPhone | 59 | a person with an iPhone, a frame with the current build |
| Way back | 86 to 89 | only if openframe is removed |

### 2. Wi-Fi checks run from a script on the device

With Wi-Fi off there is no SSH. A shell script on the device switches Wi-Fi off, waits, switches it on again and writes what it saw (screenshots of the display, the output of `dumpsys`) to a file in `/data/local/tmp`, which is read afterwards. The script is started before Wi-Fi goes off and re-enables Wi-Fi by itself, also if the run is cut short, so the tablet is never left offline. The cold start is a process start without a network (`am force-stop`, then `am start`), not a reboot: a reboot would need somebody to switch Wi-Fi on by hand, and the scenario is about the process.

*Alternative:* switch Wi-Fi off at the router and watch the display. Rejected as the main way because it cannot be repeated, kept as a fallback for the one-hour check.

### 3. "Hours without touch" needs the system out of the way

The Lenovo has `stay_on_while_plugged_in=7`, so on the charger the display stays on whatever the app does. For the check the setting is set to 0 and the screen timeout to 1 minute for the run, and both are put back afterwards. A logger on the device writes the state of the display every 30 minutes. The Frameo is not used: it has no setting of this kind that is known, and its display was switched off by hand.

*Alternative:* run it unplugged. Rejected, 12 hours is more than the battery should be asked for on an old tablet.

### 4. MTP is tried with a real client

`adb push` writes into the folder but does not go through MTP. The check uses a file manager or an MTP mount on a computer, because what is checked is that files that arrive over MTP are found by the scan within 30 s and that files uploaded through the web UI are visible over MTP after the notification of the media store (`MediaStoreNotifier`).

### 5. Row 26 is closed with what can be known

On the Lenovo both packages are installed and the data of `pictureframe` can be compared with the figures from before the installation (23 files and 91 MB in its Nextcloud cache, 44 thumbnails, an empty `photos` folder). On the Frameo `pictureframe` was ended by the firmware when openframe started, so the scenario "keeps running" cannot hold there; that stays as a documented finding, not as a defect of openframe.

## Risks / Trade-offs

- A script that switches Wi-Fi off can leave the tablet offline if it fails. Mitigation: it re-enables Wi-Fi from a `trap` and from a second watchdog started first; the first run is short.
- The settings changed for the 12 hour run could stay changed. Mitigation: the logger restores them at the end of the run, and the task list has a step that checks them.
- Everything that installs, deletes, restarts or changes a system setting is asked first (`CLAUDE.md`). The Frameo gets no change that is not needed.
- A result of "works" with one phone or one computer is one data point. Each row says with what it was checked.

## Open Questions

- Who has an iPhone, and when? Row 59 waits for that.
- Is a long run on the Lenovo with the display setting changed acceptable, and when?
