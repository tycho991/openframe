# Tasks

## 1. Selection rule in `:core`

- [x] 1.1 Add `AddressCandidate` (address, kind: `WIFI`, `ETHERNET`, `OTHER`, `CELLULAR`, `VPN`) and the function that orders candidates (Wi-Fi and Ethernet first in the given order, then `OTHER`, `CELLULAR` and `VPN` dropped) in `core/.../platform/`; verify: `./gradlew :core:compileKotlin` passes and `checkNoAndroidImports` stays green
- [x] 1.2 Unit tests for the rule: the Galaxy Tab S7 list (`rmnet_data0` first, `wlan0` second gives only `192.168.100.7`), Wi-Fi only (Frameo, Lenovo), mobile data only (empty list), Wi-Fi plus Ethernet (order kept), a hotspot interface as `OTHER` after Wi-Fi, a VPN address dropped; verify: `./gradlew :core:test` passes and the Tab S7 case fails without the rule
- [x] 1.3 Update the contract text of `PlatformInfo.ipAddresses()` (reachable from the LAN, no mobile data, ordered best first); verify: KDoc reads the same as the spec requirement

## 2. Android implementation

- [x] 2.1 `AndroidPlatform.ipAddresses()`: build the candidates from `ConnectivityManager.getAllNetworks()`, `NetworkCapabilities` and `LinkProperties` (API 21), add the remaining up interfaces from `NetworkInterface` as `OTHER` unless they belong to a cellular or VPN network, fall back to the old interface list if the system call fails, and pass the list to the rule from 1.1; verify: `./gradlew :android:assembleDebug :android:lintDebug` passes and `./gradlew :core:check` (Animal Sniffer, API 21) passes
- [x] 2.2 `getAllNetworks()` is deprecated from API 31: suppress it at that one call with a comment that says why (synchronous read, `NetworkCallback` is asynchronous); verify: no new lint warning in the report

## 3. Device check

- [x] 3.1 Galaxy Tab S7 (Wi-Fi plus SIM): install the build, tap the frame and read the hint and the empty state with `uiautomator dump`; verify: `Verwaltung: http://192.168.100.7:8080/` and the address opens the admin interface from the computer; ask before installing
- [x] 3.2 Galaxy Tab S7 with Wi-Fi off (mobile data on): verify: the hint says "Kein Netzwerk", and 10 s after Wi-Fi is switched on again the Wi-Fi address is shown without a restart; ask before switching Wi-Fi off
- [x] 3.3 Lenovo and Frameo: install the build and read the hint; verify: the address is the same as before; ask before installing, and not while a run on the Lenovo is going
- [x] 3.4 Enter the results in `docs/DEVICE_TEST.md` (a row for Wi-Fi plus SIM, a note for Lenovo and Frameo) and mark the finding "Galaxy Tab S7, wrong address on the display" in `docs/FINDINGS.md` as fixed; verify: `git diff docs` shows the row and the changed finding

## 4. Close out

- [x] 4.1 Run `./gradlew check`; verify: it ends green (formatting, tests, API 21 check, Android Lint)
- [x] 4.2 `openspec validate fix-display-address-on-mobile-data`; verify: exit 0 without warnings

## Workflow follow-up

- Archive the change with `/opsx:archive` after the device checks.
