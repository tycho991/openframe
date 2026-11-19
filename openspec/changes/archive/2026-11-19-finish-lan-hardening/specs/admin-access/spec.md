## ADDED Requirements

### Requirement: Connections only on local addresses
The frame SHALL answer only connections that arrive on loopback or on an address of the device's Wi-Fi, Ethernet, hotspot, tethering or VPN connection, IPv4 or IPv6. It SHALL close a connection that arrives on a mobile data address without an answer. An address that appears after a network change SHALL be accepted without a restart of the app.

#### Scenario: Mobile data
- **GIVEN** the device has a mobile data connection
- **WHEN** a client connects to the frame's port on the mobile data address
- **THEN** the frame closes the connection without an answer, and the slideshow and the admin interface on Wi-Fi keep working

#### Scenario: VPN
- **GIVEN** local-only mode is off and the device is connected to a VPN, for example Tailscale or WireGuard
- **WHEN** a browser opens the frame's VPN address
- **THEN** the admin interface answers as it does on the LAN

#### Scenario: Wi-Fi off and on
- **WHEN** Wi-Fi drops and connects again, possibly with a new address
- **THEN** a browser on the LAN opens the admin interface on the new address without a restart of the app

#### Scenario: From the device itself
- **WHEN** a client on the device connects to `127.0.0.1`, for example through `adb forward`
- **THEN** the frame answers, with local-only mode on or off

#### Scenario: IPv6 on the LAN
- **GIVEN** local-only mode is off
- **WHEN** a client on the LAN connects to an IPv6 address of the frame's Wi-Fi connection
- **THEN** the admin interface answers

### Requirement: Log of device management
The frame SHALL write one info line to its log for each pairing, and for each renaming, revocation and invitation made through the API. The line SHALL name the device that acted by its ID and, where there is one, the device affected by its ID and name. It SHALL NOT contain a token, a pairing secret or an invitation link.

#### Scenario: Revoked by another device
- **WHEN** a paired phone revokes the pairing of a laptop
- **THEN** the log has a line with the phone's ID, the laptop's ID and the laptop's name

#### Scenario: Paired by invitation
- **WHEN** a device pairs with an invitation that a phone created
- **THEN** the log has a line with the new device's ID and name and the phone's ID, and none of the lines about this invitation contains its link or secret

#### Scenario: Renamed
- **WHEN** a paired device renames another device
- **THEN** the log has a line with both IDs and the new name
