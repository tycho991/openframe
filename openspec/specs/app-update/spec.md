# app-update Specification

## Purpose
Lets a frame find a newer version of openframe by itself, download and verify it, and install it after someone confirms Android's installation dialog on the frame, started from the device menu or from a paired client.

## Requirements

### Requirement: Source of updates
The frame SHALL look for updates only in the latest published release of the openframe repository on GitHub, without credentials and only over https, also across every redirect. Drafts and pre-releases SHALL NOT count. A release SHALL count as an update only if its version is higher than the installed version and it carries exactly one APK of openframe.

#### Scenario: Newer release
- **WHEN** version 0.3.0 is installed and the latest release is 0.4.0 with its APK
- **THEN** the frame treats 0.4.0 as an update

#### Scenario: Same or older release
- **WHEN** version 0.4.0 is installed and the latest release is 0.4.0 or 0.3.1
- **THEN** the frame reports that it is up to date and downloads nothing

#### Scenario: Release without APK
- **WHEN** the latest release is 0.4.0 but has no APK attached
- **THEN** the frame does not offer it and reports the missing APK as the check's error

#### Scenario: No release found
- **WHEN** GitHub answers the request for the latest release with 404
- **THEN** the check counts as failed with the code `update_not_found`, not as up to date

#### Scenario: Redirect to http
- **WHEN** the download of the APK is redirected to an `http://` address
- **THEN** the frame does not follow it and reports the check as failed

### Requirement: Updates only for official builds
If the installed app is not signed with the official certificate of openframe's releases, the frame SHALL NOT contact GitHub for updates, neither by itself nor on request, and the update state SHALL report the phase `unsupported`.

#### Scenario: Own build
- **WHEN** someone installs a build of openframe signed with their own key
- **THEN** the frame makes no request to GitHub, and the update state reports `unsupported`

### Requirement: Automatic check
While automatic checks are on in the settings, the frame SHALL check for an update not before 3 minutes after the app started and not before 6 hours after the last successful check, and then every 6 hours; retries and safe mode follow their own requirements. It SHALL NOT check by itself while its clock is not set. While automatic checks are off, the frame SHALL NOT contact GitHub by itself.

#### Scenario: Frame running
- **WHEN** automatic checks are on, the last check was a day ago, and the app has been running for 12 hours and 3 minutes
- **THEN** the frame has checked three times

#### Scenario: Restart shortly after a check
- **WHEN** the app restarts one hour after a check
- **THEN** the next automatic check comes 6 hours after that check, not 3 minutes after the restart

#### Scenario: Clock not set
- **WHEN** the frame's clock was never set and shows a date years in the past
- **THEN** the frame makes no automatic check and reports no error

#### Scenario: Checks off
- **WHEN** automatic checks are off
- **THEN** the frame makes no request to GitHub until a client asks for a check

### Requirement: Retry after a failed check
If a check or a download fails, for example without network, on an error answer or on an unexpected answer of GitHub, the frame SHALL report the error in the update state, keep showing the slideshow, and try again after 15 minutes, then after 1 hour, then every 6 hours. When the network returns while a retry waits, the frame SHALL try again within one minute.

#### Scenario: No network
- **WHEN** the automatic check runs while the frame has no network
- **THEN** the update state shows the error, the slideshow keeps running, and the next attempt comes 15 minutes later

#### Scenario: Network back
- **WHEN** a retry waits and the frame gets its network back
- **THEN** the frame checks again within one minute

#### Scenario: Unexpected answer
- **WHEN** GitHub answers with a body that is not a release
- **THEN** the update state reports `update_failed`, and the frame keeps checking on schedule

### Requirement: Check on request
A paired client SHALL be able to ask for a check at any time, also while automatic checks are off. A request while a check or a download is running, or within 60 seconds of the last check, SHALL NOT start another one and SHALL return the current state. After GitHub reported its rate limit, the frame SHALL NOT ask GitHub again before the limit resets.

#### Scenario: Check now
- **WHEN** automatic checks are off and a client asks for a check
- **THEN** the frame checks at once and the update state shows the result

#### Scenario: Two requests in a row
- **WHEN** a client asks for a check twice within 30 seconds
- **THEN** the frame sends one request to GitHub, and the second answer shows the state of the first check

#### Scenario: Rate limited
- **WHEN** GitHub refused a check because of its rate limit, and a client asks again before the limit resets
- **THEN** the frame sends no request and the state keeps reporting `update_rate_limited`

