## ADDED Requirements

### Requirement: Slideshow state through the API
A paired client SHALL be able to read the photo on screen, with the same data as in the photo list, whether the slideshow is paused, and whether it rests because the display is off. While no photo is shown, because the library is empty or the slideshow is still starting, the photo SHALL be empty.

#### Scenario: Photo on screen
- **WHEN** a client reads the slideshow while a photo is shown
- **THEN** the answer contains that photo with its ID, comment, section and image URLs, and `paused` and `resting` are false

#### Scenario: Empty library
- **WHEN** a client reads the slideshow of a frame without visible photos
- **THEN** the answer contains no photo

#### Scenario: Display off
- **WHEN** a client reads the slideshow while the display is off
- **THEN** `resting` is true and the answer contains the photo that stays on screen

#### Scenario: Without a token
- **WHEN** a client without a valid token reads the slideshow
- **THEN** the frame answers with 401

### Requirement: Operating the slideshow through the API
A paired client SHALL be able to switch to the next or previous photo and to pause and resume the slideshow, with the same effect as the gestures and the hint on the frame. A photo change SHALL start the interval over. The answer SHALL contain the slideshow state after the command has taken effect.

#### Scenario: Next photo from afar
- **WHEN** a client sends `next` while photos are shown
- **THEN** the frame shows the next photo, the interval starts over, and the answer contains the new photo

#### Scenario: Pause from afar
- **WHEN** a client sends `pause`
- **THEN** the current photo stays until the slideshow is resumed, the answer contains `paused` true, and the hint on the frame offers resume

#### Scenario: Paused on the frame, resumed from afar
- **WHEN** the user paused the slideshow on the frame and a client sends `resume`
- **THEN** the photos change after the interval again

#### Scenario: Unknown command
- **WHEN** a client sends the action `shuffle`
- **THEN** the frame answers with 400 naming the field `action` and the slideshow is unchanged

### Requirement: Operating the slideshow while the display is off
While the display is off, the frame SHALL refuse `next` and `previous` with 409 and the error code `display_off`, and SHALL leave the photo unchanged. `pause` and `resume` SHALL take effect and SHALL determine whether the photos change after the display comes on again.

#### Scenario: Next photo at night
- **WHEN** the display is off by the schedule and a client sends `next`
- **THEN** the frame answers with 409 and `display_off`, the photo stays while the display is off, and when the display comes on the slideshow moves on by one photo, as it does without the command

#### Scenario: Pause at night
- **WHEN** the display is off, a client sends `pause` and the display comes on
- **THEN** the paused photo stays on screen

### Requirement: Slideshow API in safe mode
While the frame is in safe mode, reading and operating the slideshow SHALL answer with 503 and the error code `safe_mode`, without waiting for the slideshow.

#### Scenario: Next photo in safe mode
- **WHEN** the frame started in safe mode and a client sends `next`
- **THEN** the frame answers with 503 and `safe_mode` at once

### Requirement: Delay of a command from afar
A photo change sent through the API SHALL appear on screen as fast as a swipe, within two seconds on the reference devices when the next photo is already rendered.

#### Scenario: Next photo on the Frameo
- **WHEN** a client sends `next` to the Frameo while photos are shown
- **THEN** the next photo appears within two seconds
