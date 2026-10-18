# Tasks

## 1. Projektgerüst und Werkzeuge

- [x] 1.1 JDK 17 (Temurin) in `.mise.toml` pinnen, Gradle-Wrapper, `settings.gradle.kts` mit `:core` und `:android` und `gradle/libs.versions.toml` anlegen; prüfen: `mise install && ./gradlew help` läuft durch
- [x] 1.2 Modul `:core` als Kotlin/JVM-Bibliothek (Kotlin 2.x, Toolchain 17, `kotlinx-coroutines`, `kotlinx-serialization`, JUnit 5) mit einem Platzhaltertest anlegen; prüfen: `./gradlew :core:test` grün
- [x] 1.3 Gradle-Check gegen `android.*`-Importe in `:core` an `check` hängen; prüfen: ein absichtlich eingefügter Import lässt `./gradlew :core:check` mit verständlicher Meldung scheitern
- [ ] 1.4 Modul `:android` mit Paket-ID `io.github.tycho991.openframe`, `minSdk 21`, neuestem stabilem `targetSdk`, `largeHeap` und leerer Activity anlegen; prüfen: `./gradlew :android:assembleDebug` erzeugt eine APK, `aapt dump badging` zeigt Paket-ID und `minSdk 21`
- [ ] 1.5 Spotless/ktlint und Android Lint einrichten, einschließlich der Prüfung, dass keine API über `minSdk` genutzt wird; prüfen: `./gradlew check` grün, ein Stilverstoß lässt es scheitern
- [x] 1.6 `webadmin/` mit Vite, Svelte 5, TypeScript, ESLint, Prettier und Vitest anlegen, `package-lock.json` einchecken; prüfen: `npm ci && npm run lint && npm test && npm run build` in `webadmin/` grün
- [ ] 1.7 GitHub-Actions-Workflow `ci.yml` (Gradle `check` + `assembleDebug`, Web-UI-Lint/-Test/-Build) und Renovate-Regeln für Gradle und npm ergänzen; prüfen: Workflow läuft auf einem Push-Branch grün

## 2. API-Vertrag und HTTP-Schicht

- [x] 2.1 `api/openapi.yaml` für `/api/v1` mit allen Endpunkten aus design.md (Entscheidung 6), Fehlerformat und Auth-Schema schreiben; prüfen: Schema-Lint (`redocly lint` o. ä.) ohne Fehler
- [x] 2.2 Routing-Schicht in `:core` über NanoHTTPD bauen: typisierte `suspend`-Handler, JSON per `kotlinx.serialization`, einheitliche Fehlerantworten (400/401/403/404/413/415/429/500/507), begrenzter Dispatcher; prüfen: Tests gegen einen echten `ServerSocket` für Routing, Fehler-Mapping und unbekannte Pfade
- [x] 2.3 Sicherheitsheader und Origin-/`Sec-Fetch-Site`-Prüfung für ändernde Methoden umsetzen (Spec `admin-access`: Sicherheitsheader); prüfen: Test „Fremde Seite“ liefert 403, alle Antworten tragen die Header
- [x] 2.4 Contract-Test-Harness: jede Antwort eines Tests gegen `api/openapi.yaml` validieren (JSON-Schema-Validator im Testklassenpfad); prüfen: ein absichtlich falsches Feld lässt den Test scheitern
- [x] 2.5 Statische Auslieferung über `AssetSource` mit SPA-Fallback auf `index.html` und CSP für die Web-UI; prüfen: Test liefert `index.html` für `/` und `/photos`, Assets mit korrektem MIME-Typ

## 3. Einstellungen und Identität

- [x] 3.1 Atomare JSON-Speicherung (`tmp` + `fsync` + `rename`, ein Schreiber, Bündelung) als `JsonStore`; prüfen: Tests für Rundlauf, unterbrochenes Schreiben (Temp-Datei bleibt liegen → alter Stand gilt) und parallele Schreiber
- [x] 3.2 Einstellungsmodell mit Defaults, Merge über Defaults und Validierung pro Feld (Spec `frame-settings`), einschließlich Ausrichtung (`landscape`/`portrait`), Sprache am Rahmen und Cache-Budget (100–10.000 MB, Standard 1.000 MB); „nur lokal“ wird mitgespeichert, ist aber kein Feld der API; prüfen: Tests für „Erster Start“, „Update mit neuer Einstellung“, „Ungültiges Intervall“, „Unbekanntes Feld“
- [x] 3.3 `GET`/`PATCH /api/v1/settings` mit Teiländerungen, Einstellungsversion und `StateFlow` für die Anzeige; prüfen: Contract-Tests und Szenario „Nur Intervall ändern“
- [x] 3.4 Identität (zufällige ID beim ersten Start), Name als Einstellung mit Default aus `PlatformInfo` und `GET /api/v1/info` ohne Auth; prüfen: Tests „Info abrufen“ und „Name ändern“ (über `PATCH /api/v1/settings`), ID bleibt über Neustart gleich

