# Tasks

## 1. Contract (first, `api/openapi.yaml` before the code)

- [ ] 1.1 Add `GET /places` with `q` (required, string) and `limit` (integer 1 to 20, default 10), security `bearer`,
  responses 200 `PlaceList`, 400 `BadRequest`, 401; schemas `PlaceList` and `PlaceResult` (design decision 4). Verify:
  `npm run lint` in `webadmin/` (Redocly) is green.
- [ ] 1.2 Run `npm run gen:api` in `webadmin/`. Verify: `npm run typecheck` is green, and the diff of
  `src/lib/api/schema.d.ts` holds only the new path and schemas.

## 2. Place data v2

- [ ] 2.1 Update `scripts/build-places.py` to format v2 (design decision 1): a third argument `admin1CodesASCII.txt`,
  regions and population class in the base, region names in the packs, `searchOrder` in base and packs, the fold rule
  of design decision 2 with its fold-case table checked at the end of the run, and the header comment and
  `SOURCE.txt` text describing v2. Verify: two runs on the same dumps give byte-identical files, and the script prints
  place count, region count and file sizes.
- [ ] 2.2 Download the current `cities500.zip`, `alternateNamesV2.zip` and `admin1CodesASCII.txt` from
  `https://download.geonames.org/export/dump/` into a folder outside every repository, and rebuild
  `core/src/main/resources/places/`. Verify: `SOURCE.txt` and `webadmin/public/licenses/GeoNames.txt` are equal and
  name the three dump dates; the compressed base stays below 4.5 MB (else narrow the order to `u24`, design Risks).

## 3. Reader and search in `:core`

- [ ] 3.1 `PlaceIndex` reads v2 and only v2: regions, population classes, search orders, pack region names; v1 or a
  wrong dataset ID logs a warning and gives no places, as a missing file does today. Verify: the existing
  `PlaceIndexTest` cases for `nearest` and `placeName` pass on the rebuilt data (Siena, Milan/Mailand, beyond 50 km),
  and a v1 test file gives `null` with the warning.
- [ ] 3.2 Name folding in Kotlin (design decision 2) with the fold-case table as a unit test, and a test that walks the
  shipped base and German pack orders once and asserts they are sorted under the Kotlin fold. Verify: both tests pass
  and stay within `./gradlew :core:check`'s API 21 check (`java.text.Normalizer` only).
- [ ] 3.3 `PlaceIndex.search(query, language, limit)` with prefix walk, bounded best-`limit` heap, ranking and dedup
  (design decision 3). Verify: `PlaceIndexTest` on the shipped data covers `place-search` › Search by name (› Start of
  a name, › Without accents, › Only the start counts), › Names in the frame's language (all four scenarios),
  › Content of a result › Place with region, › Order of the results (all three), › Number of results (› Default
  number, › Nothing found); a JVM timing test of 100 queries over all two-letter prefixes stays under 20 ms per query
  after loading.

## 4. Route

- [ ] 4.1 Route `GET /places` with query validation, the frame's language from the settings, and the DTOs of 1.1.
  Verify: an API test with `TestHttp(..., checkContract = true)` covers `place-search` › Invalid searches (› Query too
  short, › Limit too large, › Without token), a valid search in German and in English, and a search on a frame
  without network (`place-search` › Search by name › Frame without network, by the fake platform reporting no
  network); `RouteAccessTest` is green with the new path.
- [ ] 4.2 README: the "Privacy" section names the place search next to the place names as offline, and the API
  overview (if it lists routes) gets `/places`. Verify: the text reads correctly in the diff.

## 5. Checks

- [ ] 5.1 Run `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck` and `npm test`. Verify: all are
  green.
- [ ] 5.2 Report the APK size before and after (`android/build/outputs/apk/release/`). Verify: the growth is stated in
  the pull request text and matches the estimate of the proposal.

## 6. Devices (each persistent device change only with the owner's consent at that moment)

- [ ] 6.1 Add rows to the device checklist in `../openframe-private/DEVICE_TEST.md`:
  - Lenovo: the first `GET /places?q=ka` after the start (load time from the log), then 20 searches of growing
    prefixes; each answers within 300 ms (`place-search` › Search speed › Typing in the web UI);
  - Lenovo and Frameo: a photo with a known position keeps its place name after the update
    (`photo-library` › Place name from the position);
  - Lenovo: `dumpsys meminfo` of openframe before and after the first search.

  Verify: the rows exist with their references.
- [ ] 6.2 Run the rows of 6.1 after the release, updating through the updater with the owner's consent. Verify: results
  are entered, and anything that differs gets a finding in the private findings list.

## Workflow follow-up

- `sun-dimming` builds on this change; propose it so that it can be implemented once this one is merged.
- Archive the change after the device rows of 6.2 are done.
