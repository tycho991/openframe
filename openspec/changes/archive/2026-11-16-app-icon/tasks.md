# Tasks

## 1. The drawing

- [x] 1.1 `webadmin/public/favicon.svg` with the geometry and colours of design decision 1 (motive approved by the owner on 2026-11-16), and the token `--icon-sun` in `tokens.css` per design decision 1a. Verify: rendered at 16, 48 and 160 px it matches the approved draft, and `npm run lint` is green
- [x] 1.2 `scripts/render-icon.sh` (Inkscape, from `favicon.svg`) writes `webadmin/public/apple-touch-icon.png` at 180×180 px; the README's contribution notes name the script. Verify: running it twice gives the same file, and `file` reports 180×180 PNG

## 2. Web UI

- [x] 2.1 Icon links in `webadmin/index.html` and the header's brand mark in `App.svelte` per design decision 4 (24 px `<img>`, `alt=""`). Verify: an e2e test for `visual-design` › Icon in the web UI › Browser tab (the page links `/favicon.svg` and fetching it returns 200 with `image/svg+xml`), › Bookmark on the home screen (`/apple-touch-icon.png` returns 200 with `image/png`) and › Header (the image is visible next to the frame's name in the light and the dark theme), with its own `OPENFRAME_E2E_PORT`
- [x] 2.2 Vitest test per design decision 2: the colours of `favicon.svg` equal `--bg` and `--accent` of the dark theme and `--icon-sun` in `tokens.css`, and `apple-touch-icon.png` is 180×180. Verify: `npm test` is green, and fails when the accent in `favicon.svg` is changed by hand
- [x] 2.3 Run `npm run lint`, `npm run typecheck`, `npm test` and `npm run e2e` in `webadmin/`. Verify: all green

## 3. Frame

- [x] 3.1 `ds_bg` and `ds_icon_sun` in `design_tokens.xml`, the vector drawables and the adaptive icon per design decisions 2 and 3, `android:icon="@mipmap/ic_launcher"` on the application in `AndroidManifest.xml`. Verify: `./gradlew :android:lintDebug` reports no new error or warning, and `aapt2 dump resources` of the debug APK lists `mipmap/ic_launcher` for `anydpi` (the vector, compiled as `-v21`) and `anydpi-v26` (the adaptive icon)
- [x] 3.2 `checkAppIcon` in `android/build.gradle.kts`, run by `check`, per design decision 2. Verify: `./gradlew check` is green, and fails naming the file when the path data of one drawable is changed by hand
- [x] 3.3 On the emulators with the debug build: on API 37 the app info page with the system's round mask; on API 21 the list of Home apps (the same icons as the HOME chooser, without clearing the emulator's default Home app) and the app list. A square mask was not checked: the API 37 emulator has to keep its own launcher disabled (`docs/EMULATOR.md`), and the spec asks only for the round one. Verify: screenshots show the whole open frame with the sun in each (`visual-design` › Launcher icon on the frame › Round mask, › Choice of the HOME app, › Frame on Android 5 before the device run)

## 4. README

- [x] 4.1 The icon above the README's title per design decision 4. Verify: `README.md` renders it on the pull request's "Files changed" view at 96 px

## 5. Devices (each persistent device change only with the owner's consent at that moment)

- [x] 5.1 Rows in `../openframe-private/DEVICE_TEST.md` with their references: the icon in "Apps" and in the package installer's dialog during the update on the Lenovo (`visual-design` › Launcher icon on the frame › Frame on Android 5) and the Frameo, and a home-screen bookmark of the web UI on an Android and an iOS phone (`visual-design` › Icon in the web UI › Bookmark on the home screen). Verify: the rows exist with their references
- [x] 5.2 Run the rows of 5.1 after the release. Verify: results entered; if a frame shows the default icon or a bookmark is blurred, a finding with the fallback of design decision 3 or the manifest named in its risks

## Workflow follow-up

- Archive the change after the device rows of 5.1 are done.
- Optional, by the owner: the GitHub social preview and the Home Assistant integration's brand icon, both outside this repository.
