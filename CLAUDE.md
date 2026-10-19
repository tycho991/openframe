# openframe – rules for Claude Code

Picture frame app: `:core` (Kotlin/JVM, business logic and HTTP API), `:android` (kiosk APK),
`webadmin/` (Svelte 5 + TypeScript), API contract in `api/openapi.yaml`. Planning uses
OpenSpec under `openspec/`; decisions are in the `design.md` of the respective change.

## Language

Everything in the repository is written in English: OpenSpec artifacts (requirements use
SHALL/MUST), documentation, code comments and KDoc, test names, log messages and API error
messages. The only German texts are the German translations that users see: `de.json` in the web
UI and `values-de/strings.xml` on the frame. Commit messages are in English as well.

## Module boundaries

- `:core` has no `android.*`/`androidx.*` imports. `./gradlew :core:check` verifies this
  (`checkNoAndroidImports`) and checks every JDK API against Android 21 with Animal Sniffer and
  core library desugaring. No `readNBytes`, no `java.nio.file`, no Java 9+ APIs without a
  replacement (see `core/.../IoCompat.kt`).
- Platform-dependent things sit behind the interfaces in `core/.../platform/`
  (`MediaEngine`, `Clock`, `PlatformInfo`, `AssetSource`, `MediaStoreNotifier`) and are
  implemented in `:android`, in tests as a JVM fake (`core/src/test/.../testing/`).
- The display gets its data as `StateFlow`s from `FrameCore`, never over HTTP.
- `:android` uses only the framework, no AndroidX (minSdk 21).

## API and contract tests

- `api/openapi.yaml` is the source of truth. New or changed endpoints go there first, then the
  Kotlin DTOs by hand, then `npm run gen:api` for the web UI's types.
- Tests against the server run with `TestHttp(..., checkContract = true)` or `TestFrame`;
  every response is checked against the contract. `RouteAccessTest` ensures that router and
  contract have the same endpoints and the same security.
- Only `GET /info`, `POST /pairing` and `POST /pairing/code` are reachable without a token.

## Tests and checks

- `./gradlew check` (formatting, tests, API 21 check, Android Lint) must be green.
- Web UI: `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e` in `webadmin/`.
- `openspec validate <change>` must end with exit 0 and without warnings. Keep requirements
  under 500 characters; split longer ones into several requirements, otherwise the validator
  warns.
- What is measurable only on hardware belongs in `docs/DEVICE_TEST.md` with a reference to the
  spec scenario.

## Devices

- Credentials, IP addresses and notes on the devices are in the neighboring project under
  `../pictureframe/devices/`. Do not copy them into this repo.
- Ask before every persistent change on the device: installing or uninstalling packages,
  changing the HOME launcher, system settings, deleting files in the photo folder. Read-only
  commands (`adb shell dumpsys`, `adb logcat`, `adb pull`) are fine without asking.
- `pictureframe` (`com.pictureframe.kiosk`) stays installed until openframe has taken over its
  role. Do not touch its data.