## 4. Kopplung und Zugriff

- [x] 4.1 Kopplungscode (6 Ziffern, 5 min, einmalig, 5 Fehlversuche → 60 s Sperre), `POST /api/v1/pairing` mit Gerätename (Fallback aus User-Agent) und `POST /api/v1/pairing/code` (ohne Auth, 204, zeigt einen gültigen Code am Rahmen über einen `StateFlow` für 5 min oder bis zur Kopplung); ein angezeigter Code wird bei Ablauf ersetzt; prüfen: Tests mit Fake-`Clock` für Erfolg, Ablauf, Einmaligkeit, Sperre (429), „Code am Rahmen anfordern“ (Antwort enthält keinen Code) und Ersetzen eines angezeigten Codes nach 5 min
- [x] 4.2 Token-Speicherung als SHA-256, Auth-Filter für alle geschützten Routen, `GET/DELETE /api/v1/clients` mit Kennzeichnung des anfragenden Geräts; prüfen: Tests „Ohne Token“ (401), „Mit Token“ (200), „Kopplung aufheben“ (sofort 401); ein Test listet alle Routen und stellt sicher, dass nur die freigegebenen ohne Token erreichbar sind
- [x] 4.3 Signierte, kurzlebige Bild-URLs (`?t=`, HMAC über Foto-ID und Ablauf, Server-Geheimnis, Ablauf auf die übernächste volle Stunde gerundet) als Alternative zum Token nur für Bildabrufe; prüfen: Tests für gültige, abgelaufene und auf ein anderes Foto übertragene Signaturen (Szenarien „Signierte Bild-URL“, „Signatur auf anderes Foto übertragen“) und gleiche URL innerhalb einer Stunde
- [x] 4.4 „Alle Kopplungen aufheben“ (einschließlich neuem Signatur-Geheimnis) und Bindung an `127.0.0.1` im Modus „nur lokal“ als Core-Funktionen, nicht über die API erreichbar; prüfen: Test, dass nach dem Zurücksetzen alle Tokens und alten Bild-URLs 401 liefern und ein neuer Code entsteht; Test, dass der Server im Modus „nur lokal“ auf einer LAN-Adresse nicht annimmt; Szenario „Über die API“ (400)

## 5. Fotobibliothek

