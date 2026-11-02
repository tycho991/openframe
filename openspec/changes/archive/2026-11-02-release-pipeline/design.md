# Design

## Context

What exists today:
- **Version and build.** The version is hard-coded in `android/build.gradle.kts` (`versionName = "0.1.0"`, `versionCode = 1`) and again in `webadmin/package.json` and its lock file. `AndroidPlatformInfo.appVersion` reads `versionName`, and `GET /info` reports it. There is no `release` build type of our own and there are no tags or releases. CI builds `assembleDebug` with a debug key that the runner creates for each run.
- **The frames' key.** The openframe APK on the Lenovo is signed with the developer's `~/.android/debug.keystore` (certificate SHA-256 `2f1a6702…34fe`). An encrypted offline backup of it exists, and its restore was tested (`prepare-public-release` task 6.3). The neighbouring project pictureframe is signed with the same key.
- **Debug builds.** In a debuggable build `FrameService` registers the receiver `DEBUG_CRASH` without a permission, so any app on the frame can make openframe crash; the README warns about it.
- **Repository settings** (`prepare-public-release` task 6.1): squash merges only, with the pull request title as subject and an empty body; read-only `GITHUB_TOKEN` by default; full-length SHA pinning of actions required; immutable releases; artifacts and logs kept 14 days. Not set yet: Actions may not create pull requests, and the labels `autorelease: pending` and `autorelease: tagged` do not exist. After the switch to public, `prepare-public-release` task 6.5 adds a `main` ruleset (pull request required without approvals, no force push, no deletion, linear history, no required status checks) and a `v*` tag ruleset (no update, no deletion).
- **CI.** `ci.yml` runs the jobs `gradle`, `webadmin`, `playwright-version` and `e2e` (three browsers) on pull requests and pushes to `main`, with `contents: read` and a concurrency group prefixed `ci-`.
- **Renovate** opens pull requests without commit types. Its groups: "Kotlin" (compiler, stdlib, coroutines, serialization), "Android Gradle Plugin" (with `desugar_jdk_libs`), "Web UI tools" (all minor and patch updates of the web UI's dev dependencies, `svelte` included) and "Playwright".
- **History.** The new repository's history was filtered during the move. Every commit since `2cd10f0` on `main` was squash-merged with a Conventional pull request title.

See proposal.md for the motivation and the spec for the required behavior.

## Goals / Non-Goals

**Goals:**
- No version number is edited by hand, ever.
- The signing key is reachable only by one job that runs no project code, and only after the owner approved that run.
- What is published is exactly what passed the full CI on the release commit.

**Non-Goals:**
- The updater (`app-auto-update`).
- Reproducible builds, or signing releases on the developer machine.
- A second signature over the release, for example an offline-signed manifest. The owner decided against it: the separation of build and signing, the pinned fingerprint, immutable releases and the approval cover the threats this project sees, and a second key would mostly add work.

## Decisions

### 1. release-please with the `simple` type and `version.txt`

The root gets `version.txt` (`0.1.0`), the native version file of the `simple` type.
- `release-please-config.json`:
  - `release-type: simple`, `bump-minor-pre-major: true`, `include-component-in-tag: false` (tags `vX.Y.Z`), `draft: true` and `force-tag-creation: true`. GitHub creates the tag of a draft only when it is published; `force-tag-creation` (release-please 17.2, so `googleapis/release-please-action` v4.4.1 or later) creates it at once, which the build and the next release-please runs need.
  - `changelog-sections` shows `feat`, `fix` and `perf` and hides `chore`, `docs`, `test`, `ci`, `build`, `refactor`, `style` and `revert`. release-please releases only for visible sections, so `perf` leads to a patch release (the owner's decision) and the hidden types never do.
  - `extra-files` with the `json` updater: `webadmin/package.json` (`$.version`) and `webadmin/package-lock.json` (`$.version` and `$.packages[""].version`). A JSONPath that matches nothing changes nothing without an error, and `npm ci` does not notice a stale root version; so the APK step of `ci.yml` asserts that all three equal `version.txt`. If `$.packages[""]` does not match the empty key, `$.packages[?(@property === '')].version` does; never `$.packages.*.version`, which would rewrite every dependency.
  - `bootstrap-sha: 2cd10f0`, the first commit on the filtered `main` after which every commit is a squash merge with a Conventional title. release-please never parses the free-form history before it.
- `.release-please-manifest.json` holds `{ ".": "0.1.0" }`.

A revert is titled `fix: revert …`, so that it releases like the fix it is.

*Alternatives:* `git describe` as the version source: it breaks builds from a source archive without `.git`. `gradle.properties` as the store would need the `generic` updater with marker comments.

### 2. Gradle: version from `version.txt`, an unsigned release build

`android/build.gradle.kts` reads `rootProject.file("version.txt")`, checks it against `^\d+\.\d+\.\d+$` with minor and patch below 100, and fails otherwise with a message naming the form. It sets `versionName` to it and `versionCode = X*10000 + Y*100 + Z`. Debug and release builds have the same name and code, so a development build never claims to be newer than the release it is based on.

The `release` build type has no `signingConfig`: no fallback to `~/.android/debug.keystore`, no passwords in Gradle. `assembleRelease` gives `android-release-unsigned.apk`. R8 stays off (`isMinifyEnabled = false`): kotlinx.serialization would need keep rules, and the size gain does not matter. A release build is not debuggable, so `FrameService` does not register `DEBUG_CRASH` there. In debug builds it registers the receiver with the broadcast permission `android.permission.DUMP` (on API 33 and later with `RECEIVER_EXPORTED`); only the shell holds it, so `adb shell am broadcast` keeps working and other apps cannot trigger the crash. Debug builds keep signing with the developer's local debug key, the frames' key.

### 3. Workflow layout

`.github/workflows/release-please.yml` (`name: release-please`) runs only `on: push: branches: [main]`, without `workflow_dispatch`, and has `permissions: {}` at the top. Each job gets only what it needs:

| Job | Needs | Permissions | Purpose |
|---|---|---|---|
| `release-please` | – | `contents`, `pull-requests`, `issues`: write | release pull request; after its merge the tag and a draft release |
| `checks` | `release-please`, only if `release_created == 'true'` | `contents: read` | `uses: ./.github/workflows/ci.yml` on the release commit |
| `build` | `release-please`, `checks` | `contents: read` | unsigned APK, checked, uploaded as an artifact |
| `sign` | `release-please`, `build` | `contents: write`, environment `release` | sign, check, attach, publish |

- `ci.yml` gets `workflow_call` as an additional trigger, with an optional input `ref` that all its checkouts use (`ref: ${{ inputs.ref || github.sha }}`); the gate passes release-please's `sha`. A called workflow gets at most the permissions of the calling job, and `ci.yml` asks for `contents: read`, so `checks` grants exactly that. In the call `github.workflow` is the caller's name, so its concurrency group becomes `ci-release-please-<sha>` and cannot collide with the push run of the same commit (`ci-ci-<sha>`) or with the `release-please` job. The local `uses: ./.github/workflows/ci.yml` refers to the same commit and needs no SHA pin.
- Only the `release-please` job has `concurrency: {group: release-please-main, cancel-in-progress: false}`. At workflow level, a `sign` job that waits for approval would hold the group and queue, or let GitHub cancel, every later run on `main`.
- GitHub keeps only one pending run per group, so the run of the release pull request's merge may be cancelled by a later push; the release is then created in the run of a later commit. Gate and build therefore use release-please's `sha` output (the tag's commit), never `github.sha`.
- Outputs of release-please (`release_created`, `tag_name`, `version`, `sha`) are strings and reach `run:` scripts only through `env:`, never as `${{ }}` inside a script.

### 4. The build job

- `actions/checkout` of the tag (`ref` from `tag_name`) with `persist-credentials: false`, then `test "$(git rev-parse HEAD)" = "$RELEASE_SHA"` with release-please's `sha`. A read-only token cannot see draft releases, so the job checks the tag with git; the `sign` job checks the draft.
- `jdx/mise-action` with `version: ${{ env.MISE_VERSION }}`, `install_args: java node` and `cache: false`; `gradle/actions/setup-gradle` with `cache-disabled: true`. A poisoned cache from another run cannot reach the release.
- `./gradlew --no-build-cache --no-configuration-cache :android:assembleRelease`. npm runs without install scripts (`webadmin/.npmrc`).
- The checks of `ci.yml`'s APK step on the unsigned APK (package ID, `minSdkVersion` 21, no `native-code`, the web UI and the licence files in the assets), plus `versionName` equal to `version`, `versionCode` equal to the derived value, and no `application-debuggable`. The log shows the lines.
- `actions/upload-artifact` of the unsigned APK under the name `release-apk-unsigned` with `retention-days: 1`, `overwrite: true` (for a re-run of the job) and `if-no-files-found: error`. No reports.

The job has no secret, no environment and no write right, so a compromised dependency in the build can neither read the key nor change the repository or its releases.

### 5. The sign job and publishing

The job runs in the environment `release`, so it waits for the owner's approval and is the only job that can read `FRAME_KEYSTORE_BASE64`. It has no `actions/checkout`: the only code it runs is `actions/download-artifact` (pinned by SHA), the runner's `gh` and the tools under `$ANDROID_HOME/build-tools` (the highest version).

1. Download the artifact by its name (the called `ci.yml` uploads a debug APK into the same run) and check that it holds exactly one APK. `gh` gets `GH_TOKEN` and `GH_REPO` from `env:`, because without a checkout it cannot infer the repository. Check that the release `vX.Y.Z` is still a draft and that the tag resolves to release-please's `sha`.
2. Stop if the secret is empty. Otherwise write the keystore with `umask 077` to `$RUNNER_TEMP`; a later step with `if: always()` deletes it.
3. `zipalign -f 4`, then `apksigner sign` with the alias and the passwords of a debug keystore (`androiddebugkey`, `android`), which are public. apksigner reads `minSdkVersion` 21 from the manifest and adds the v1 signature that Android 5 and 6 need; step 4 checks it.
4. `apksigner verify --min-sdk-version 21 --print-certs` must pass and report the certificate SHA-256 `FRAME_CERT_SHA256`, which the workflow states in full as a public value. Otherwise the job stops and prints both fingerprints. The file must start with `PK\x03\x04`, and `aapt2 dump badging` must show package, version name and version code again.
5. `gh release upload vX.Y.Z openframe-X.Y.Z.apk`. GitHub records the asset's SHA-256 as its `digest`, which the updater checks.
6. Publish with the latest rule. Right before `gh release edit`, `gh release list --exclude-drafts --limit 100 --json tagName` gives the published versions; listing late keeps the window small in which two approved sign jobs could both see themselves as the highest, and the runbook says to approve one release at a time. If the own version is higher than all of them, `gh release edit vX.Y.Z --draft=false --latest`, otherwise `--latest=false`. The comparison runs as a small shell function, tested on its own (0.3.0 after 0.3.1 gives `--latest=false`, 0.10.0 after 0.9.0 gives `--latest`).

`releases/latest` ignores drafts, so until step 6 a frame gets the previous release. The sign job uploads no artifact and no report.

*Alternatives:* signing in the build job with the secret (the plan before the audit) gives every build dependency the key. A separate workflow on `release: published` would not start, because events created with `GITHUB_TOKEN` start no workflows.

### 6. Pull request title check without a third-party action

`.github/workflows/pr-title.yml` runs on `pull_request` (`opened`, `edited`, `synchronize`, `reopened`), never on `pull_request_target`, with `permissions: {}`. One step reads the title from `env:` and matches it in bash against `^(feat|fix|perf|refactor|docs|test|ci|build|chore)(\([a-z0-9-]+\))?!?: .+`; on a mismatch it prints the form and the types. It needs no checkout and no token.

The check is not a required status check (`prepare-public-release` design decision 8: `main` requires none, because the release pull request gets no CI); a red title is fixed before the merge.

*Alternative:* `amannn/action-semantic-pull-request` is a third-party action with read access to pull requests, for one regular expression.

### 7. Commit types for Renovate

`renovate.json`:
- `extends` gets `:semanticCommits` and `:semanticCommitTypeAll(chore)` after `config:recommended`. A top-level `semanticCommitType` would not apply to pull requests that a `packageRule` shapes; the preset is a package rule itself, so the rules after it win.
- A package rule sets `semanticCommitType: fix` for what ends up in the APK or the web UI bundle: `okhttp`, `nanohttpd`, `kotlinx-serialization-json`, `desugar_jdk_libs` and `svelte`. The groups "Kotlin" and "Android Gradle Plugin" get `fix` explicitly; they bring runtime code or build the APK, and the type of a mixed group would otherwise depend on which update comes first.
- `svelte` leaves "Web UI tools" for a group of its own, so that its pull request is a `fix` and the tool updates stay `chore`; its rule comes after the "Web UI tools" rule, because later rules win.
- `vulnerabilityAlerts` gets `semanticCommitType: fix`.
- The custom manager for `MISE_VERSION` also covers `release-please.yml`.

So a dependency update of the app leads to a patch release, and tool and test updates ride along with the next one.

### 8. GitHub settings and when they can be made

On GitHub Free, environment protection rules and rulesets work only in public repositories. An environment secret without protection would be readable by any workflow on any branch. The settings therefore come in three steps, all made by the owner:
1. **Before this change is merged** (possible while private): allow Actions to create and approve pull requests (`can_approve_pull_request_reviews`), and add the labels `autorelease: pending` and `autorelease: tagged`.
2. **Right after the switch to public, and before any release pull request is merged:** the rulesets of `prepare-public-release` task 6.5, then the environment `release` (a job that names a missing environment would create it without protection): deployment branches only `main` (no tag pattern), the owner as required reviewer, "Prevent self-review" off (the owner merges and approves), admin bypass off.
3. **Right before the first release:** the secret `FRAME_KEYSTORE_BASE64` only in the environment, never as a repository secret, filled from the restored backup after `keytool -list -v` showed the pinned fingerprint.

### 9. Rules in `CLAUDE.md`

A new section "Versions and releases":
- pull request titles follow Conventional Commits: a change's implementation is `feat:` or `fix:`, a proposal `docs: propose <change>`, ticks and archives `chore:`, a revert `fix: revert …`;
- merging is `gh pr merge --squash`, which takes the title as the subject (repository default);
- versions change only through the release pull request;
- merging the release pull request publishes a release and runs only after the owner says so;
- Claude never approves a deployment, also not through the REST API (`pending_deployments`), and never changes repository or environment settings or secrets.

The existing line about Renovate automerge points to this change instead of `app-auto-update`.

## Risks / Trade-offs

- [`draft` and `force-tag-creation` in release-please-action are untested here; with an action older than v4.4.1 the option would be ignored silently] → the pin is v4.4.1 or later, and the owner and Claude watch the first release step by step: tag present while the release is a draft, gate, build, approval, signature, published. If the tag is missing, `build` stops at the checkout, nothing is signed, and the cause is fixed with a `fix:` pull request, which becomes the next version.
- [The owner's token can approve a deployment through the REST API] → an agent session holding that token could skip the click. The `CLAUDE.md` rule lowers the risk; a fine-grained token without the Actions, Administration and Workflows rights for agent sessions would remove it.
- [The separation protects the key and the write token, not the content] → code of a compromised build dependency is signed once the owner approves. Dependency verification in Gradle, npm without install scripts, Renovate's minimum release age of 7 days and the gate lower the risk.
- [The release pull request is opened with `GITHUB_TOKEN` and gets no CI] → it changes only `version.txt`, the package files and `CHANGELOG.md`; the gate runs the full CI on the release commit before anything is built.
- [The unsigned artifact lives one day] → if the approval comes later, the sign job finds no artifact. "Re-run all jobs" would not help: release-please would find no pending release and skip everything. Re-running the `build` job re-runs it and the jobs after it with release-please's outputs of the first attempt. The README runbook says so.
- [The gate doubles the CI time of a release, and a flaky test can hold it] → "Re-run failed jobs"; a new sign job needs a new approval.
- [The key cannot be rotated on the frames] → key rotation (APK signature scheme v3) exists only from API 28, and the frames run API 21 and 23: a leaked key stays valid for good, for openframe and for pictureframe. It stays on the developer machine, in its encrypted backup and in the environment secret. Its passwords are the public ones of a debug keystore, so the file alone is the secret.
- [AGP creates a new debug key without asking when `~/.android/debug.keystore` is missing] → local debug builds would then no longer install over the frames' app; the pinned fingerprint keeps a wrong key from reaching a release. An Actions secret cannot be read back, so the backup is the only source.
- [Until the environment exists, nothing protects a release] → the secret is created only after the environment, and the environment only after the switch and the rulesets.
- [A wrong `bootstrap-sha`] → release-please would parse free-form commits or miss commits. `2cd10f0` is on the filtered history; the first release pull request shows which commits it took.

## Migration Plan

1. The owner allows Actions to create pull requests and adds the two labels (decision 8, step 1).
2. This change is implemented; its pull request is titled `feat: release signed APKs through release-please` and squash-merged after the owner's OK. release-please then opens `chore(main): release 0.2.0`.
3. After the switch to public: the rulesets (from `prepare-public-release`), then the environment `release`, then the secret (decision 8, steps 2 and 3).
4. When the owner says so, the release pull request is merged. The owner approves the sign job. Check: `gh release view v0.2.0 --json isDraft,isLatest,assets` shows `isDraft: false`, `isLatest: true` and `openframe-0.2.0.apk` with its digest, and `apksigner verify --print-certs` on the downloaded asset shows the pinned fingerprint.
5. 0.2.0 is not installed on the frames. The first release of `app-auto-update` is.

Rollback: a release is never changed after publishing (immutable releases); a bad one is fixed forward with a `fix:` release. A draft that is never published is deleted by the owner; its tag stays (the `v*` ruleset forbids deleting it), and the fix gets the next version.

## Open Questions

None. Decided by the owner: two changes, release path first; `perf` releases a patch version; no offline signature; no required status checks on `main`.
