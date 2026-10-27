# Inter for the frame

`Inter-Light.ttf` (the clock), `Inter-Regular.ttf`, `Inter-Medium.ttf` and `Inter-SemiBold.ttf` are subsets of the
static weights of Inter 4.1 (SIL Open Font License 1.1, see `../licenses/Inter-OFL.txt`). `FrameType` loads them,
because XML `fontFamily` cannot refer to a bundled font before API 26. They cover the same characters and layout
features as the web UI's `InterVariable.woff2` (see `openspec/changes/design-system/design.md`, decision 4).

To make them again, from the repository root, in the same shell after the first block of
`webadmin/src/assets/fonts/README.md` (it sets `W` to a work directory with fonttools 4.65.0 and the unpacked
release):

```sh
for w in Light Regular Medium SemiBold; do
  "$W/venv/bin/pyftsubset" "$W/Inter-4.1/extras/ttf/Inter-$w.ttf" \
    --unicodes="U+0020-007E,U+00A0-017F,U+2000-206F,U+20AC,U+2190-2193,U+2212,U+2713" \
    --layout-features="kern,liga,calt,tnum,case" \
    --output-file="android/src/main/assets/fonts/Inter-$w.ttf"
done
```

The results are byte-identical to the committed files.
