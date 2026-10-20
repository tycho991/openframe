# Spec Delta

## Purpose

Shows the library's visible photos on the frame as a smooth fullscreen slideshow, even on old, weak Android hardware.

## ADDED Requirements

### Requirement: Change photos in fullscreen
The frame SHALL show the library's visible photos one after another in fullscreen and switch to the next photo after the configured interval. After the last photo the slideshow SHALL start over from the beginning.

#### Scenario: Change after interval
- **WHEN** the interval is set to 30 seconds and a photo has been shown for 30 seconds
- **THEN** the frame shows the next visible photo

#### Scenario: Wrap-around
- **WHEN** the last photo of the order has been shown and the interval expires
- **THEN** the slideshow starts again with the first photo of the order

### Requirement: Order
The frame SHALL support the orders `shuffle` (default) and `chronological` (by capture date, falling back to file date). With `shuffle`, every visible photo SHALL be shown once per pass before any repeats.

#### Scenario: Shuffle without repeats within a pass
- **WHEN** 10 photos are visible and the order is `shuffle`
- **THEN** in the first 10 changes every photo appears exactly once

#### Scenario: Chronological
- **WHEN** the order is `chronological`
- **THEN** the photos appear in ascending order of capture date

### Requirement: Transitions
The change SHALL happen with the configured transition: `fade` (crossfade, default), `slide` or `none`. The transition duration SHALL be configurable. With a transition duration of 0 the change SHALL happen immediately.

#### Scenario: Crossfade
- **WHEN** the transition `fade` is configured with 1500 ms
- **THEN** the new photo fades in over the old one for 1500 ms, without a black frame in between

### Requirement: Smooth display on weak hardware
Transitions SHALL run without visible stutter on the reference devices (Frameo `PFF_1024`, Android 6.0.1; Lenovo Yoga Tablet 2 1050F, Android 5.0.1). Decoding the next photo SHALL NOT delay a running transition.

#### Scenario: Next photo is prepared
- **WHEN** a change is due
- **THEN** the next photo is already decoded and the transition starts without waiting

#### Scenario: Frame rate during a transition
- **WHEN** a crossfade runs on a reference device
- **THEN** at most 5 % of the frames are dropped during the transition (measured with `dumpsys gfxinfo` or equivalent)

### Requirement: Memory limits
The slideshow SHALL get by with the heap of the reference devices. Photos SHALL be loaded into memory at screen resolution instead of original size, and at most the current and the next photo SHALL be held as bitmaps.

#### Scenario: Large photo
- **WHEN** a photo with 48 megapixels is shown
- **THEN** the app does not crash and the photo appears at screen resolution

#### Scenario: Endurance run
- **WHEN** the slideshow runs for 24 hours with an interval of 5 seconds
- **THEN** memory use does not grow steadily and the app keeps running

### Requirement: Fill mode
The frame SHALL support the fill modes `fill` (fills the screen, cropped, default) and `fit` (fully visible, with borders). The photo's aspect ratio SHALL NOT be distorted in the process.

#### Scenario: Portrait with fill
- **WHEN** a portrait photo is shown on a landscape display in `fill` mode
- **THEN** the photo fills the width and is cropped centered at the top and bottom

#### Scenario: Portrait with fit
- **WHEN** the same photo is shown in `fit` mode
- **THEN** the whole photo is visible and borders remain on the left and right

### Requirement: Orientation of the frame
The frame SHALL run in the configured format, `landscape` (default) or `portrait`. Within the format it MAY follow the orientation sensor, that is, rotate by 180°. A change of format SHALL take effect without restarting the app.

#### Scenario: Switch to portrait
- **WHEN** the orientation is changed from `landscape` to `portrait`
- **THEN** the slideshow keeps running in portrait without a restart, and the photos are rendered again for the new format

### Requirement: Orientation from EXIF
Photos SHALL be shown rotated according to their EXIF orientation.

#### Scenario: Rotated phone photo
- **WHEN** a photo has EXIF orientation 6 (90° clockwise)
- **THEN** it appears upright

### Requirement: Clock overlay
The frame SHALL show a clock with date, which can be switched off, over the photo. Position (four corners), size, 12- or 24-hour format and the date's language SHALL be configurable. The clock SHALL be legible on bright and dark photos alike.

#### Scenario: Minute change
- **WHEN** the clock is on and a new minute begins
- **THEN** the clock shows the new time within one second at the latest

#### Scenario: Clock off
- **WHEN** the clock is switched off in the settings
- **THEN** it disappears without restarting the app

### Requirement: Operation on the frame
Swiping left or right SHALL switch to the next or previous photo. A tap SHALL show a hint for a few seconds, with pause/resume, the frame's name, the address of the admin interface and a valid pairing code.

#### Scenario: Swipe back
- **WHEN** the user swipes right
- **THEN** the frame shows the previous photo and the interval starts over

#### Scenario: Pause
- **WHEN** the user pauses
- **THEN** the current photo stays until the user resumes playback

### Requirement: Empty state
If there are no visible photos, the frame SHALL show a hint on how to add photos. It SHALL include the address of the admin interface and the pairing code as long as no device is paired yet.

#### Scenario: New frame
- **WHEN** the app starts without photos for the first time
- **THEN** the frame shows the address of the admin interface and the pairing code

### Requirement: Apply changes without restart
New, hidden or deleted photos and changed settings SHALL take effect at the next change at the latest, without the app restarting.

#### Scenario: Album hidden
- **WHEN** an album is hidden in the admin interface
- **THEN** from the next change on no photo of this album appears any more

#### Scenario: Current photo deleted
- **WHEN** the photo currently shown is deleted
- **THEN** the frame switches to the next photo immediately

### Requirement: Skip faulty files
If a photo cannot be decoded, the frame SHALL skip it and show the next one. The faulty photo SHALL NOT stop the slideshow.

#### Scenario: Broken file
- **WHEN** the next photo is a damaged JPEG file
- **THEN** the frame shows the photo after it instead and logs the error