### Requirement: Download of an update
When a check finds an update, the frame SHALL download its APK by itself into storage of its own that is not a cache. It SHALL keep at most one downloaded update, SHALL delete a download whose version is no longer higher than the installed version, and SHALL report `update_storage` instead of downloading when free space is below twice the APK's size plus 100 MiB.

#### Scenario: Update found
- **WHEN** a check finds 0.4.0 while 0.3.0 is installed
- **THEN** the frame downloads the APK and, once it is verified, reports 0.4.0 as ready

#### Scenario: Installed in the meantime
- **WHEN** a download of 0.4.0 is kept and the app starts as version 0.4.0
- **THEN** the frame deletes the download and reports that it is up to date

### Requirement: Kept download checked again
After each start and before each installation, the frame SHALL check that a kept download still exists and still has the checksum of its release. If not, it SHALL NOT offer it and SHALL download it again.

#### Scenario: Download damaged on disk
- **WHEN** 0.4.0 is ready and its file is changed or deleted before someone installs it
- **THEN** the frame no longer reports 0.4.0 as ready and downloads it again

### Requirement: Verification before offering
Before an update counts as ready, the frame SHALL verify the download: the checksum the release states, a ZIP structure with the first entry at the very start and no data after its end, the package ID of openframe, a higher version code than the installed one, no debug build, and the same signing certificate as the installed app. A download that fails a check SHALL be deleted and SHALL NOT be offered.

#### Scenario: Damaged download
- **WHEN** the downloaded file does not match the checksum of the release
- **THEN** the frame deletes it, offers nothing and reports the error

#### Scenario: Different signature
- **WHEN** the APK of the release is signed with another key than the installed app
- **THEN** the frame offers nothing and reports that the signature does not match

#### Scenario: Data before the archive
- **WHEN** the APK has other data in front of its first ZIP entry or behind its end
- **THEN** the frame offers nothing and reports `update_invalid_apk`

#### Scenario: Debug build in a release
- **WHEN** the APK of a release is a debuggable build
- **THEN** the frame offers nothing and reports `update_invalid_apk`

### Requirement: Rejected download is not fetched again
If a download fails the structure, package, version code, debug or signature check, the frame SHALL NOT download the same APK again while the installed version stays the same. A download that failed only the checksum SHALL be tried again at the next check.

#### Scenario: Foreign signature stays rejected
- **WHEN** the APK of 0.4.0 was rejected for its signature and the next check finds the same APK
- **THEN** the frame downloads nothing and keeps reporting the rejection

### Requirement: No dialog without a request
The frame SHALL NOT open the installation dialog by itself. Only choosing the entry in the device menu or a request of a paired client SHALL start it.

#### Scenario: Ready update, nobody asks
- **WHEN** an update has been ready for a day and nobody chose the entry or sent a request
- **THEN** no dialog has appeared and the slideshow kept running

### Requirement: Ready update on the frame
While an update is ready, the hint that a tap on the frame shows SHALL name its version, and the device menu SHALL offer an entry that installs it. Without a ready update the entry SHALL NOT appear.

#### Scenario: Tap on the frame
- **WHEN** 0.4.0 is ready and someone taps the frame
- **THEN** the hint names version 0.4.0, and the device menu offers to install it

### Requirement: Installation dialog
To install, the frame SHALL start Android's installation dialog for the verified download. Confirming it SHALL install the update. Cancelling it SHALL leave the update ready, return to the slideshow and report `install_cancelled`. The result SHALL be reported correctly also when the app restarts while the dialog is open.

#### Scenario: Confirmed
- **WHEN** someone chooses the entry in the device menu and confirms the dialog
- **THEN** Android installs 0.4.0 and openframe runs as version 0.4.0 afterwards

#### Scenario: Cancelled
- **WHEN** someone cancels the dialog
- **THEN** the slideshow returns, 0.4.0 is still ready, and the update state reports `install_cancelled`

#### Scenario: App restarted during the dialog
- **WHEN** the app process ends while the dialog is open and someone cancels the dialog afterwards
- **THEN** the restarted app reports `install_cancelled` and 0.4.0 is still ready

### Requirement: Unanswered dialog
If nobody answers the installation dialog within 10 minutes, the frame SHALL discard the pending installation, bring the slideshow to the front, keep the update ready and report `install_unanswered`. The deadline SHALL survive a restart of the app. A dialog left open from before a restart of the device SHALL be discarded at once.

#### Scenario: Request from the sofa, nobody at the frame
- **WHEN** a client asks for the installation and nobody touches the frame for 10 minutes
- **THEN** the slideshow is shown again, the update is still ready, and the state reports `install_unanswered`

#### Scenario: Device restarted
- **WHEN** the device restarts while the dialog is open
- **THEN** after the start no dialog is pending, the update is still ready, and the state reports `install_unanswered`

