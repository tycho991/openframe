# Design

## Context

See proposal.md for the motivation. What is already in place shapes the approach:

- `scripts/build-places.py` turns `cities500.zip` and `alternateNamesV2.zip` into `base.bin` and `names-de.bin`
  (format v1, described in the script's header) and writes `SOURCE.txt` and `webadmin/public/licenses/GeoNames.txt`.
  The output is committed, and the script is run on purpose, not on every build. Two runs on the same dumps give
  byte-identical files.
- `PlaceIndex` loads the base lazily into flat arrays (about 5 MB of heap, no object per place) and the name packs per
  language on first use. It only answers "nearest place within 50 km". Places are sorted by 1° cell, then by GeoNames
  ID; the place index is an array position and is not stored anywhere outside the data files: photos keep their
  latitude and longitude, and the place name is computed for every answer. A new dataset therefore needs no migration.
- Name packs hold a name only where it differs from GeoNames' own name (`Munich` in the base, `München` in the German
  pack) and are tied to their base by the dataset ID.
- The frame's language is `frame-settings`' `language`. The country is named with `Locale.getDisplayCountry`.
- Reference frames: Lenovo (Android 5.0.1, Atom) and Frameo (Android 6.0.1). Animal Sniffer checks every JDK API
  against API 21; `java.text.Normalizer` is available there.

## Goals / Non-Goals

**Goals:**

- A search that answers in a few milliseconds on the JVM and well within 300 ms on the Lenovo, without sorting or
  building an index on the frame.
- The reverse lookup for photos keeps its results on data of the same dumps.
- One folding rule for names, written once in Python and once in Kotlin, with a test that catches a difference.

**Non-Goals:**

- Matching inside a name (`main` for Frankfurt am Main), spelling mistakes, or German transliteration (`muenchen`).
- Postal codes, streets, or places below 500 inhabitants.
- A web UI for the search: that comes with `sun-dimming`.
- More name packs than German.

## Decisions

### 1. Format v2 instead of a second data file

`base.bin` and the name packs move to version 2. The reader accepts only v2: v1 files exist only in older APKs.

`base.bin` v2 adds, after the v1 arrays:

```
regionCount  u16      R
regions      R x (u16 byte length, UTF-8)   GeoNames' ASCII name of each admin1 region, sorted by "CC.code"
region       N x u16  index into regions, 0xFFFF without a region
population   N x u8   class: 0 for unknown or 0, else min(255, 1 + floor(16 * log10(population)))
searchOrder  N x i32  place indices sorted by folded name, then by population class descending, then by index
```

A name pack v2 adds the region names of its language (`regionNames: R' x (u16 region index, u16 length, UTF-8)`,
only where they differ) and its own `searchOrder` over its entries (sorted by folded name as above).

The script reads a third dump, `admin1CodesASCII.txt`, for region names and their GeoNames IDs. It takes the regions'
names in a language from `alternateNamesV2.txt` with the same rule as for places.

- *Alternative:* a separate `search.bin`. It would duplicate the dataset ID check and the loading, and the base is
  needed for every result anyway.
- *Alternative:* sorting on the frame at the first search. Sorting 236,764 names with a folding comparison takes
  seconds on the Lenovo, so it would need a cache file and its invalidation.

The population class keeps the order "larger first" in one byte. Places of about the same size may swap places, which
does not matter for a search list.

### 2. Folding rule

Both sides fold a name the same way:

1. Unicode NFD, then remove combining marks (category Mn).
2. Map the letters that NFD does not take apart: `ß`→`ss`, `æ`→`ae`, `œ`→`oe`, `ø`→`o`, `ł`→`l`, `đ`→`d`, `ð`→`d`,
   `þ`→`th`, `ı`→`i`.
3. Lowercase with the root locale.
4. Turn every character that is not a letter or digit into a space, collapse runs of spaces, trim.

Compare the folded strings by UTF-16 code units (Kotlin's `String.compareTo`). In Python, sort by
`s.encode("utf-16-be")`, which gives the same order.

`PlaceIndexTest` walks the shipped `searchOrder` of the base and of the German pack once and asserts it is sorted
under the Kotlin fold. A difference between the two implementations fails the build instead of making the binary
search miss places silently. A table of fold cases (`São Paulo`, `Łódź`, `Straße`, `Saint-Étienne`) is tested in
Kotlin and used as a check at the end of the script.

### 3. Search

`PlaceIndex.search(query, language, limit): List<PlaceHit>`:

1. Fold the query.
2. Binary search for the first entry of the base order whose folded name starts with the query, and walk on while
   it matches. Do the same in the order of the frame language's pack, if one exists.
3. Collect matches into one set of place indices. A place found twice counts once.
4. Rank: exact folded match first, then population class descending, then folded name, then index. Take `limit`.
5. Each hit gets its name from the pack or the base, its region from the pack or the base, and its country from
   `Locale.getDisplayCountry` as in the place names of photos.

A short query such as `sa` matches thousands of places. The walk keeps only the best `limit` entries in a small bounded
heap, so it does not build a large list. 7,398 places start with `san`; walking them is a few thousand array reads.

### 4. API

`GET /places?q=<text>&limit=<1..20>`, in the `bearer` security group like the other library routes:

```yaml
PlaceList:   { places: PlaceResult[] }
PlaceResult: { name: string, region: string|null, country: string|null,
               countryCode: string|null, latitude: number, longitude: number }
```

Validation: `q` is trimmed. Fewer than 2 or more than 100 characters, a missing `q`, or a `limit` outside 1 to 20 give
400 `bad_request`, like the photo list's parameters. Coordinates are the data's 1/10,000°.

The route asks `PlaceIndex` on the HTTP worker thread. The first search may load the base (see Risks).

### 5. Rebuilt data and licence text

The data is rebuilt from the GeoNames dumps of the day of the implementation, because older dumps are not offered.
The place names of photos may change where GeoNames changed a place since 2026-10-27; that is the normal effect of
refreshed data. `SOURCE.txt` names the three dumps and says which fields are kept (name, position, country, region,
population class).

## Risks / Trade-offs

- [The first search loads the base, about 1 to 2 s on the Lenovo] → The web UI shows its loading state. The 300 ms of
  the spec apply once the data is loaded. A load at the start of the app would cost heap on frames that never search.
- [Data and APK grow by about 1 to 1.5 MB] → The search order is the largest part and compresses poorly. The script
  prints the sizes. If the base grows past 4.5 MB compressed, the population class and region stay but the order is
  narrowed to `u24`, which fits 236,764 indices.
- [Python and Kotlin fold differently for a rare letter] → The sorted-order test over the shipped data catches it in
  `./gradlew check`.
- [Heap on the Lenovo] → About 1 MB more for the order and less than 1 MB for regions and population. It stays in
  flat arrays.
- [The region in GeoNames is missing or out of date for some places] → It is reported as `null` and the place stays
  findable.

## Migration Plan

Code and data ship in one release, since the reader takes only v2. Rolling back means installing the older APK, which
brings its own v1 data. Nothing is stored that depends on the format.
