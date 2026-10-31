#!/usr/bin/env python3
# Builds the offline place data under core/src/main/resources/places from the GeoNames dumps.
# Usage: python3 scripts/build-places.py <cities500.zip> <alternateNamesV2.zip> <target folder>
#
# The dumps come from https://download.geonames.org/export/dump/ (CC BY 4.0). Run this on purpose when the data
# should be refreshed, not on every build; the output is committed. Running it twice on the same dumps gives
# byte-identical files.
#
# Format (read by core/.../places/PlaceIndex.kt). Both .bin files are one gzip stream (no file name, mtime 0);
# inside, all numbers are big-endian.
#
# base.bin, every place of cities500:
#   magic        4 bytes  "OFPB"
#   version      u16      1
#   datasetId    8 bytes  first 8 bytes of the SHA-256 of everything after the header
#   placeCount   i32      N
#   countryCount u16      C (at most 256)
#   countries    C x 2 bytes  ISO 3166 alpha-2 codes, sorted; "\0\0" for places without a country
#   cells        64,801 x i32 start index of each 1°x1° cell; cell = (floor(lat) + 90) * 360 + floor(lon) + 180,
#                         with lat 90 in the row of 89° and lon 180 in the column of 179°; the last entry is N
#   lat          N x i16  latitude inside the cell in 1/10,000°, 0 to 10,000
#   lon          N x i16  longitude inside the cell in 1/10,000°, 0 to 10,000
#   country      N x u8   index into countries
#   nameOffset   N x i32  start of the name in the pool; a name ends where the next one starts
#   poolSize     i32
#   pool         poolSize bytes  UTF-8 names (GeoNames' own `name` field) in place order
# Places are sorted by cell, then by GeoNames ID.
#
# names-<language>.bin, one name pack:
#   magic        4 bytes  "OFPN"
#   version      u16      1
#   datasetId    8 bytes  the datasetId of the base it belongs to
#   language     2 bytes  ISO 639-1 code
#   count        i32      M
#   entries      M x (i32 place index, u16 byte length, UTF-8 name), sorted by place index
# A pack holds a name only where it differs from the base name. Per place it takes the name of that language
# that is marked preferred first, then one that is not a short name, then the lowest alternate name ID; historic
# and colloquial names are skipped.
#
# SOURCE.txt: the dump dates, the dataset ID, the attribution, the licence and the changes made to the data. The
# same text goes to webadmin/public/licenses/GeoNames.txt, which the licence menu of the web UI links.
import gzip, hashlib, io, os, struct, sys, zipfile

LANGUAGES = ["de"]
CELLS = 180 * 360


def dump_date(z, member):
    y, m, d = z.getinfo(member).date_time[:3]
    return f"{y:04d}-{m:02d}-{d:02d}"


def lines(z, member):
    with z.open(member) as f:
        yield from io.TextIOWrapper(f, encoding="utf-8", newline="\n")


