#!/usr/bin/env python3
# Generates the test images under core/src/test/resources/fixtures (needs Pillow).
# Usage: python3 scripts/generate-test-fixtures.py core/src/test/resources/fixtures
import sys, os
from PIL import Image, ImageDraw
from PIL.TiffImagePlugin import IFDRational
out = sys.argv[1]
def scene(w, h, color):
    im = Image.new("RGB", (w, h), color)
    d = ImageDraw.Draw(im)
    # Marker at the top left so that rotations can be checked
    d.rectangle([0, 0, w // 4, h // 4], fill=(255, 0, 0))
    return im
def dms(value):
    # Degrees, minutes and seconds as EXIF rationals, the seconds to 1/100.
    value = abs(value)
    d = int(value)
    m = int((value - d) * 60)
    s = round(((value - d) * 60 - m) * 60 * 100)
    return (IFDRational(d, 1), IFDRational(m, 1), IFDRational(s, 100))
def exif(orientation=None, taken=None, gps=None):
    e = Image.Exif()
    if orientation is not None:
        e[0x0112] = orientation
    if taken is not None:
        e[0x0132] = taken  # DateTime
        # Sub-IFDs are assigned as a whole: Pillow 9.0 does not write one filled in through get_ifd.
        e[0x8769] = {0x9003: taken}  # DateTimeOriginal
    if gps is not None:
        lat, lon = gps
        e[0x8825] = {1: "N" if lat >= 0 else "S", 2: dms(lat), 3: "E" if lon >= 0 else "W", 4: dms(lon)}
    return e
scene(400, 300, (40, 90, 160)).save(f"{out}/landscape.jpg", quality=85, exif=exif(1, "2024:07:14 12:30:00"))
scene(400, 300, (40, 160, 90)).save(f"{out}/rotated6.jpg", quality=85, exif=exif(6, "2023:01:02 08:00:00"))
scene(400, 300, (160, 90, 40)).save(f"{out}/rotated8.jpg", quality=85, exif=exif(8))
# GPS positions: Siena, Rio de Janeiro (south and west), and 0/0 as cameras without a fix write it.
# landscape.jpg is the JPEG without GPS data.
scene(400, 300, (200, 160, 90)).save(f"{out}/gps-siena.jpg", quality=85, exif=exif(1, "2024:08:03 17:45:00", (43.3188, 11.3308)))
scene(400, 300, (90, 160, 200)).save(f"{out}/gps-rio.jpg", quality=85, exif=exif(1, "2024:02:10 09:15:00", (-22.9068, -43.1729)))
scene(400, 300, (120, 120, 120)).save(f"{out}/gps-zero.jpg", quality=85, exif=exif(1, "2024:05:01 10:00:00", (0.0, 0.0)))
scene(200, 100, (90, 90, 90)).save(f"{out}/image.png")
scene(160, 120, (10, 120, 200)).save(f"{out}/image.webp", quality=80)
f1 = scene(100, 80, (200, 200, 0)); f2 = scene(100, 80, (0, 200, 200))
f1.save(f"{out}/anim.gif", save_all=True, append_images=[f2], duration=200, loop=0)
good = open(f"{out}/landscape.jpg", "rb").read()
open(f"{out}/broken.jpg", "wb").write(good[:300])
# HEIF header without image data: "unsupported" on devices without a decoder
open(f"{out}/photo.heic", "wb").write(bytes.fromhex("0000001866747970686569630000000068656963") + b"mif1" + b"\0" * 200)
open(f"{out}/note.txt", "w").write("not an image\n")
