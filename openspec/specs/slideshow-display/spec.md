# slideshow-display Specification

## Purpose
Shows the library's visible photos on the frame as a smooth fullscreen slideshow, even on old, weak Android hardware.

## Requirements

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
Transitions SHALL run without visible stutter on the reference devices (Frameo `PFF_1024`, Android 6.0.1; Lenovo Yoga Tablet 2 1050F, Android 5.0.1). Decoding the next photo SHALL NOT delay a running transition. If a device cannot show a transition smoothly, the frame SHALL change photos instantly instead and keep them sharp; the chosen transition in the settings SHALL stay as it is.

#### Scenario: Next photo is prepared
- **WHEN** a change is due
- **THEN** the next photo is already decoded and the transition starts without waiting

#### Scenario: Frame rate during a transition
- **WHEN** a crossfade runs on a device whose GPU manages it, such as the Lenovo
- **THEN** at most 5 % of the frames are dropped during the transition (measured with `dumpsys gfxinfo` or equivalent)

#### Scenario: A GPU that cannot fade
- **WHEN** blending two textures of the screen's size takes the display more than half a refresh interval per frame
- **THEN** the frame changes photos instantly from then on, also after a restart, and the setting for the transition is unchanged

#### Scenario: A GPU that can fade
- **WHEN** blending two textures of the screen's size takes at most half a refresh interval per frame
- **THEN** the frame shows the transition as set

#### Scenario: Trying again
- **WHEN** the user changes the transition or its duration while the frame changes photos instantly
- **THEN** the frame shows the transition again and measures it anew

### Requirement: Memory limits
The slideshow SHALL get by with the heap of the reference devices. Photos SHALL be loaded into memory at screen resolution instead of original size, and at most the current and the next photo SHALL be held as bitmaps.

#### Scenario: Large photo
- **WHEN** a photo with 48 megapixels is shown
- **THEN** the app does not crash and the photo appears at screen resolution

#### Scenario: Endurance run
- **WHEN** the slideshow runs for 24 hours with an interval of 5 seconds
- **THEN** memory use does not grow steadily and the app keeps running

### Requirement: Fill mode
The frame SHALL support the fill modes `auto` (crops at most a quarter, default), `fill` (fills the screen, cropped) and `fit` (fully visible, with borders). The fill mode SHALL apply to every photo without a section of its own. The photo's aspect ratio SHALL NOT be distorted in the process, and borders SHALL be black.

#### Scenario: Portrait with fill
- **WHEN** a portrait photo is shown on a landscape display in `fill` mode
- **THEN** the photo fills the width and is cropped centered at the top and bottom

#### Scenario: Portrait with fit
- **WHEN** the same photo is shown in `fit` mode
- **THEN** the whole photo is visible and borders remain on the left and right

#### Scenario: Portrait with auto
- **WHEN** the same photo is shown in `auto` mode
- **THEN** three quarters of its height are visible, cropped centered at the top and bottom, and black borders remain on the left and right

### Requirement: Automatic presentation
In the fill mode `auto`, the frame SHALL show a photo as large as possible while cropping at most 25 % of it in the direction in which it does not fit the screen; the rest of the difference SHALL become black borders. A photo that `fill` crops by at most 25 % SHALL fill the screen as in `fill`. The visible part SHALL be centered on the photo's center.

#### Scenario: Portrait photo on a landscape frame
- **WHEN** a photo of 3000×4000 pixels is shown in `auto` on a screen of 1920×1200
- **THEN** the middle 75 % of its height are visible at a height of 1200 pixels, and black borders of 360 pixels remain on the left and on the right

#### Scenario: Landscape photo close to the screen's format
- **WHEN** a photo of 4000×3000 pixels is shown in `auto` on a screen of 1920×1200
- **THEN** it fills the screen and is cropped centered at the top and bottom, as in `fill`

#### Scenario: Panorama
- **WHEN** a photo of 6000×2000 pixels is shown in `auto` on a screen of 1920×1200
- **THEN** the middle 75 % of its width are visible across the full width, and black borders remain at the top and bottom

### Requirement: Section of a photo
A photo with a section SHALL be shown with that section, whatever the fill mode. A section is a focus point (fractions of width and height) and a zoom. Zoom 1 SHALL fill the screen as `fill` does, a smaller zoom SHALL show more of the photo with black borders, down to the whole photo, and a larger zoom SHALL show a detail. The visible part SHALL be centered on the focus point.

