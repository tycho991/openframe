# openframe – Regeln für Claude Code

Bilderrahmen-App: `:core` (Kotlin/JVM, Fachlogik und HTTP-API), `:android` (Kiosk-APK),
`webadmin/` (Svelte 5 + TypeScript), API-Vertrag in `api/openapi.yaml`. Geplant wird
mit OpenSpec unter `openspec/`; Entscheidungen stehen im `design.md` des jeweiligen Changes.

## Modulgrenzen

- `:core` hat keine `android.*`/`androidx.*`-Importe. `./gradlew :core:check` prüft das
  (`checkNoAndroidImports`) und prüft per Animal Sniffer jede JDK-API gegen Android 21 mit
  Core Library Desugaring. Kein `readNBytes`, kein `java.nio.file`, keine Java-9+-APIs
  ohne Ersatz (siehe `core/.../IoCompat.kt`).
- Plattformabhängiges liegt hinter den Schnittstellen in `core/.../platform/`
  (`MediaEngine`, `Clock`, `PlatformInfo`, `AssetSource`, `MediaStoreNotifier`) und wird
  in `:android` implementiert, in Tests als JVM-Fake (`core/src/test/.../testing/`).
- Die Anzeige bekommt Daten als `StateFlow`s aus `FrameCore`, nie über HTTP.
- `:android` nutzt nur das Framework, kein AndroidX (minSdk 21).

## API und Contract-Tests

- `api/openapi.yaml` ist die Quelle der Wahrheit. Neue oder geänderte Endpunkte zuerst
  dort, dann Kotlin-DTOs von Hand, dann `npm run gen:api` für die Typen der Web-UI.
- Tests gegen den Server laufen mit `TestHttp(..., checkContract = true)` bzw. `TestFrame`;
  jede Antwort wird gegen den Vertrag geprüft. `RouteAccessTest` stellt sicher, dass Router
  und Vertrag dieselben Endpunkte und dieselbe Sicherheit haben.
- Nur `GET /info`, `POST /pairing` und `POST /pairing/code` sind ohne Token erreichbar.

## Tests und Prüfungen

- `./gradlew check` (Formatierung, Tests, API-21-Prüfung, Android Lint) muss grün sein.
- Web-UI: `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e` in `webadmin/`.
- `openspec validate <change>` muss mit Exit 0 enden. Die Hinweise „should contain SHALL or MUST“
  sind erwartet: Die Prüfung kennt nur englische Schlüsselwörter und erkennt unser „MUSS“ nicht
  (es gibt dafür keine Einstellung). Deshalb nie `--strict` verwenden und keine englischen
  Schlüsselwörter in die deutschen Specs mischen. Anforderungen unter 500 Zeichen halten, längere
  in mehrere Anforderungen teilen, sonst warnt der Validator zusätzlich.
- Der Build braucht eine UTF-8-Locale, weil Testnamen Umlaute enthalten.
- Was nur auf Hardware messbar ist, gehört in `docs/DEVICE_TEST.md` mit Verweis auf das
  Spec-Szenario.

## Geräte

- Zugangsdaten, IP-Adressen und Notizen zu den Geräten stehen im Nachbarprojekt unter
  `../pictureframe/devices/`. Nicht in dieses Repo kopieren.
- Vor jeder dauerhaften Änderung am Gerät nachfragen: Pakete installieren oder
  deinstallieren, HOME-Launcher ändern, Einstellungen des Systems, Dateien im Fotoordner
  löschen. Lesende Befehle (`adb shell dumpsys`, `adb logcat`, `adb pull`) sind ohne
  Rückfrage in Ordnung.
- `pictureframe` (`com.pictureframe.kiosk`) bleibt installiert, bis openframe seine Rolle
  übernommen hat. Seine Daten nicht anfassen.