#### Scenario: Request after an unanswered dialog
- **WHEN** a dialog was left unanswered and someone asks for the installation again
- **THEN** the dialog of the new request appears, not the discarded one, and confirming it installs the update

### Requirement: Display awake during the installation dialog
While the installation dialog is open, the display SHALL stay on until it is answered or its deadline passes, also in an off period of the schedule and with a short screen timeout of the system. Afterwards the display SHALL follow the schedule again, with the minutes after waking as after the power button.

#### Scenario: Short screen timeout
- **WHEN** the system's screen timeout is 30 seconds and the dialog stays open for 5 minutes
- **THEN** the display stays on with the dialog for the whole 5 minutes

### Requirement: Installation on request of a client
A paired client SHALL be able to ask the frame to start the installation dialog. Without a ready update, without the right to install, or while a dialog is open, the request SHALL be refused with an error code. An accepted request SHALL wake a display that is off as the power button does; a refused one SHALL NOT.

#### Scenario: Install on the frame
- **WHEN** 0.4.0 is ready and a client asks for the installation
- **THEN** the installation dialog appears on the frame

#### Scenario: Nothing ready
- **WHEN** no update is ready, the display is off at night, and a client asks for the installation
- **THEN** the API answers with 409 and `update_not_ready`, and the display stays off

#### Scenario: Display off at night
- **WHEN** the display is off by the schedule and a client asks for the installation of a ready update
- **THEN** the display goes on with the dialog, stays on until the dialog is answered or its deadline passes, and goes off again after the minutes after waking

### Requirement: Pause after a declined dialog
For 30 minutes after a dialog was cancelled or left unanswered, the frame SHALL refuse installation requests of clients with `install_cooldown`. The entry in the device menu SHALL stay available.

#### Scenario: Request right after a cancel
- **WHEN** someone cancelled the dialog on the frame and a client asks for the installation 5 minutes later
- **THEN** the API answers with 409 and `install_cooldown`, and no dialog appears

### Requirement: Installation not allowed
If Android does not allow openframe to install apps, the update state SHALL say so, a request of a client to install SHALL be refused with `install_not_allowed`, and the entry in the device menu SHALL show a notice that names the setting for apps from unknown sources instead of opening system settings.

#### Scenario: Unknown sources off
- **WHEN** an update is ready on a device that does not allow installing apps from unknown sources and someone chooses the entry
- **THEN** a notice names the setting, no system screen opens, and the update state reports that installing is not allowed

### Requirement: Updates in safe mode
The updater SHALL also run while the frame is in safe mode. There, and after an early end of the previous run, the first automatic check of the run SHALL come at once, without the 3 minutes and without waiting for 6 hours after the last check. A ready update SHALL stay ready across restarts, and a client SHALL be able to install it without the slideshow.

#### Scenario: Fix for a crashing release
- **WHEN** the frame is in safe mode after a release crashed, and a release with the fix is published
- **THEN** the frame finds and downloads it, and a client's request opens the installation dialog

### Requirement: Installation is a planned end
Before it starts the installation dialog, the frame SHALL announce a planned end of the app that stays valid beyond the dialog's deadline, so that installing, or the system ending the app while the dialog is open, never counts as a crash. A cancel, a failure or an unanswered dialog SHALL withdraw it.

#### Scenario: App ended while the dialog is open
- **WHEN** the system ends the app while the installation dialog is open, and the app starts again
- **THEN** the previous end is logged as planned and does not count towards safe mode

### Requirement: Update state in the API
A paired client SHALL be able to read the installed version, the latest version found with its release notes, the phase (idle, checking, downloading, ready, installing or unsupported), the time and result of the last check, the last error and whether installing is allowed. Reading the state, asking for a check and asking for the installation SHALL require a token.

#### Scenario: Update ready
- **WHEN** 0.4.0 is ready on a frame running 0.3.0 and a client reads the update state
- **THEN** it learns 0.3.0, 0.4.0 with its notes, that the update is ready and when the last check was

#### Scenario: Without token
- **WHEN** a client without a token reads the update state, asks for a check or for the installation
- **THEN** the API answers with 401

### Requirement: Fixed codes of the update
The error codes of the update state and the refusal codes of an installation request SHALL each come from a fixed list in the API contract. A failed check whose certificate chain is not trusted SHALL report `update_tls`.

#### Scenario: Untrusted certificate
- **WHEN** the answer for a check comes with a certificate chain under an unknown root
- **THEN** the update state reports `update_tls`, and the log names the chain's subjects and issuers
