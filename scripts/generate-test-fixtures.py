#!/usr/bin/env python3
# Erzeugt die Testbilder unter core/src/test/resources/fixtures (braucht Pillow).
# Aufruf: python3 scripts/generate-test-fixtures.py core/src/test/resources/fixtures
import sys, os
from PIL import Image, ImageDraw
out = sys.argv[1]
def scene(w, h, color):
    im = Image.new("RGB", (w, h), color)
    d = ImageDraw.Draw(im)
    # Markierung oben links, damit Drehungen prüfbar sind
    d.rectangle([0, 0, w // 4, h // 4], fill=(255, 0, 0))
    return im
def exif(orientation=None, taken=None):
    e = Image.Exif()
    if orientation is not None:
        e[0x0112] = orientation
    if taken is not None:
        e[0x0132] = taken  # DateTime
        ifd = e.get_ifd(0x8769)
        ifd[0x9003] = taken  # DateTimeOriginal
    return e
scene(400, 300, (40, 90, 160)).save(f"{out}/landscape.jpg", quality=85, exif=exif(1, "2024:07:14 12:30:00"))
scene(400, 300, (40, 160, 90)).save(f"{out}/rotated6.jpg", quality=85, exif=exif(6, "2023:01:02 08:00:00"))
scene(400, 300, (160, 90, 40)).save(f"{out}/rotated8.jpg", quality=85, exif=exif(8))
scene(200, 100, (90, 90, 90)).save(f"{out}/image.png")
scene(160, 120, (10, 120, 200)).save(f"{out}/image.webp", quality=80)
f1 = scene(100, 80, (200, 200, 0)); f2 = scene(100, 80, (0, 200, 200))
f1.save(f"{out}/anim.gif", save_all=True, append_images=[f2], duration=200, loop=0)
good = open(f"{out}/landscape.jpg", "rb").read()
open(f"{out}/broken.jpg", "wb").write(good[:300])
# HEIF-Kopf ohne Bilddaten: auf Geräten ohne Decoder „nicht unterstützt“
open(f"{out}/photo.heic", "wb").write(bytes.fromhex("0000001866747970686569630000000068656963") + b"mif1" + b"\0" * 200)
open(f"{out}/notiz.txt", "w").write("keine Bilddatei\n")
