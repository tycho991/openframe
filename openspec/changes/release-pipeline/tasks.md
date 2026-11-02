# Tasks

## 1. Version source and release build

- [x] 1.1 `version.txt` (`0.1.0`) at the root; `android/build.gradle.kts` reads it per design decisions 1 and 2 (format check, minor and patch below 100, `versionCode = X*10000 + Y*100 + Z`), and the fixed `versionName`/`versionCode` go away. Verify: `./gradlew :android:assembleDebug` and `aapt2 dump badging` show `versionName='0.1.0' versionCode='100'`; with `version.txt` set to `0.4.0`, `0.4.1` and `1.0.0` for a moment the version codes are 400, 401 and 10000; `0.1` and `0.100.0` fail the build with the message (scenarios of `app-release` › Derived version code).
- [x] 1.2 `release` build type without `signingConfig` and without R8 (design decision 2). Verify: `./gradlew :android:assembleRelease` gives `android-release-unsigned.apk`; `aapt2 dump badging` shows no `application-debuggable`; `./gradlew check` stays green, including `lintRelease`. If the release build resolves artifacts that `gradle/verification-metadata.xml` lacks, regenerate it with a fresh `GRADLE_USER_HOME` and `:android:assembleRelease` added to the command in the README, and commit it with the change.
- [x] 1.3 `FrameService` registers `DEBUG_CRASH` with the broadcast permission `android.permission.DUMP` (with `RECEIVER_EXPORTED` from API 33), still only in debuggable builds, through one helper that `crash-resilience` uses for `DEBUG_DECODE_CRASH` as well; the README's caveat on debug builds says that only `adb` can trigger it. Verify: Robolectric tests that a context without `FLAG_DEBUGGABLE` registers nothing and a debug context registers the receiver with the permission. A row in `../openframe-private/DEVICE_TEST.md` checks that `adb shell am broadcast` still crashes a debug build.
- [x] 1.4 The APK check in `ci.yml` also expects the version from `version.txt` and the derived version code, and asserts that `webadmin/package.json` (`.version`) and `webadmin/package-lock.json` (`.version`, `.packages[""].version`) equal `version.txt`. A Robolectric test checks that `AndroidPlatformInfo.appVersion` is the build's `versionName`. Verify: CI on the pull request is green and the step's log shows the version lines (scenario `app-release` › Single version source › After release 0.4.0, together with 4.2).

## 2. Releases with release-please

- [x] 2.1 `release-please-config.json` and `.release-please-manifest.json` per design decision 1, with the `$schema` of release-please's config. Verify: the editor's schema check shows no error; the behavior is checked by the first real run in 4.2. No `npx` without an exact version and no token on the command line.
- [x] 2.2 `ci.yml` gets `workflow_call` with the optional input `ref` used by all its checkouts. `.github/workflows/release-please.yml` (`name: release-please`) with the jobs `release-please`, `checks`, `build` and `sign` per design decisions 3 to 5: `googleapis/release-please-action` at v4.4.1 or later; `permissions: {}` at the top and the job permissions of the table; concurrency only on the `release-please` job; `release_created == 'true'` as the condition; gate and build on release-please's `sha`; outputs only through `env:`; every remote `uses:` pinned by SHA with its version comment (the local `ci.yml` needs none); every checkout with `persist-credentials: false`; every mise step with `version: ${{ env.MISE_VERSION }}`, `install_args: java node` and `cache: false`; the artifact uploaded and downloaded by name; `GH_TOKEN` and `GH_REPO` for `gh` in `sign`; `FRAME_CERT_SHA256` stated in full. Verify: `actionlint` is clean; `build` has `needs: [release-please, checks]` (scenario `app-release` › Release checks before the build › Failing check); only `sign` names `FRAME_KEYSTORE_BASE64` and `environment`; `build` has no `secrets.`, no cache and no write permission; `sign` has no `actions/checkout`; `checks` has `contents: read`.
- [x] 2.3 The latest rule and the fingerprint check as shell functions that the workflow sources inline, each tested locally as a table test: 0.3.0 after 0.3.1 gives `--latest=false`, 0.10.0 after 0.9.0 gives `--latest`, no published release gives `--latest`; an APK signed with another key stops with both fingerprints (scenario `app-release` › Signing apart from the build › Wrong key). Verify: the table tests pass.
- [x] 2.4 `.github/workflows/pr-title.yml` per design decision 6. Verify: `actionlint` is clean; the check passes on this change's pull request with its title and fails when the title is changed to a free-form one for a moment (scenarios of `app-release` › Conventional pull request titles).
- [ ] 2.5 `renovate.json` per design decision 7, keeping the English group names. Verify: the Renovate app's config check on the pull request is green; after the merge the next Renovate pull requests are named `chore(deps): …` for a test library and `fix(deps): …` for `okhttp` or the Kotlin group.

