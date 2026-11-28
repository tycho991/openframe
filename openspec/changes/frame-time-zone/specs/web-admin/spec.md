## ADDED Requirements

### Requirement: Frame times in the interface
The interface SHALL show a photo's capture time and the display's next switching time in the frame's time zone from the state, followed by the zone's name when the browser is in another zone. If the browser does not know the frame's zone, it SHALL show them in its own zone with that zone's name. Times of events, such as pairing, last contact, deletion, synchronisation and update checks, SHALL stay in the browser's zone.

#### Scenario: Capture time from another zone
- **WHEN** the frame is in `Asia/Tokyo`, the browser in `America/New_York`, and the frame holds a photo's capture time as 21:30 in its zone
- **THEN** the photo detail shows 21:30 followed by the name of the frame's zone

#### Scenario: Next switching time from another zone
- **WHEN** the frame is in `Asia/Tokyo`, the browser in `America/New_York`, and the user switches the display off while the schedule switches it on at 00:00
- **THEN** the interface shows the display off until 00:00 followed by the name of the frame's zone

#### Scenario: Browser in the frame's zone
- **WHEN** the browser is in the same time zone as the frame
- **THEN** capture time and next switching time appear without a zone name

#### Scenario: Zone unknown to the browser
- **WHEN** the frame reports a time zone that the browser does not know
- **THEN** capture time and next switching time appear in the browser's zone, each followed by the name of that zone

#### Scenario: Pairing time from another zone
- **WHEN** the frame is in `Asia/Tokyo` and the user, with the browser in `America/New_York`, paired a device at 09:00 browser time
- **THEN** the list of devices shows the pairing at 09:00, without a zone name