- [x] 5.1 `MediaEngine`-Schnittstelle und JVM-Fake (ImageIO + `metadata-extractor`) für Tests; Fixture-Fotos mit EXIF-Orientierung 1/6/8, PNG, WebP, GIF und eine defekte Datei unter `core/src/test/resources`; prüfen: Fake liest Maße nach Drehung korrekt
- [x] 5.2 `PhotoIndexStore` (Index im Speicher, gebündelt persistiert, Bibliotheksversion, `StateFlow` der sichtbaren Fotos); prüfen: Tests für Rundlauf, Versionswechsel bei Änderung und Neustart
- [x] 5.3 `PhotoSource` `local`: Scan alle 15 s, mtime/Größe-Abkürzung, ID aus Pfad, Bildversion, Album aus erstem Ordner, Formatfilter, HEIC nur nach erfolgreichem Probe-Decode, Liste übersprungener Dateien mit Grund und `GET /api/v1/skipped-files` (Spec `photo-library`); prüfen: Tests für „Gemischter Ordner“, „HEIC auf altem Gerät“ (Fake-MediaEngine ohne HEIC), „Neuscan ohne Änderungen“ (kein Öffnen, auch nicht der übersprungenen Dateien), „Datei ersetzt“, „Datei entfernt“, „Album zuordnen“
- [x] 5.4 Sichtbarkeit von Foto und Album, einschließlich ausgeblendeter Alben für neue Fotos; `PATCH /api/v1/photos/{id}`, `GET`/`PATCH /api/v1/albums` (mit Anzahl und Titelbild), `POST /api/v1/photos:batch`; prüfen: Contract-Tests und Szenario „Ausgeblendetes Album bekommt neues Foto“
- [x] 5.5 `GET /api/v1/photos` mit Filter, Sortierung, Cursor-Paginierung und signierten Bild-URLs pro Eintrag (aus 4.3); prüfen: Contract-Test „Seite abrufen“ und ein Test mit 10.000 synthetischen Einträgen unter 50 ms pro Seite auf der JVM
- [x] 5.6 Upload (multipart, Streaming auf die Platte, Bereinigung von Datei- und Albumnamen, eindeutige Namen, Formatprüfung, Prüfung auf mindestens 100 MB Restspeicher, sofortiger Index-Eintrag, Meldung an `MediaStoreNotifier`); prüfen: Tests „Upload in Album“, „Pfad-Traversal“ (Datei- und Albumname), „Gleicher Name“, „Kein unterstütztes Format“ (415), „Speicher voll“ (507, keine Teildatei) und ein Upload von 300 MB mit begrenztem Test-Heap
- [x] 5.7 Löschen einschließlich der abgeleiteten Größen (`DELETE /api/v1/photos/{id}`, Batch) und Meldung an `MediaStoreNotifier`; prüfen: Test „Foto löschen“, Fake-Notifier hat die Datei erhalten
- [x] 5.8 Bildgrößen-Cache: Vorschaubild 400 px und bildschirmdeckende Anzeigegröße für die eingestellte Ausrichtung (höchstens 2,5-fache Pixelzahl des Bildschirms), Cache-Schlüssel aus ID, Bildversion und Zielmaßen, ein Render-Worker, Vorab-Rendern der nächsten Fotos, beim ersten Start zuerst das erste Foto der Reihenfolge, ETag/304, `Cache-Control: immutable`, LRU-Budget aus den Einstellungen; prüfen: Tests „Vorschaubild abrufen“, „Hochformatfoto auf Querformat-Rahmen“ (1920×2560), Panorama an der Obergrenze, „Wiederholter Abruf“ (304 ohne Rendern), „Budget überschritten“ (Originale unberührt)

## 6. Android-Host

- [ ] 6.1 `AndroidMediaEngine`: `BitmapFactory` mit `inSampleSize` in zwei Durchgängen, Drehung aus `ExifInterface` (Framework-Klasse, nicht AndroidX), JPEG mit Qualität 85, GIF als erstes Frame, HEIC-Probe; prüfen: Robolectric-Tests mit den Fixture-Fotos aus 5.1
- [ ] 6.2 Foreground-Service mit `:core`, HTTP-Server, `PARTIAL_WAKE_LOCK`, `WifiLock`, Notification-Channel ab API 26 und `START_STICKY`; lokaler Binder mit den `StateFlow`s; prüfen: auf dem Emulator (API 21 und aktuell) ist `curl http://<ip>:8080/api/v1/info` erreichbar, auch wenn die Activity im Hintergrund ist
- [ ] 6.3 `BootReceiver`, HOME-Intent-Filter, immersives Vollbild mit Wiederherstellung, `FLAG_KEEP_SCREEN_ON`; prüfen: Emulator-Neustart zeigt die App ohne Eingriff; `adb shell cmd package resolve-activity -c android.intent.category.HOME` bietet sie an
- [ ] 6.4 Selbstheilung (`UncaughtExceptionHandler` → Log + `AlarmManager`-Neustart) und rotierendes Datei-Log mit `GET /api/v1/logs`; prüfen: ein Debug-Befehl, der einen Absturz auslöst, führt innerhalb von 30 s zum Neustart, und das Log zeigt die Ursache
- [ ] 6.5 `PlatformInfo` (Modell, Android-Version, Auflösung, IP-Adressen) und Neubindung des Servers nach WLAN-Wechsel; prüfen: auf dem Emulator WLAN aus und an, danach ist `/api/v1/info` wieder erreichbar und die angezeigte Adresse stimmt
- [ ] 6.6 `MediaStoreNotifier` für Android mit `MediaScannerConnection.scanFile` für angelegte und gelöschte Dateien; prüfen: auf dem Emulator nach einem Upload zeigt `adb shell content query --uri content://media/external/file` die Datei, nach dem Löschen nicht mehr (Szenario „Hochgeladenes Foto per USB“; echter MTP-Test in `docs/DEVICE_TEST.md`)

