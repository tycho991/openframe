# Spec Delta

## ADDED Requirements

### Requirement: Licences of bundled components
The APK and the web UI SHALL ship the licence texts and copyright notices of every third-party library they bundle, reachable offline from the frame's web server and linked from the interface's licence menu. The build SHALL fail when a runtime library of the app has no entry in these notices.

#### Scenario: Notices on the web server
- **WHEN** someone requests `/licenses/third-party-android.txt` or `/licenses/third-party-webadmin.txt` from the frame's web server
- **THEN** the frame answers with the licence texts and copyright notices of the libraries of the app or of the web UI

#### Scenario: Notices in the APK
- **WHEN** the APK is unpacked
- **THEN** it contains both notice files next to the licences of the typeface and the icon set

#### Scenario: Linked from the interface
- **WHEN** the user opens the licences in the interface without internet access
- **THEN** the interface links the notices of the app's libraries, of the web UI's libraries and the source note of the place data, and each link opens its text

#### Scenario: Library without a notice
- **WHEN** a runtime library is added to the app without an entry in the notices
- **THEN** the build's check fails and names that library
