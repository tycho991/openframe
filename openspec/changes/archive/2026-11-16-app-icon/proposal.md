# Proposal

## Why

openframe has no icon of its own. The APK declares no `android:icon`, so Android shows its generic default icon in the
app list, in the dialog that picks the HOME app, in "Apps" in the settings and in the package installer's dialog when
an update is installed. The web UI has no favicon, so a browser tab and a bookmark on a phone's home screen show a
generic symbol, and the brand in the web UI's header is a placeholder (an empty rounded square in the accent colour).
An icon makes the app recognisable where users meet it and is a prerequisite for presenting the project publicly.

## What Changes

- One app icon for openframe, drawn for the project and published under its MIT licence: an open frame in the accent
  colour with a warm sun in its open corner, on the dark background of the design tokens, legible from 16 px.
- The frame's APK SHALL carry it as its launcher icon on Android 5.0 and newer, as an adaptive icon from Android 8.0.
- The web UI SHALL use it as favicon, as icon of a bookmark on a phone's home screen, and as the brand mark in its
  header in place of the placeholder.
- The README SHALL show it at the top.
- Out of scope: a web app manifest or installable web app, a splash screen on the frame, and icons outside this
  repository (the Home Assistant integration's brand, the GitHub social preview, which is a repository setting).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `visual-design`: new requirements "App icon" (one icon, own work, colours from the design tokens, legible at small
  sizes), "Launcher icon on the frame" and "Icon in the web UI".

## Impact

- `:android`: `AndroidManifest.xml` (`android:icon`), new resources under `res/` (a vector drawable for Android 5 to 7,
  an adaptive icon in `mipmap-anydpi-v26`), `ds_bg` and `ds_icon_sun` in `design_tokens.xml` (tokens the frame
  does not mirror yet, covered by `checkDesignTokens`), and `--icon-sun` in `tokens.css`.
- `webadmin/`: `index.html` (icon links), `public/` (SVG favicon and a PNG for the home screen), `App.svelte` (brand
  mark), a test that the icon's colours equal the tokens.
- Repository: the icon's source SVG, the README.
- No change to `:core`, the API or the contract.
- Device rows in `../openframe-private/DEVICE_TEST.md`: the icon in "Apps" and in the HOME chooser on the Frameo
  (Android 6) and the Lenovo (Android 5).