def cell_of(lat, lon):
    row = min(int(lat // 1) + 90, 179)
    col = min(int(lon // 1) + 180, 359)
    return row, col


def read_places(path):
    places = []
    with zipfile.ZipFile(path) as z:
        for line in lines(z, "cities500.txt"):
            f = line.rstrip("\n").split("\t")
            gid, name, lat, lon, country = int(f[0]), f[1], float(f[4]), float(f[5]), f[8]
            row, col = cell_of(lat, lon)
            places.append((row * 360 + col, gid, name, lat, lon, row, col, country))
        date = dump_date(z, "cities500.txt")
    places.sort(key=lambda p: (p[0], p[1]))
    return places, date


def build_base(places):
    countries = sorted({p[7] for p in places})
    if len(countries) > 256:
        sys.exit(f"{len(countries)} country codes do not fit into one byte")
    country_index = {c: i for i, c in enumerate(countries)}
    starts, cell = [], 0
    for i, p in enumerate(places):
        while cell <= p[0]:
            starts.append(i)
            cell += 1
    while cell <= CELLS:
        starts.append(len(places))
        cell += 1
    lat = [round((p[3] - (p[5] - 90)) * 10000) for p in places]
    lon = [round((p[4] - (p[6] - 180)) * 10000) for p in places]
    pool, offsets = bytearray(), []
    for p in places:
        offsets.append(len(pool))
        pool += p[2].encode("utf-8")
    n = len(places)
    body = b"".join([
        struct.pack(">iH", n, len(countries)),
        b"".join(c.encode("ascii").ljust(2, b"\0") for c in countries),
        struct.pack(f">{len(starts)}i", *starts),
        struct.pack(f">{n}h", *lat),
        struct.pack(f">{n}h", *lon),
        bytes(country_index[p[7]] for p in places),
        struct.pack(f">{n}i", *offsets),
        struct.pack(">i", len(pool)),
        bytes(pool),
    ])
    dataset_id = hashlib.sha256(body).digest()[:8]
    return b"OFPB" + struct.pack(">H", 1) + dataset_id + body, dataset_id


def read_names(path, places):
    index_of = {p[1]: i for i, p in enumerate(places)}
    best = {lang: {} for lang in LANGUAGES}
    with zipfile.ZipFile(path) as z:
        for line in lines(z, "alternateNamesV2.txt"):
            f = line.rstrip("\n").split("\t")
            if f[2] not in best:
                continue
            i = index_of.get(int(f[1]))
            if i is None or f[6] == "1" or f[7] == "1":  # isColloquial, isHistoric
                continue
            key = (0 if f[4] == "1" else 1, 1 if f[5] == "1" else 0, int(f[0]))
            current = best[f[2]].get(i)
            if current is None or key < current[0]:
                best[f[2]][i] = (key, f[3])
        date = dump_date(z, "alternateNamesV2.txt")
    packs = {}
    for lang, names in best.items():
        packs[lang] = sorted((i, name) for i, (_, name) in names.items() if name != places[i][2])
    return packs, date


def build_pack(lang, dataset_id, names):
    out = [b"OFPN", struct.pack(">H", 1), dataset_id, lang.encode("ascii"), struct.pack(">i", len(names))]
    for i, name in names:
        b = name.encode("utf-8")
        out.append(struct.pack(">iH", i, len(b)) + b)
    return b"".join(out)


def write_gzip(path, data):
    with open(path, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as g:
        g.write(data)


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: build-places.py <cities500.zip> <alternateNamesV2.zip> <target folder>")
    cities, alternates, target = sys.argv[1:]
    places, cities_date = read_places(cities)
    base, dataset_id = build_base(places)
    packs, names_date = read_names(alternates, places)
    os.makedirs(target, exist_ok=True)
    write_gzip(os.path.join(target, "base.bin"), base)
    for lang, names in packs.items():
        write_gzip(os.path.join(target, f"names-{lang}.bin"), build_pack(lang, dataset_id, names))
    source = (
        "Place names: GeoNames (https://www.geonames.org/), licensed under CC BY 4.0\n"
        "(https://creativecommons.org/licenses/by/4.0/).\n"
        "\n"
        f"cities500.txt of {cities_date} and alternateNamesV2.txt of {names_date} from\n"
        "https://download.geonames.org/export/dump/, changed by scripts/build-places.py: converted into\n"
        f"{' and '.join(['base.bin'] + [f'names-{lang}.bin' for lang in packs])}, only the name, position and country of each place kept,\n"
        "positions rounded to 1/10,000 degree, and one name per place chosen for each language pack from the\n"
        "alternate names (preferred names first, historic and colloquial names left out). The names\n"
        "themselves are not changed.\n"
        "\n"
        f"Dataset ID: {dataset_id.hex()}\n"
    )
    licenses = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "webadmin", "public", "licenses")
    for path in (os.path.join(target, "SOURCE.txt"), os.path.join(licenses, "GeoNames.txt")):
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(source)
    print(f"{len(places)} places, " + ", ".join(f"{len(n)} names ({lang})" for lang, n in packs.items()))
    for name in sorted(os.listdir(target)):
        print(f"  {name}: {os.path.getsize(os.path.join(target, name))} bytes")


if __name__ == "__main__":
    main()
