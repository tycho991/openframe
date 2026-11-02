## Purpose

Defines how openframe gets its version numbers and releases: from Conventional Commit titles of squash-merged pull requests, through a release pull request, to a tagged GitHub release with a signed APK that frames can update from.

## ADDED Requirements

### Requirement: Conventional pull request titles
Every pull request to `main` SHALL have a title in the Conventional Commits form `type(scope)!: description`, where scope and `!` are optional. A check on the pull request SHALL fail for any other title. Pull requests SHALL be squash-merged with their title as the commit subject, so that the title, not the subject of a single commit, becomes the commit on `main`.

#### Scenario: Valid title
- **WHEN** a pull request is titled `feat: install updates from GitHub releases`
- **THEN** the title check passes

#### Scenario: Free-form title
- **WHEN** a pull request is titled `Review of #20: pick the tile from the polled list`
- **THEN** the title check fails and names the expected form

### Requirement: Release pull request
After a merge to `main`, if there are `feat`, `fix`, `perf` or breaking commits since the last release, a release pull request SHALL be open that names the next version and adds those commits to `CHANGELOG.md`. Commits of other types alone SHALL NOT lead to a release.

#### Scenario: Fix merged
- **WHEN** a pull request `fix: keep the display off after a restart` is merged after release 0.3.0
- **THEN** a release pull request for 0.3.1 is open and its changelog lists the fix

#### Scenario: Performance improvement merged
- **WHEN** only a pull request `perf: decode thumbnails in one pass` was merged since release 0.3.0
- **THEN** a release pull request for 0.3.1 is open and its changelog lists the improvement

#### Scenario: Only housekeeping
- **WHEN** only `chore:` and `docs:` pull requests were merged since the last release
- **THEN** no release pull request is open

### Requirement: Version steps before 1.0
While the version is below 1.0.0, a `feat` commit SHALL raise the minor version, a `fix` or `perf` commit the patch version, and a breaking commit the minor version. The highest step among the commits since the last release SHALL decide.

#### Scenario: Feature and fix
- **WHEN** a `feat` and a `fix` pull request were merged since release 0.3.1
- **THEN** the release pull request names 0.4.0

### Requirement: Release on merge
Merging the release pull request SHALL create the tag `vX.Y.Z` on that commit and a GitHub release `vX.Y.Z` whose notes are the version's changelog entries. The release SHALL be published only once its APK is attached, so that no frame sees a release without one.

#### Scenario: Release 0.4.0
- **WHEN** the release pull request for 0.4.0 is merged and its APK is signed and attached
- **THEN** the tag `v0.4.0` and a published release `v0.4.0` with its changelog entries and its APK exist

#### Scenario: Before the APK is attached
- **WHEN** a frame asks for the latest release while the APK of 0.4.0 is still being built or waits for its signature
- **THEN** it gets the previous release

### Requirement: Release checks before the build
Before the APK of a release is built, all checks of the project SHALL pass on the release commit: the Gradle checks with Android Lint, the web UI's lint, type check and unit tests, and the end-to-end tests in all three browsers. If one fails, no APK SHALL be built and the release SHALL stay unpublished.

#### Scenario: Failing check
- **WHEN** an end-to-end test fails on the release commit of 0.4.0
- **THEN** no APK is built, the release stays unpublished, and frames keep getting the previous release

### Requirement: Signing apart from the build
The build of a release SHALL have no access to the signing key and no right to change the repository or its releases; it SHALL produce an unsigned APK. Signing SHALL run without the project's code and only after the owner approved it for that release. If the signing certificate differs from the fingerprint pinned in the repository, the release SHALL stay unpublished.

#### Scenario: Waiting for approval
- **WHEN** the unsigned APK of 0.4.0 is built and the owner has not approved signing yet
- **THEN** the release stays unpublished and no signed APK exists

#### Scenario: Wrong key
- **WHEN** the stored key is not the one with the pinned fingerprint
- **THEN** signing stops with an error naming both fingerprints, and the release stays unpublished

### Requirement: Signed APK on every release
Every published release SHALL carry exactly one APK named `openframe-X.Y.Z.apk`, signed with a signature Android 5.0 can verify and with the certificate of the pinned fingerprint. Its package ID SHALL be openframe's, its version name and version code SHALL match the tag, and it SHALL NOT be debuggable.

#### Scenario: Release built
- **WHEN** the release `v0.4.0` is published
- **THEN** `openframe-0.4.0.apk` is attached, GitHub states its SHA-256, and the APK reports version 0.4.0, version code 400, the pinned certificate and no debug flag

### Requirement: Latest release is the highest version
A release SHALL be marked as the latest only if its version is higher than that of every other published release. A release of a lower version that is published later SHALL NOT replace the latest one.

#### Scenario: Late older release
- **WHEN** release 0.4.1 is published and afterwards the delayed release 0.4.0 is published
- **THEN** 0.4.1 stays the latest release

### Requirement: Single version source
The version SHALL be stored in exactly one file of the repository, which only the release pull request changes. The app's version name, the version in `GET /info` and the version in `webadmin/package.json` and its lock file SHALL be derived from it or kept equal to it by the release pull request.

#### Scenario: After release 0.4.0
- **WHEN** the APK of release 0.4.0 runs on a frame
- **THEN** `GET /info` reports `appVersion` 0.4.0, and `webadmin/package.json` in the tagged commit says 0.4.0

### Requirement: Derived version code
The app's version code SHALL be derived from the version `X.Y.Z` as `X*10000 + Y*100 + Z`, so that every release has a higher version code than the one before. Minor and patch version SHALL stay below 100; a version outside this form SHALL fail the build.

#### Scenario: Version codes
- **WHEN** the versions 0.4.0, 0.4.1 and 1.0.0 are built
- **THEN** their version codes are 400, 401 and 10000

#### Scenario: Version out of range
- **WHEN** the stored version is `0.100.0` or `0.1`
- **THEN** the build fails with a message naming the expected form