## 7. Native Slideshow

- [x] 7.1 Reihenfolge-Logik in `:core` (`shuffle` ohne Wiederholung im Durchgang, `chronological`, Umlauf, vor/zurück, Reaktion auf Änderungen der Bibliothek, gelöschtes aktuelles Foto); prüfen: JVM-Tests für alle Szenarien zu Reihenfolge, Umlauf und Änderungen aus Spec `slideshow-display`
- [ ] 7.2 Anzeige-Activity mit zwei `ImageView`s, Decode-Thread, höchstens zwei Bitmaps, Vorladen des nächsten Fotos, Überspringen defekter oder noch nicht gerenderter Fotos (Warten nur, solange noch keines gerendert ist), Start erst nach Bereitschaft des Cores (nicht des HTTP-Servers); Ausrichtung per `setRequestedOrientation` (`sensorLandscape`/`sensorPortrait`) ohne Neustart, bis zum Neu-Rendern mit der alten Anzeigegröße; prüfen: Emulator zeigt Fixture-Fotos im eingestellten Intervall, die defekte Datei wird übersprungen; Szenario „Kaltstart“ mit ausgeschaltetem WLAN und leerem Cache; Szenario „Auf Hochformat umstellen“
- [ ] 7.3 Übergänge `fade`/`slide`/`none` mit `ViewPropertyAnimator.withLayer()`, Dauer aus den Einstellungen; Füllmodi `fill`/`fit` über die Matrix; prüfen: Szenario „Übergang geändert“ ohne Neustart; Szenarien „Überblendung“, „Hochformat bei fill/fit“ visuell auf dem Emulator (Screenshots in `docs/`)
- [ ] 7.4 Uhr-Overlay (Position, Größe, 12/24 h, Datumssprache, Schatten), Minutenwechsel über `ACTION_TIME_TICK`; prüfen: Szenarien „Minutenwechsel“ und „Uhr aus“ ohne Neustart
- [ ] 7.5 Gesten und Hinweis-Overlay (Wischen vor/zurück, Tippen → Pause/Weiter, Name, Adresse, gültiger Kopplungscode), Leerzustand und Anzeige des per API angeforderten Codes; prüfen: Szenarien „Zurückwischen“, „Pause“, „Neuer Rahmen“ auf dem Emulator
- [ ] 7.6 Gerätemenü (3 s langes Drücken in der Ecke): „nur lokal“ an/aus, „Alle Kopplungen aufheben“ mit Bestätigung, Info; prüfen: Szenarien „Am Rahmen ausschalten“ und „Zugriff verloren“ auf dem Emulator
- [ ] 7.7 Gemeinsames Theme (Typografie, Farben, Abstände) für Overlay, Hinweis, Leerzustand und Gerätemenü; alle Texte am Rahmen als String-Ressourcen auf Deutsch und Englisch, gewählt über die Einstellung „Sprache am Rahmen“ ohne Neustart; prüfen: Screenshots aller vier Zustände auf 1024×600 und 1920×1200 in `docs/`, je einmal mit `de` und `en`

## 8. Verwaltungsoberfläche

