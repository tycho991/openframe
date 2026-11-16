#!/usr/bin/env bash
# Renders the app icon's PNG from its source, webadmin/public/favicon.svg (openspec change app-icon, design.md,
# decision 2). Only the home-screen icon of the web UI is a PNG; the frame's launcher icons are vector drawables that
# checkAppIcon compares with the source. The corners are filled with the tile's colour, because iOS expects an opaque
# square and applies its own mask. Run it after changing favicon.svg and commit the PNG with it.
# Needs Inkscape 1.x.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/webadmin/public/favicon.svg"
out="$root/webadmin/public/apple-touch-icon.png"

inkscape "$src" --export-width=180 --export-height=180 --export-type=png \
  --export-background="#151514" --export-background-opacity=1 --export-filename="$out" 2>/dev/null
echo "wrote $out"
