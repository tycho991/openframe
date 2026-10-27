# Inter for the web UI

`InterVariable.woff2` is a subset of Inter 4.1 (SIL Open Font License 1.1, see `public/licenses/Inter-OFL.txt`),
made once with fonttools and committed, so the build needs no Python. It keeps the weights 300 to 700 and the
optical size axis; it covers Basic Latin, Latin-1, Latin Extended-A, general punctuation, €, the four arrows, the
minus sign and the check mark, with the layout features `kern`, `liga`, `calt`, `tnum` and `case` (see
`openspec/changes/design-system/design.md`, decision 4). The frame's static weights are made the same way, see
`android/src/main/assets/fonts/README.md`.

To make it again, from the repository root:

```sh
W=$(mktemp -d)
python3 -m venv "$W/venv"
"$W/venv/bin/pip" install -q fonttools==4.65.0 brotli==1.2.1
curl -sSLo "$W/Inter-4.1.zip" https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
echo "9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e  $W/Inter-4.1.zip" | sha256sum -c
unzip -q "$W/Inter-4.1.zip" -d "$W/Inter-4.1"

"$W/venv/bin/fonttools" varLib.instancer -q --no-recalc-timestamp "$W/Inter-4.1/InterVariable.ttf" wght=300:700 \
  -o "$W/InterVariable-300-700.ttf"
"$W/venv/bin/pyftsubset" "$W/InterVariable-300-700.ttf" \
  --unicodes="U+0020-007E,U+00A0-017F,U+2000-206F,U+20AC,U+2190-2193,U+2212,U+2713" \
  --layout-features="kern,liga,calt,tnum,case" \
  --flavor=woff2 --output-file=webadmin/src/assets/fonts/InterVariable.woff2
```

The result is byte-identical to the committed file.
