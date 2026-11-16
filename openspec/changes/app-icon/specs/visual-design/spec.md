## ADDED Requirements

### Requirement: App icon
openframe SHALL have one app icon, used by the frame and the web UI alike. It SHALL be the project's own work under the project's licence, take its colours from the design tokens, and stay recognisable at 16×16 pixels.

#### Scenario: Smallest size
- **WHEN** the icon is shown at 16×16 pixels, as in a browser tab
- **THEN** the open frame and the sun in its open corner are still recognisable as two shapes, in colours of the design tokens

#### Scenario: Licence
- **WHEN** someone reads the licences that ship with the web UI and the APK
- **THEN** the icon needs no notice of its own, because it is covered by the project's licence

### Requirement: Launcher icon on the frame
The frame's app SHALL show the app icon wherever Android shows the app: the app list, the choice of the HOME app, the app settings and the installer's dialog. From Android 8.0 on it SHALL be an adaptive icon that keeps the open frame inside every mask shape.

#### Scenario: Frame on Android 5
- **WHEN** the user opens "Apps" in the settings of the Lenovo Yoga Tablet 2 (Android 5.0.1)
- **THEN** openframe is listed with its app icon, not Android's default icon

#### Scenario: Choice of the HOME app
- **WHEN** the user presses Home on a frame with a second launcher and Android asks which app to use
- **THEN** openframe is offered with its app icon

#### Scenario: Round mask
- **WHEN** a launcher on Android 8.0 or newer masks app icons as circles
- **THEN** the open frame and the sun are shown whole inside the circle on the dark background

### Requirement: Icon in the web UI
The web UI SHALL use the app icon as its favicon, as the icon of a bookmark on a phone's home screen and as the brand mark next to the frame's name in its header. The icon files SHALL be served by the frame, with no request to a foreign server.

#### Scenario: Browser tab
- **WHEN** the user opens the web UI in a browser
- **THEN** its tab shows the app icon

#### Scenario: Bookmark on the home screen
- **WHEN** the user adds the web UI to the home screen of a phone
- **THEN** the bookmark shows the app icon

#### Scenario: Header
- **WHEN** the user opens the web UI in the light or the dark theme
- **THEN** the header shows the app icon next to the frame's name, legible in both themes