#### Scenario: Head instead of the middle
- **WHEN** a portrait photo on a landscape frame has the section x 0.5, y 0.2, zoom 1
- **THEN** the frame fills the screen with the part around the upper fifth of the photo instead of its middle

#### Scenario: Zoom on a detail
- **WHEN** a photo of 4800×3000 pixels has the section x 0.7, y 0.6, zoom 2 on a screen of 1920×1200
- **THEN** the frame shows a part of half its width and height, centered on the focus point, across the whole screen

#### Scenario: Zoomed out
- **WHEN** a portrait photo on a landscape frame has a zoom below the one at which it is fully visible
- **THEN** the frame shows the whole photo with black borders, as in `fit`

### Requirement: Section at the edge of the photo
If the visible part centered on the focus point would reach past an edge of the photo, the frame SHALL move it inward until it touches that edge, so that no border appears where the photo could fill the screen. In a direction in which the photo is smaller than the screen, the photo SHALL be centered between two black borders.

#### Scenario: Focus point in the corner
- **WHEN** a photo of 4800×3000 pixels has the section x 0, y 0, zoom 2 on a screen of 1920×1200
- **THEN** the frame shows the top left quarter of the photo across the whole screen, without a border

#### Scenario: Borders and focus point
- **WHEN** a portrait photo on a landscape frame has the section x 0.1, y 0.5 and a zoom at which it is narrower than the screen
- **THEN** the photo stands in the middle of the screen with borders of equal width on both sides

### Requirement: Section across screen formats
A section SHALL keep its focus point when the frame's orientation or screen changes. Its zoom SHALL then be limited to the range that the new screen allows, from the whole photo visible to the largest zoom of `photo-library`. The section itself SHALL stay stored unchanged.

#### Scenario: Switch to portrait
- **WHEN** a photo of 4000×3000 pixels has the section x 0.3, y 0.5, zoom 2 on a screen of 1920×1200 and the frame switches from `landscape` to `portrait`
- **THEN** the frame shows it around the same focus point with the largest zoom the portrait screen allows, and after switching back it shows it with zoom 2 again

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
Swiping left or right SHALL switch to the next or previous photo. A tap SHALL show a hint for a few seconds, with pause/resume, the frame's name, the address of the admin interface, a valid pairing code and the matching QR code.

#### Scenario: Swipe back
- **WHEN** the user swipes right
- **THEN** the frame shows the previous photo and the interval starts over

#### Scenario: Pause
- **WHEN** the user pauses
- **THEN** the current photo stays until the user resumes playback

#### Scenario: Hint with QR code
- **WHEN** the user taps the frame
- **THEN** the hint shows a QR code next to name, address and code, which a phone's camera app reads from one meter away

### Requirement: Empty state
If there are no visible photos, the frame SHALL show a hint on how to add photos. It SHALL include the address of the admin interface, and the pairing code and QR code as long as no device is paired yet.

#### Scenario: New frame
- **WHEN** the app starts without photos for the first time
- **THEN** the frame shows the address of the admin interface, the pairing code and the QR code

### Requirement: Apply changes without restart
New, hidden or deleted photos and changed settings SHALL take effect at the next change at the latest, without the app restarting.

#### Scenario: Album hidden
- **WHEN** an album is hidden in the admin interface
- **THEN** from the next change on no photo of this album appears any more

#### Scenario: Current photo deleted
- **WHEN** the photo currently shown is deleted
- **THEN** the frame switches to the next photo immediately

### Requirement: Skip faulty files
If a photo cannot be decoded, the frame SHALL skip it and show the next one. The faulty photo SHALL NOT stop the slideshow. This SHALL also hold for a memory error and for a crash of the decoding process while rendering. Quarantined photos SHALL never be shown.

#### Scenario: Broken file
- **WHEN** the next photo is a damaged JPEG file
- **THEN** the frame shows the photo after it instead and logs the error

#### Scenario: Out of memory while rendering
- **WHEN** rendering the next photo runs out of memory
- **THEN** the frame shows the photo after it instead, and the app keeps running

#### Scenario: Quarantined photo in the order
- **WHEN** the next photo in the order is quarantined
- **THEN** the frame shows the photo after it without trying to render the quarantined one

### Requirement: Display of the pairing QR code
The frame SHALL draw the pairing QR code dark on a light background with a quiet zone, regardless of the photo and theme. While pairing by code is locked, the frame SHALL keep showing the QR code and SHALL name the lockout in place of the code.

