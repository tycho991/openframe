# Proposal

## Why

openframe has no releases. The version is fixed at `0.1.0`/`1` in `android/build.gradle.kts`, a new build reaches a frame only through `adb` or `pm install -r`, and nothing records what changed. The updater planned in `app-auto-update` needs signed releases on GitHub to update from, and their signing key is the one the frames trust: whoever can use it can replace the app on every frame. So releases need version numbers that come from the work itself, and the key must be out of reach of the build, of its dependencies and of pull requests. This change builds the release path on its own, before any frame depends on it; its first release need not be installed on a frame.

## What Changes

- **Pull request titles.** Pull requests are squash-merged with their title, and the title follows Conventional Commits. A workflow without third-party actions checks it on every pull request.
- **release-please.** It keeps a release pull request open with the next version and `CHANGELOG.md`. `feat`, `fix`, `perf` and breaking changes lead to a release; below 1.0, `feat` and breaking changes raise the minor version, `fix` and `perf` the patch version. Merging the release pull request creates the tag `vX.Y.Z` and a draft release.
- **One version source.** The version lives in `version.txt`, which only the release pull request changes. Gradle derives `versionName` and `versionCode` (`X*10000 + Y*100 + Z`) from it; release-please keeps `webadmin/package.json` and its lock file in step.
- **Release gate.** Before anything is built for a release, the full CI (`ci.yml`, called as a reusable workflow) runs on the release commit.
- **Build without the key.** A build job with a read-only token, no secret and no caches builds an unsigned, non-debuggable release APK and checks package, version and contents.
- **Signing apart from the build.** A sign job in the GitHub environment `release` waits for the owner's approval, checks out no project code, signs with `apksigner` from the runner's SDK, checks the certificate against a fingerprint pinned in the workflow, attaches `openframe-X.Y.Z.apk` and publishes the release. An older version published later never becomes "latest".
- **Release build type.** The `release` build type has no signing configuration and no fallback to a local keystore. Debug builds keep signing with the developer's key; their crash test receiver now needs the `DUMP` permission, which only the shell holds.
- **Renovate.** Its pull requests get commit types: `fix(deps)` for what ends up in the APK, `chore(deps)` for tools and tests.
- **Docs.** `CLAUDE.md` gets the rules for titles, versions and releases (Claude never approves a deployment and never changes repository settings); `README.md` a section on releases and the runbook; `SECURITY.md` names the latest release as supported.

### Out of scope

- The updater on the frame, its API and its web UI: `app-auto-update`, which builds on this change.
- Installing releases on the frames. The first release of this change stays off the frames; the first release with the updater is the last one installed by hand.
- Pre-releases, a beta channel, downgrades.
- A second, offline signature over the release (the owner decided against it).
- A new signing key. The frames trust the developer's debug key, and a new key would mean uninstalling openframe and losing its data on every frame.

## Capabilities

### New Capabilities

- `app-release`: how a version number and a release come about: Conventional Commit titles, the release pull request, version steps, the single version source and the derived version code, the checks before the build, signing apart from the build, the signed APK on every release and which release counts as the latest.

### Modified Capabilities

None.

## Impact

- Build: `version.txt` (new), `android/build.gradle.kts` (version from `version.txt`, `release` build type without signing), `FrameService` (crash test receiver behind `DUMP`), `gradle/verification-metadata.xml` if the release build resolves new artifacts.
- CI: `release-please-config.json` and `.release-please-manifest.json` (new); `.github/workflows/release-please.yml` and `pr-title.yml` (new); `ci.yml` also as a reusable workflow, its APK check with the version line; `renovate.json` (commit types, the new workflows in the mise version manager).
- Docs: `CLAUDE.md`, `README.md`, `SECURITY.md`.
- Settings the owner changes on GitHub: allow Actions to create pull requests and add the labels `autorelease: pending` and `autorelease: tagged` before the merge. After the switch to public, and before the first release, create the environment `release`, then its secret. The rulesets for `main` and `v*` come from `prepare-public-release`.
- Frames: none. They keep their installed build until the first release of `app-auto-update` is installed by hand.