- [x] 8.1 TypeScript-Typen aus `api/openapi.yaml` generieren (`openapi-typescript`), API-Client mit Token, 401-Behandlung und signierten Bild-URLs (geladene Seiten spätestens nach 50 min neu laden, bei Bildfehler Seite einmal nachladen); prüfen: `npm run typecheck`, Vitest für 401 → Kopplungsdialog und Szenario „Tab stundenlang offen“ mit Fake-Timern
- [ ] 8.2 Gradle-Task baut `webadmin/` vor `mergeAssets` in die APK-Assets und schlägt ohne Node klar fehl; prüfen: `./gradlew :android:assembleDebug` enthält `assets/webadmin/index.html`; ohne Node erscheint die Fehlermeldung
- [x] 8.3 App-Rahmen: Layout für Handy und Desktop, Navigation (Fotos, Alben, Einstellungen, Geräte), CSS-Variablen, Hell und Dunkel, i18n Deutsch/Englisch mit Erkennung über den Browser und Wahl pro Browser in `localStorage`; Playwright-Projekte für Chromium, Firefox und WebKit; prüfen: Playwright bei 360 px, 390 px und 1280 px ohne horizontales Scrollen; Szenario „Deutscher Browser“; Szenario „Offline im Heimnetz“ (Netzwerk-Mitschnitt zeigt keine Anfragen an fremde Hosts)
- [x] 8.4 Kopplungsdialog mit Gerätename, „Code am Rahmen anzeigen“ (`POST /api/v1/pairing/code`) und Fehlern (falsch, abgelaufen, Sperre); prüfen: Playwright gegen einen `:core`-Testserver für „Erster Besuch“ und „Wiederkehrender Besuch“
- [x] 8.5 Fotoraster mit virtueller Liste, Cursor-Paginierung, Filter nach Album und Sichtbarkeit, Markierung ausgeblendeter Fotos, Detailansicht; Hinweis und Liste übersprungener Dateien; prüfen: Playwright mit 10.000 Fake-Fotos, nur Vorschaubilder im sichtbaren Bereich werden angefragt (Netzwerk-Mitschnitt); Szenario „HEIC per USB auf altem Gerät“
- [x] 8.6 Mehrfachauswahl mit Ausblenden, Einblenden und Löschen (mit Bestätigung); prüfen: Playwright „Mehrere ausblenden“ und „Löschen bestätigen“
- [x] 8.7 Upload per Dateiauswahl und Drag-and-drop mit Fortschritt und Fehlern pro Datei und Albumwahl (bestehendes oder neues Album); prüfen: Playwright „Teilweiser Fehler“
- [x] 8.8 Albenansicht mit Anzahl, Titelbild und Schalter; prüfen: Playwright „Album ausblenden“
- [x] 8.9 Einstellungsformular für alle API-Einstellungen (einschließlich Name, Ausrichtung, Sprache am Rahmen und Cache-Budget, ohne „nur lokal“) mit Fehlern am Feld und Bestätigung ohne Neuladen; prüfen: Playwright „Fehler am Feld“
- [x] 8.10 Geräteliste mit Kennzeichnung des aktuellen Geräts und Aufheben der Kopplung; prüfen: Playwright „Altes Gerät entfernen“
- [x] 8.11 Aktualisierung über `GET /api/v1/state` (Polling alle 10 s, nur bei sichtbarem Tab); prüfen: Playwright „Foto per USB“ (Datei im Testserver-Ordner anlegen → erscheint in 30 s)

## 9. Dokumentation

- [ ] 9.1 README schreiben: Kurzbeschreibung, Architektur in einem Absatz, Build (`mise install`, SDK-Pfad, `./gradlew :android:assembleDebug`), Installation per `adb install`, erste Kopplung; prüfen: die Befehle laufen in einem frischen Clone wie beschrieben
- [ ] 9.2 `docs/DEVICE_TEST.md` als Checkliste für echte Hardware anlegen (Frame-Drops per `dumpsys gfxinfo`, Dauerlauf 24 h bei 5 s, 48-MP-Foto, Autostart, WLAN-Ausfall, Upload per USB am Rechner sichtbar, Parallelbetrieb mit `pictureframe`, Rückweg mit Sicherung der Fotos); prüfen: jede Zeile verweist auf ihr Spec-Szenario
- [ ] 9.3 `CLAUDE.md` mit den Projektregeln anlegen (Modulgrenzen, Contract-Tests, Geräte-Zugang mit Verweis auf `../pictureframe/devices/`, Bestätigung vor persistenten Änderungen am Gerät); prüfen: Datei ist vorhanden und verweist auf existierende Pfade

## 10. Abnahme auf den Geräten

- [ ] 10.1 Lenovo Yoga Tablet 2: APK parallel zu `pictureframe` installieren und `docs/DEVICE_TEST.md` vollständig abarbeiten (nach Rücksprache, da Paketinstallation auf dem Gerät); prüfen: alle Punkte abgehakt, Messwerte eingetragen
- [ ] 10.2 Frameo: APK parallel installieren, Slideshow, Kopplung, Upload und Dauerlauf prüfen, `pictureframe` bleibt HOME-Launcher (nach Rücksprache); prüfen: Checkliste ohne den HOME-Wechsel abgehakt

## Workflow follow-up

- Folge-Changes vorschlagen: Nextcloud-Sync, Video, Crop-Editor, Bildschirm-Zeitplan/Helligkeit, Home Assistant, mehrere Rahmen (mDNS), Self-Update.
- Change nach Review und grüner Abnahme mit `/opsx:archive` archivieren.
