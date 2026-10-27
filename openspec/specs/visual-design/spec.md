# visual-design Specification

## Purpose
Gives the web UI and the frame one design language: shared design tokens, the typeface Inter, one icon set, colour
themes, contrast and motion, all shipped with the app and never loaded from a foreign server.

## Requirements

### Requirement: Shared design tokens
The web UI and the frame SHALL take their colours, accent, corner radii, spacing and type sizes from one set of
design tokens defined in the web UI. Every token value the frame uses SHALL equal the value in the web UI, and the
project's checks SHALL fail when the two differ.

#### Scenario: Token changed on one side only
- **WHEN** the accent colour is changed in the web UI's tokens but not in the frame's resources
- **THEN** `./gradlew check` fails and names the token that differs

#### Scenario: Same accent on both
- **WHEN** the frame shows its pairing code and the web UI shows a primary button
- **THEN** both use the same accent colour

### Requirement: Typeface Inter
The web UI and the frame SHALL show their texts in the typeface Inter, which ships with the web UI and the APK. If
the typeface cannot be loaded, the texts SHALL fall back to the system's sans-serif font and stay readable.

#### Scenario: Web UI without internet
- **WHEN** the web UI is opened on a home network without internet access
- **THEN** its texts appear in Inter and no request leaves the frame

#### Scenario: Frame on Android 5
- **WHEN** the hint bar appears on the Lenovo (Android 5.0.1)
- **THEN** the frame's name, address and pairing code appear in Inter

### Requirement: Tabular figures for changing numbers
Numbers that change while they are visible, such as the time of the clock and the remaining validity of an
invitation, SHALL use figures of equal width, so that the digits do not shift.

#### Scenario: Clock changes the minute
- **WHEN** the clock on the frame changes from 11:59 to 12:00
- **THEN** the colon stays in place

### Requirement: One icon set
The web UI and the frame SHALL use icons from one icon set in one style. A control that shows only an icon SHALL
have an accessible name that says what it does.

#### Scenario: Icon-only button with a screen reader
- **WHEN** a screen reader reaches a button that shows only an icon
- **THEN** it reads the button's action, for example "Close"

### Requirement: Licences of font and icons
The licences of the typeface and of the icon set SHALL ship with them, in the web UI and in the APK.

#### Scenario: Licence in the web UI
- **WHEN** someone requests the licence file of the typeface from the frame's web server
- **THEN** the frame answers with the licence text

#### Scenario: Licence in the APK
- **WHEN** the APK is unpacked
- **THEN** it contains the licence texts next to the font files

### Requirement: Colour themes
The design tokens SHALL define a light and a dark theme. The frame SHALL always use the dark theme, whatever the
system's colour scheme.

#### Scenario: Frame on a device in light mode
- **WHEN** the Galaxy Tab S7 is set to the light system theme
- **THEN** the frame's hint bar and empty state appear in the dark theme

### Requirement: Contrast
In both themes, text SHALL reach a contrast of at least 4.5:1 against its background, and large text, outlines of
controls and the focus indicator at least 3:1. Text that the frame draws directly over a photo SHALL keep a shadow
so that it stays legible on light and dark photos.

#### Scenario: Muted text in the dark theme
- **WHEN** the contrast of the muted text colour is computed against the surface colour of the dark theme
- **THEN** it is at least 4.5:1

#### Scenario: Token pair below the limit
- **WHEN** a token change lowers a text colour's contrast against its background below 4.5:1
- **THEN** the web UI's unit tests fail and name the pair

### Requirement: Motion
Movements of the interface, such as opening a sheet or a panel or switching a view, SHALL last at most 250 ms. When
the system asks for reduced motion, they SHALL happen without movement. The transitions of the slideshow are
settings of their own and not affected.

#### Scenario: Reduced motion
- **WHEN** the browser reports that the user prefers reduced motion and the user opens the photo detail
- **THEN** the detail appears without sliding or fading in
