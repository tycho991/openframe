# Proposal

## Why

While the display is off by the schedule, a frame that loses its Wi-Fi does not get it back: on the Lenovo the access point dropped the connection and Android made no attempt to reconnect until the display came on 43 minutes later (`docs/FINDINGS.md` 46), although the service already holds a Wi-Fi lock. Worse, a frame that boots without network keeps a wrong clock: the Frameo started in May 2021, followed the schedule for that time and stayed dark and unreachable for about 18 hours (finding 54). In both cases the admin interface, the syncs and the uploads promised by "Reachable while the display is off" are gone.

## What Changes

- **Asking for the network back.** When the network is lost while the display is off by the frame's own decision, the frame asks Android to reconnect and to scan, once a minute, without anything showing.
- **A dark wake as the last resort.** If the network is still missing ten minutes after it was lost, the frame switches the display on for one minute with a black picture at the lowest brightness the app can set, because on both frames the network came back within 7 to 18 s of the display going on. It switches the display off again as soon as the network is back, or after the minute; if the network is still missing, it tries again ten minutes later. The display state in the API keeps reporting the display as off by the schedule during such a wake. A touch during it counts as waking the frame by hand.
- **No schedule with an unset clock.** While the device's clock is evidently not set (earlier than a fixed date that no real clock of a frame running this app can show), the frame decides nothing: the display stays on and the slideshow runs. Once the clock is plausible, the schedule applies as after a change of the schedule, with the minutes of grace if it is night. The display state reports `cause: none` meanwhile.

### Out of scope

- A setting to choose or switch off the dark wake, or its ten-minute and one-minute times.
- Telling the user in the web UI that the clock is not set.
- Setting the clock without network (NTP is Android's job; the frames have no real-time clock of their own that this app could read).
- Network problems while the display is on, which Android handles itself.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `display-schedule`: three added requirements, "Asking for the network while the display is off", "Dark wake for the network" (with its state in the API and a touch during it) and "Schedule only with a set clock"; in "Switching on time" the scenario "Clock corrected after boot" is limited to a clock later than the floor, since an unset clock now gets the grace when it is set.

## Impact

- `:core`: `DisplayController` and `DisplayPolicy` (the clock rule, the dark wake as a state of its own that screen events and the API do not mistake for waking); a new platform interface for the network state and the reconnect request, with a JVM fake; a black screen flag that the display reads as a `StateFlow` from `FrameCore`.
- `:android`: an implementation of the interface with `ConnectivityManager` and `WifiManager` (`reconnect`, `startScan`; permission `CHANGE_WIFI_STATE`, a normal permission); `FrameService` feeds it the network callbacks it already registers; `DisplayActivity` shows black at the lowest window brightness while the flag is set.
- No API or web UI change: the contract stays as it is.
- `docs/DEVICE_TEST.md`: a dropped Wi-Fi at night on the Lenovo and the Frameo, a boot without network on the Frameo.
