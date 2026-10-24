# Design

## Context

`PlatformInfo.ipAddresses()` is documented as "IPv4 addresses on the LAN that the hint on the frame shows". `AndroidPlatform` implements it by listing every interface that is up and not loopback, in the order `NetworkInterface.getNetworkInterfaces()` gives, and `FrameCore.lanAddress()` takes the first (cached for a few seconds, cleared by `onNetworkChanged()`). The hint, the empty state and the QR code all go through `FrameCore.adminUrl()`. The server itself binds to all addresses, so only what is shown is wrong. The Galaxy Tab S7 has `wlan0` (192.168.100.7) and `rmnet_data0` (10.100.200.100); the mobile data interface came first. The Lenovo and the Frameo have one interface each. See proposal.md for the finding.

## Goals / Non-Goals

**Goals:**
- The shown address is the one a browser on the LAN can reach, on devices with a SIM too.
- The rule is plain JVM code in `:core` that a unit test can run; `:android` only supplies what the system reports.

**Non-Goals:**
- No list of several addresses on the display, and no choice for the user.
- No IPv6, no mDNS name (`openframe.local`), no change to the server binding.
- No change to how the QR code gets its address for a request (it takes the `Host` header).

## Decisions

### 1. The system's own classification, not the interface name

Interface names (`wlan0`, `rmnet_data0`, `ccmni0`, `eth0`) differ by vendor, so a name filter would be a list that never ends. `ConnectivityManager.getAllNetworks()` with `NetworkCapabilities.hasTransport()` and `LinkProperties` (interface name and addresses) tells what kind of network each interface belongs to, and all of it exists on API 21. `ACCESS_NETWORK_STATE` is already in the manifest. `getAllNetworks()` is deprecated from API 31 but still works; the call is wrapped with `@Suppress("DEPRECATION")` and a comment, because the replacement (`NetworkCallback`) is asynchronous and the address is read synchronously.

*Alternative:* ask for the default network only (`getActiveNetwork`). Rejected: with Wi-Fi and mobile data on, the default network can be either, and on API 21 and 22 it is not available per network.

### 2. A small candidate model in `:core`

`:android` builds a list of `AddressCandidate(address, kind)` with `kind` one of `WIFI`, `ETHERNET`, `OTHER`, `CELLULAR`, `VPN`. A function in `:core` (next to `PlatformInfo`) turns the list into the ordered addresses: Wi-Fi and Ethernet first (keeping the order given within a kind), then `OTHER`, with `CELLULAR` and `VPN` dropped. `ipAddresses()` returns that result, so `FrameCore.lanAddress()` does not change.

*Alternative:* let `AndroidPlatform` sort and filter. Rejected: untestable on the JVM, and CLAUDE.md wants platform-dependent things behind an interface and logic in `:core`.

### 3. Interfaces the system does not report as a network are `OTHER`

A Wi-Fi hotspot (`ap0`, `wlan1`) or USB tethering (`rndis0`) is not a `Network` of the device, but the frame itself is the gateway there and its address is the right one. After the networks have been classified, every other interface that is up and not loopback becomes an `OTHER` candidate, unless its name belongs to a network that was classified as cellular or VPN.

*Alternative:* only the networks the system reports. Rejected: a frame on a hotspot would show "no network".

### 4. Mobile data only shows "no network"

The address of a mobile data connection is not reachable from any LAN, so the list stays empty, and the existing "No network" text in the hint and the empty state appears (`hint_no_network`, `empty_no_network`). The user then sees at once that the frame has no Wi-Fi.

*Alternative:* keep it as the last candidate. Rejected: it is the same wrong address the finding is about, only in fewer cases.

### 5. Change of network

`onNetworkChanged()` already clears the cache when the system reports an available network; it fires for Wi-Fi coming back, so the scenario "Wi-Fi returns" needs no new code. The lookup stays at most a few seconds old.

## Risks / Trade-offs

- [A vendor that reports Wi-Fi tethering or a Wi-Fi network with the wrong transport] → the interface falls into `OTHER` or `WIFI` and is still shown; at worst the order differs from a better one.
- [`getAllNetworks()` throws or returns nothing on a device] → the lookup falls back to the old interface list minus loopback, so the result is never worse than today.
- [A device with Wi-Fi and Ethernet both up] → both are shown candidates, the first in the order of the system wins, as today.
- [Mobile data only] → "no network" instead of an address; intended (decision 4).

## Open Questions

None.
