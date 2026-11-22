## MODIFIED Requirements

### Requirement: Asking for the network while the display is off
When the network is lost while the display is off by the schedule or the manual switch, the frame SHALL ask the system once a minute to reconnect to the Wi-Fi, without switching the display on, until the network is back or the display goes on. Only a Wi-Fi or Ethernet connection SHALL count as the network, here and for the dark wake; mobile data, a VPN or any other kind of connection SHALL NOT.

#### Scenario: Router drops the frame at night
- **WHEN** the display is off by the schedule and the access point drops the frame's Wi-Fi
- **THEN** the frame asks for a reconnect within a minute and again every minute, and the display stays dark

#### Scenario: Network back
- **WHEN** the network comes back after a reconnect request
- **THEN** the frame asks for nothing more and the admin interface answers again

#### Scenario: Mobile data stays on
- **WHEN** the display is off by the schedule and the frame's Wi-Fi drops while its mobile data stays connected
- **THEN** the frame asks for a reconnect within a minute, and ten minutes after the loss it starts a dark wake

#### Scenario: Mobile data only
- **WHEN** the device has mobile data but no Wi-Fi or Ethernet connection and the display is off by the schedule
- **THEN** the frame treats the network as missing, as on a device without any network: it asks for a reconnect every minute and starts a dark wake every ten minutes