## 3. Docs

- [x] 3.1 `CLAUDE.md` gets the section "Versions and releases" of design decision 9, and the Renovate line points to this change. Verify: the section names `version.txt`, release-please, squash merges, the release pull request only on the owner's word, and that Claude never approves a deployment or changes settings.
- [x] 3.2 `README.md` gets "Releases": how a version comes about, the release workflow with the owner's approval, the runbook ("Re-run failed jobs" for a flaky test; re-run the `build` job, not all jobs, if the approval came after the artifact expired; approve one release at a time; how to check a release with `apksigner`), and the GitHub settings of design decision 8, with the environment before any release pull request is merged. `SECURITY.md` names the latest release as the supported version once one exists. Verify: both texts exist; the README names `FRAME_KEYSTORE_BASE64`, the environment `release` and the pinned fingerprint.

## 4. First release

- [x] 4.1 The owner makes the settings of design decision 8, step 1. Verify (read-only): `gh api repos/tycho991/openframe/actions/permissions/workflow` shows `can_approve_pull_request_reviews: true`; `gh api repos/tycho991/openframe/labels` lists both labels.
- [ ] 4.2 After the switch to public and the merge of this change: the owner creates the environment and then the secret (design decision 8, steps 2 and 3); only when `gh api repos/tycho991/openframe/environments/release` shows the reviewer, the branch policy `main` and `can_admins_bypass: false`, and the owner says so, merge the release pull request; the owner approves the sign job. Verify:
  - the release pull request names 0.2.0, its changelog lists the `feat` commit, and its diff changes `version.txt`, `webadmin/package.json` and both fields of the lock file (scenarios of `app-release` › Release pull request and › Version steps before 1.0, as far as a `feat` release shows them);
  - `gh api repos/tycho991/openframe/actions/secrets` does not list `FRAME_KEYSTORE_BASE64`;
  - while `sign` waits, `git ls-remote origin refs/tags/v0.2.0` shows the tag and `gh api repos/tycho991/openframe/releases/latest` answers 404 (› Release on merge › Before the APK is attached; › Signing apart from the build › Waiting for approval);
  - the run log shows the tag check, the badging lines and the fingerprint check;
  - `gh release view v0.2.0 --json isDraft,isLatest,assets` shows a published latest release with `openframe-0.2.0.apk`; `apksigner verify --print-certs` on the downloaded asset shows the pinned fingerprint and `git show v0.2.0:webadmin/package.json` says 0.2.0 (› Release on merge › Release 0.4.0, › Signed APK on every release, › Single version source).
- [ ] 4.3 Before archiving, a `docs:` or `chore:` pull request merged after 0.2.0 leads to no release pull request (› Release pull request › Only housekeeping). Verify: `gh pr list --label "autorelease: pending"` is empty after its merge.

## 5. Integration

- [x] 5.1 `openspec validate release-pipeline --strict`. Verify: exit 0 without warnings.

## Workflow follow-up

- The implementation's pull request is titled `feat: release signed APKs through release-please`.
- After 4.3, archive the change; the archive pull request is titled `chore: archive release-pipeline`.
- The scenarios › Release pull request › Fix merged and › Performance improvement merged are checked with the first `fix:` and `perf:` pull requests after 0.2.0: each leads to a release pull request for the next patch version.
