# Proposal

## Why

On a device with Wi-Fi and a SIM the frame shows the address of the mobile data connection. On the Galaxy Tab S7 (24 Oct 2026) the hint, the empty state and the QR code said `http://10.100.200.100:8080/` (`rmnet_data0`), while the admin interface was reachable only at `http://192.168.100.7:8080/` (`wlan0`). Nobody on the local network can open the shown address, so the user cannot get to the admin interface or pair a phone. Frameo and Lenovo have no SIM, which is why the first runs did not show it. The cause is `AndroidPlatform.ipAddresses()`: it returns every IPv4 address of every interface that is up, and `FrameCore.lanAddress()` takes the first one, which is whatever order the system lists the interfaces in.

## What Changes

- The address the frame shows is one that a browser on the local network can reach: Wi-Fi and Ethernet addresses come first, mobile data (cellular) and VPN addresses are not shown.
- Where the system reports no network of any kind for an interface that is up (a Wi-Fi hotspot or USB tethering, where the frame itself is the gateway), its address is still a candidate, after Wi-Fi and Ethernet.
- With only mobile data the frame shows "no network" instead of an address that cannot be reached.
- The choice is made by a small piece of logic in `:core` that takes a list of candidate addresses with the kind of connection they belong to; `:android` fills the list from `ConnectivityManager` and the interface list. The rule is covered by unit tests with the interface lists of the Galaxy Tab S7 and the two older devices.
- The QR code and the pairing flow are not touched: the QR code of a request already uses the `Host` header of the browser, only the address the frame itself shows changes.
- No API change and no new endpoint.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-access`: the requirement "Reachability on the LAN" says which address is shown (one a browser on the local network can reach, never a mobile data address) and gets scenarios for Wi-Fi plus mobile data and for mobile data only.

## Impact

- `core/.../platform/Platform.kt` (the contract of `ipAddresses()`), a new small selection function in `:core`, `FrameCore.lanAddress()` stays as it is.
- `android/.../AndroidPlatform.kt`: collects the candidates (`ConnectivityManager`, `NetworkCapabilities`, `LinkProperties` and `NetworkInterface`); all of it exists on API 21. `ACCESS_NETWORK_STATE` is already requested.
- `FakePlatform` in the tests keeps its list of addresses; new unit tests for the selection.
- `docs/DEVICE_TEST.md`: a row for the Galaxy Tab S7 (Wi-Fi plus SIM) and a note on the Lenovo and the Frameo; `docs/FINDINGS.md`: the finding "Galaxy Tab S7, wrong address on the display" is marked as fixed.
- Found in `finish-device-acceptance`, task 5.1.
