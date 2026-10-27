# Spec Delta

## MODIFIED Requirements

### Requirement: Supported browsers
The admin interface SHALL work in the two most recent major versions each of Firefox, Chrome and Safari on desktop and mobile devices. It SHALL be usable from 360 px width without horizontal scrolling, in portrait and in landscape.

#### Scenario: Phone
- **WHEN** the user opens the interface on a phone with 390 px width
- **THEN** all functions are reachable without horizontal scrolling

#### Scenario: Phone in landscape
- **WHEN** the user turns a phone with 390 × 844 px to landscape
- **THEN** all functions are reachable without horizontal scrolling and the navigation does not cover the content

## ADDED Requirements

### Requirement: Navigation by width
Below 600 px width the interface SHALL show its sections in a navigation bar at the bottom, each with icon and
label. From 600 px it SHALL show them in a navigation rail at the left edge, and from 1200 px in a side navigation
with labels. The current section SHALL be marked in every form.

#### Scenario: Phone
- **WHEN** the interface is open at 390 px width
- **THEN** the sections are in a bar at the bottom and the current one is marked

#### Scenario: Tablet in portrait
- **WHEN** the interface is open at 800 px width
- **THEN** the sections are in a rail at the left edge

#### Scenario: Desktop
- **WHEN** the interface is open at 1280 px width
- **THEN** the sections are in a side navigation with their labels

### Requirement: Detail views by width
The photo detail SHALL open as a full-screen layer below 600 px width, as a sheet over the list from 600 px and as
a panel next to the list from 1200 px, where the list stays usable and choosing another photo shows it in the
panel. Closing the detail SHALL return to the same place in the list.

#### Scenario: Detail as a panel
- **WHEN** the user opens a photo at 1280 px width and then chooses another photo in the grid
- **THEN** the panel shows the second photo and the grid stays visible next to it

#### Scenario: Detail on a phone
- **WHEN** the user opens a photo at 390 px width and closes it again
- **THEN** the detail covers the screen while open, and afterwards the grid shows the same photos as before

### Requirement: Layout follows width and orientation
When the width class changes, for example because a tablet is rotated or a window is resized, the interface SHALL
switch its layout without reloading. It SHALL keep the open section, an open detail, the scroll position and
input that has not been saved.

#### Scenario: Rotate a tablet
- **WHEN** the photo detail is open at 1280 × 800 px and the tablet is rotated to 800 × 1280 px
- **THEN** the same photo is shown as a sheet, and closing it shows the grid at the same place

#### Scenario: Unsaved input
- **WHEN** the user has changed the frame's name in the settings without saving and the width class changes
- **THEN** the changed name is still in the field

### Requirement: Touch targets
Every control of the interface SHALL offer a touch target of at least 44 × 44 px, also where the visible control
is smaller.

#### Scenario: Controls on a phone
- **WHEN** the interface is open at 360 px width in any section
- **THEN** no button, link, switch or tile has a touch target smaller than 44 × 44 px

### Requirement: Keyboard operation
Every function of the interface SHALL be reachable with the keyboard. The focus SHALL always be visible, and when a
layer, sheet, panel or dialog closes, the focus SHALL return to the control that opened it.

#### Scenario: Close the photo detail
- **WHEN** the user opens a photo with the keyboard and closes the detail with Escape
- **THEN** the focus is on the tile of that photo

### Requirement: Safe areas
On devices whose screen has a notch, rounded corners or a home indicator, navigation and content SHALL stay inside
the safe area of the screen.

#### Scenario: Phone with a home indicator
- **WHEN** the interface is open on a phone with a home indicator
- **THEN** the labels of the bottom navigation bar are above the home indicator

### Requirement: Theme choice
The interface SHALL follow the system's light or dark colour scheme unless the user chooses light or dark. The
choice SHALL be offered next to the language in one place reachable from every section, apply per browser and be
kept after reloading.

#### Scenario: System in dark mode
- **WHEN** the system uses the dark colour scheme and the user has not chosen a theme
- **THEN** the interface appears in the dark theme

#### Scenario: Choice is kept
- **WHEN** the user chooses the light theme and reloads the page
- **THEN** the interface appears in the light theme, also while the system uses the dark scheme
