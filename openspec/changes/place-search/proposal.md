# Proposal

## Why

The planned change `sun-dimming` dims the display by the sun's elevation and needs the frame's position. The frame
has no GPS, and the web UI cannot ask the browser for one: it is served over plain `http` on the LAN, where browsers
refuse `navigator.geolocation`. Typing coordinates is not something users should have to do. The owner chose to pick
the place by a search. An online geocoder would break the README's promise that place names are looked up offline and
that the web UI loads nothing from outside the frame, and the web UI's CSP (`connect-src 'self'`) would block it anyway.
The frame already ships the GeoNames places of `cities500` (236,764 places) for the place names of photos, but only
for the way from a position to a name, and without the region or the size of a place, so equal names cannot be told
apart or ranked (twelve names occur more than five times in Germany alone).

## What Changes

- **Place data v2.** `scripts/build-places.py` keeps, per place, its first-level region (GeoNames `admin1`, e.g.
  Hesse) and a coarse population class. It writes a search order of the names, so that a search is a binary search
  without sorting on the frame. Name packs carry the region names of their language and a search order of their own
  names. The data is rebuilt from current GeoNames dumps and committed, with the licence text updated.
- **Place search in `:core`.** `PlaceIndex` finds places whose name starts with the query, ignoring case, accents and
  punctuation. It searches GeoNames' own names and the names of the frame's language. Results are ordered by exact
  match first, then larger places first.
- **`GET /places?q=…`** (token required) answers up to 10 places, at most 20 with `limit`: name, region and country in
  the frame's language, country code, latitude and longitude. A query of fewer than 2 or more than 100 characters gives
  400.
- The reverse lookup for photos (`photo-library` › Place name from the position) keeps its behaviour on the new format.
- No web UI yet: `sun-dimming` adds the search field where the place is chosen. The generated API types of the web UI
  are regenerated.

## Capabilities

### New Capabilities

- `place-search`: finding places of the shipped place data by name through the API, offline, with the result's
  names in the frame's language.

### Modified Capabilities

None. The place names of photos do not change.

## Impact

- `scripts/build-places.py`, `core/src/main/resources/places/` (base.bin, names-de.bin, SOURCE.txt) and
  `webadmin/public/licenses/GeoNames.txt`. The data grows by an estimated 1 to 1.5 MB compressed, and the APK grows
  by the same amount.
- `core/.../places/PlaceIndex.kt` (reader for v2, search, name folding), a new route next to the library routes, DTOs.
- `api/openapi.yaml`: path `/places`, schemas `PlaceList` and `PlaceResult`. Then `npm run gen:api` in `webadmin/`.
- Tests: `PlaceIndexTest` on the shipped data, contract tests with `checkContract = true`, `RouteAccessTest`.
- Heap: the search loads the same base as the reverse lookup (about 5 MB today) plus about 1 MB for the search order.
  The base loads lazily, on the first search or the first place name.
- No new dependency, no network access.
