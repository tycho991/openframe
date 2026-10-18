# openframe

Digitaler Bilderrahmen für alte Android-Tablets und Frameo-Rahmen. Die
Slideshow läuft nativ auf dem Gerät, verwaltet wird im Browser von Laptop
oder Handy. Es gibt keine Cloud: Fotos kommen per USB oder Upload auf den
Rahmen, der Rahmen selbst ist der Server.

Ziel-Geräte sind ab Android 5.0 (API 21), getestet auf dem Frameo `PFF_1024`
(Android 6.0.1) und dem Lenovo Yoga Tablet 2 1050F (Android 5.0.1).

## Architektur

Eine Codebasis in Kotlin. `:core` enthält die ganze Fachlogik und läuft auf
der reinen JVM: Einstellungen, Fotobibliothek mit Index, Bildgrößen-Cache,
Slideshow-Steuerung, Kopplung und die HTTP-API unter `/api/v1` (NanoHTTPD als
Transport). `:android` ist die Kiosk-APK: Foreground-Service mit dem Kern,
native Anzeige mit zwei `ImageView`s und die Anbindung an `BitmapFactory`. Die
Anzeige bekommt ihre Daten direkt als `StateFlow`s aus dem Kern, nicht über
HTTP. Die Verwaltungsoberfläche in `webadmin/` (Svelte 5, TypeScript, Vite)
wird beim Bauen in die APK gelegt und vom Rahmen ausgeliefert. Der API-Vertrag
steht in `api/openapi.yaml`; daraus entstehen die Typen der Web-UI, und
Contract-Tests prüfen jede Antwort des Servers dagegen.

Hintergründe und Entscheidungen: `openspec/changes/bootstrap-openframe-core/design.md`.

## Bauen

Voraussetzungen: [mise](https://mise.jdx.dev) für JDK 17 und Node, dazu ein
Android-SDK mit Plattform 36.

```bash
mise trust && mise install
# Pfad zum Android-SDK, falls ANDROID_HOME nicht gesetzt ist:
echo "sdk.dir=$HOME/android-sdk" > local.properties
./gradlew :android:assembleDebug
```

Die APK liegt danach unter `android/build/outputs/apk/debug/android-debug.apk`.
Ohne Node bricht der Build mit einer Meldung ab, statt eine APK ohne Web-UI zu
bauen. Ohne Android-SDK wird `:android` übersprungen; `:core` und die Web-UI
lassen sich trotzdem bauen und testen.

Der Build braucht eine UTF-8-Locale (`LANG=C.UTF-8` oder `de_DE.UTF-8`), weil
Testnamen Umlaute enthalten.

## Installieren und erste Kopplung

```bash
adb install -r android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n io.github.tycho991.openframe/.android.DisplayActivity
```

openframe läuft parallel zu `pictureframe` (eigene Paket-ID). Soll es nach dem
Neustart von selbst erscheinen, in den Android-Einstellungen als
Startbildschirm (HOME) wählen.

1. Ohne Fotos zeigt der Rahmen die Adresse der Verwaltung, etwa
   `http://192.168.100.215:8080/`, und einen sechsstelligen Kopplungscode.
2. Die Adresse im Browser öffnen und den Code eingeben. Der Browser ist danach
   gekoppelt; weitere Geräte koppeln sich genauso („Code am Rahmen anzeigen“).
3. Fotos hochladen oder per USB in den Ordner
   `Android/data/io.github.tycho991.openframe/files/photos` kopieren.
   Unterordner werden zu Alben.

Am Rahmen selbst: Tippen zeigt Name, Adresse, Code und Pause. Wischen blättert.
Drei Sekunden in eine Ecke drücken öffnet das Gerätemenü mit „nur lokal“,
„Alle Kopplungen aufheben“ und Info.

Deinstallieren löscht den Fotoordner mit. Vorher sichern:
`adb pull /sdcard/Android/data/io.github.tycho991.openframe/files/photos`.

## Entwickeln

```bash
./gradlew check                 # Formatierung, :core-Tests, Android-Importe, API 21, Android Lint
./gradlew :android:testDebugUnitTest
cd webadmin
npm ci
npm run lint && npm run typecheck && npm test
npm run e2e                     # baut die Web-UI und testet sie per Playwright gegen einen :core-Testserver
OPENFRAME_URL=http://192.168.100.215:8080 npm run dev   # Vite mit Hot Reload, API über Proxy an den Rahmen
```

Den `:core`-Testserver mit Fake-Bildern startet auch
`./gradlew :core:runTestServer --args="8090 8091 webadmin/dist"`; die Web-UI
liegt dann unter `http://127.0.0.1:8090/`.

Was sich nur auf echter Hardware prüfen lässt, steht in
[`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md).

## Arbeitsablauf mit OpenSpec

Änderungen werden als OpenSpec-Change geplant (Proposal, Specs, Design,
Tasks unter `openspec/changes/`) und dann umgesetzt. In Claude Code:

1. `/opsx:propose` für einen neuen Change
2. `openspec validate <name>` und `openspec show <name>`
3. `/opsx:apply` zum Umsetzen
4. `/opsx:archive` bzw. `openspec archive <name> --yes`

Node und OpenSpec sind in `.mise.toml` gepinnt. Renovate erhöht die Version per
PR; im Branch des PRs dann:

```bash
mise install
mise run openspec-sync
git add -A && git commit -m "OpenSpec-Skills aktualisieren"
```

Die Drift-Prüfung in `.github/workflows/openspec-drift.yml` stellt sicher, dass
die committeten Skills und Commands genau zur gepinnten Version passen.
