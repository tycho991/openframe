# Spec Delta

## Purpose

Finds places of the place data shipped with the frame by their name, offline, so that a client can choose the frame's
place without typing coordinates and without any request leaving the frame.

## ADDED Requirements

### Requirement: Search by name
A paired client SHALL be able to search places by name through `GET /places` with a query `q`. The search SHALL use
the GeoNames places of `cities500` shipped with the frame, and no request SHALL leave the frame for it. A place SHALL
match when its name starts with the query; letter case, accents, punctuation and repeated spaces SHALL NOT matter.

#### Scenario: Start of a name
- **WHEN** a client searches for `frankf`
- **THEN** the results contain Frankfurt am Main and Frankfurt (Oder)

#### Scenario: Without accents
- **WHEN** the frame's language is German and a client searches for `munchen`
- **THEN** the results contain München

#### Scenario: Only the start counts
- **WHEN** a client searches for `main`
- **THEN** Frankfurt am Main is not among the results

#### Scenario: Frame without network
- **WHEN** the frame has no network and a client searches for `kassel`
- **THEN** the search answers with Kassel as for a frame with network

### Requirement: Names in the frame's language
The search SHALL match GeoNames' own name of a place and its name in the frame's language (`frame-settings`), where
the frame ships a name pack for that language. Each result SHALL carry the place's name, its first-level region and
its country in the frame's language, each from the name pack where it has one and GeoNames' own name otherwise.

#### Scenario: German name in a German frame
- **WHEN** the frame's language is German and a client searches for `mailand`
- **THEN** the results contain the place named "Mailand" in the region "Lombardei" and the country "Italien"

#### Scenario: Own name in a German frame
- **WHEN** the frame's language is German and a client searches for `milan`
- **THEN** the results contain the same place, named "Mailand"

#### Scenario: English frame
- **WHEN** the frame's language is English and a client searches for `mailand`
- **THEN** Milan in Italy is not among the results

#### Scenario: Own name with a different name in the pack
- **WHEN** the frame's language is German and a client searches for `munich`
- **THEN** the results contain the place named "München"

### Requirement: Content of a result
Each result SHALL contain the name, the region or `null` without one, the country name or `null` without a country,
the ISO 3166 country code or `null`, and the latitude and longitude of the place in degrees.

#### Scenario: Place with region
- **WHEN** a client searches for `kassel` in an English frame
- **THEN** a result is named "Kassel", with the region "Hesse", the country "Germany", the code `DE`, and a latitude near 51.3 and a longitude near 9.5

### Requirement: Order of the results
Places whose name equals the query, after ignoring case, accents and punctuation, SHALL come first. Within that group
and among the rest, larger places by population SHALL come first. A place SHALL appear at most once, also when both
of its names match.

#### Scenario: Larger place first
- **WHEN** a client searches for `frankfurt`
- **THEN** Frankfurt am Main comes before Frankfurt (Oder)

#### Scenario: Exact name first
- **WHEN** a client searches for `neustadt`
- **THEN** every place named exactly "Neustadt" comes before places such as "Neustadt an der Weinstraße"

#### Scenario: Equal names told apart
- **WHEN** a client searches for `neustadt`
- **THEN** each result named "Neustadt" carries its region and country, and among them the larger place comes first

### Requirement: Number of results
The search SHALL answer at most 10 results, or at most `limit` results for a `limit` of 1 to 20. A query that matches
no place SHALL answer an empty list.

#### Scenario: Default number
- **WHEN** a client searches for `san` without `limit`
- **THEN** the answer holds 10 results

#### Scenario: Nothing found
- **WHEN** a client searches for `xqzzy`
- **THEN** the answer is an empty list with status 200

### Requirement: Invalid searches
A query that has fewer than 2 or more than 100 characters after removing surrounding whitespace, a missing query, or
a `limit` outside 1 to 20 SHALL be rejected with 400 `bad_request`. A request without a valid token SHALL be rejected
with 401.

#### Scenario: Query too short
- **WHEN** a client searches for ` k `
- **THEN** the API answers with 400 `bad_request`

#### Scenario: Limit too large
- **WHEN** a client searches for `kassel` with `limit` 50
- **THEN** the API answers with 400 `bad_request`

#### Scenario: Without token
- **WHEN** a client without a token searches for `kassel`
- **THEN** the API answers with 401

### Requirement: Search speed
A search SHALL answer within 300 ms on the reference frames once the place data is loaded. Loading the place data for
the first search or the first place name SHALL happen at most once per start of the app.

#### Scenario: Typing in the web UI
- **WHEN** the place data is loaded and a client sends one search per typed letter
- **THEN** each search answers within 300 ms on the Lenovo
