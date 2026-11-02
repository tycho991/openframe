# Spec Delta

## MODIFIED Requirements

### Requirement: Reachability on the LAN
The frame's server SHALL be reachable on the local network on a fixed port (default 8080) as long as local-only mode is off. The address SHALL be readable on the frame. The address shown SHALL be one that a browser on the local network can reach; an address of a mobile data connection SHALL NOT be shown.

#### Scenario: Show address
- **WHEN** the user taps the frame
- **THEN** the hint shows the address of the admin interface, e.g. `http://192.168.100.215:8080/`

#### Scenario: Wi-Fi and mobile data
- **GIVEN** the device is connected to Wi-Fi and also has a mobile data connection
- **WHEN** the frame shows the address of the admin interface
- **THEN** it is the Wi-Fi address and a browser on the same network opens the admin interface with it

#### Scenario: Mobile data only
- **GIVEN** the device has a mobile data connection but no Wi-Fi and no Ethernet
- **WHEN** the frame shows the hint or the empty state
- **THEN** it says that there is no network instead of an address

#### Scenario: Wi-Fi returns
- **GIVEN** the device was on mobile data only
- **WHEN** Wi-Fi connects
- **THEN** the frame shows the Wi-Fi address without a restart

### Requirement: Invitation address
The invitation link SHALL use the address through which the inviting device reaches the frame, so that both devices use the same origin. If that address cannot be determined, the link SHALL use the address the frame shows.

#### Scenario: Same origin
- **WHEN** a device that reaches the frame at `http://192.168.100.215:8080/` creates an invitation
- **THEN** the invitation link starts with `http://192.168.100.215:8080/`