#### Scenario: Code locked
- **WHEN** pairing by code is locked after five wrong codes while the hint is visible
- **THEN** the hint shows the QR code and "Code locked" instead of the code

### Requirement: Caption over the photo
The frame SHALL show a caption over the photo with the photo's comment. If switched on in the settings, the comment SHALL be prefixed by the album title, and the place name SHALL follow in a line of its own. A photo with nothing to show SHALL have no caption. The caption SHALL be legible on bright and dark photos alike and SHALL NOT show the capture date.

#### Scenario: Comment only
- **WHEN** a photo with the comment "Grandma's 80th birthday" is shown and album title and place are switched off
- **THEN** the caption shows "Grandma's 80th birthday"

#### Scenario: Comment, album title and place
- **WHEN** a photo in `holiday/2024/Tuscany` with the comment "Grandma's 80th birthday" and a position in Siena is shown, album title and place are switched on, and the frame's language is English
- **THEN** the caption shows "Tuscany · Grandma's 80th birthday" and below it "Siena, Italy"

#### Scenario: Nothing to show
- **WHEN** a photo without comment is shown and album title and place are switched off
- **THEN** no caption is shown

### Requirement: Album title in the caption
The album title SHALL be the last level of the photo's album path, followed by " · " and the comment. A photo without a comment SHALL show the album title alone. A photo without an album SHALL show no album title.

#### Scenario: Album title without comment
- **WHEN** a photo in `holiday/2024/Tuscany` without comment is shown and the album title is switched on
- **THEN** the caption shows "Tuscany"

#### Scenario: Photo without album
- **WHEN** a photo directly in the folder of its source without comment and without position is shown and album title and place are switched on
- **THEN** no caption is shown

### Requirement: Length of the caption
The line with album title and comment SHALL take at most two lines and the place at most one line. Text that does not fit SHALL end with an ellipsis. The caption SHALL take at most 60 % of the screen's width.

#### Scenario: Long comment
- **WHEN** a photo with a comment of 500 characters is shown
- **THEN** the comment takes two lines that end with an ellipsis, and the place line below it stays visible

### Requirement: Corner of the caption
The caption SHALL stand in the corner set for it. Its text SHALL be aligned to the left in the left corners and to the right in the right corners.

#### Scenario: Caption top right
- **WHEN** the caption's corner is set to top right
- **THEN** the caption appears in the top right corner with its lines aligned to the right

### Requirement: Caption and clock in the same corner
If the caption and the switched-on clock share a corner, the clock SHALL stay in the corner and the caption SHALL stand next to it towards the middle of the screen: above the clock in the bottom corners, below it in the top corners. Caption and clock SHALL NOT overlap, in either format and at every clock size.

#### Scenario: Both bottom right
- **WHEN** clock and caption are both set to bottom right
- **THEN** the clock stands in the bottom right corner and the caption directly above it, without overlap

#### Scenario: Large clock in portrait
- **WHEN** the frame runs in `portrait`, the clock is at 300 % and the caption shares its corner with a comment of two lines and a place
- **THEN** clock and caption are fully visible without overlap

#### Scenario: Clock switched off
- **WHEN** the clock is switched off and the caption is set to the clock's corner
- **THEN** the caption stands in the corner itself

### Requirement: Caption and hint
While the hint is shown, the caption SHALL step aside like the clock and SHALL come back when the hint disappears.

#### Scenario: Tap on the frame
- **WHEN** the user taps the frame while a caption is shown
- **THEN** the caption disappears for as long as the hint is shown and appears again afterwards

### Requirement: Caption changes with the photo
The caption SHALL change together with the photo: with a transition it SHALL fade over with the photo, without one it SHALL change at the same moment as the photo. A caption SHALL NOT be shown with a photo it does not belong to.

#### Scenario: Crossfade
- **WHEN** the frame fades from a photo with a caption to one without
- **THEN** the caption fades out during the transition, and at no time is it shown over the new photo alone

### Requirement: Caption changes without a photo change
A changed comment of the photo on screen, changed caption settings and a changed language on the frame SHALL show in the caption within 5 seconds, without the photo changing and without restarting the app.

#### Scenario: Comment edited while the photo is shown
- **WHEN** the user saves a new comment for the photo on screen
- **THEN** the caption shows the new comment within 5 seconds, and the photo is still on screen

#### Scenario: Place switched on
- **WHEN** the place is switched on in the settings while a photo with a position is shown
- **THEN** the caption shows its place within 5 seconds
