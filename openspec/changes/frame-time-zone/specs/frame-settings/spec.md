## ADDED Requirements

### Requirement: Time zone of the frame
The state that paired clients read SHALL contain the device's current time zone as an ID browsers accept: an IANA ID such as `Europe/Berlin` as it is, a zone with a fixed offset as `UTC` or as an offset such as `+01:00`. A change of the device's zone SHALL show in the next state, without a restart. A zone the frame cannot read SHALL be reported as `UTC`. The info that needs no token SHALL NOT contain the zone.

#### Scenario: Zone of the frame
- **WHEN** the device's time zone is `Europe/Berlin` and a paired client reads the state
- **THEN** the state contains the time zone `Europe/Berlin`

#### Scenario: Zone changed on the device
- **WHEN** the device's time zone is changed from `Europe/Berlin` to `America/New_York` while the frame runs
- **THEN** the next state contains `America/New_York`, without a restart of the app

#### Scenario: Custom offset
- **WHEN** the device's time zone is the custom ID `GMT+01:00`
- **THEN** the state contains the time zone `+01:00`

#### Scenario: Plain UTC
- **WHEN** the device's time zone is `GMT`, `UTC` or a zero offset
- **THEN** the state contains the time zone `UTC`

#### Scenario: Zone the frame cannot read
- **WHEN** the device's time zone ID is unknown to the time zone data the frame uses
- **THEN** the state is answered as usual and contains the time zone `UTC`

#### Scenario: Info without a token
- **WHEN** a client without a token fetches the frame's info
- **THEN** the info contains no time zone
