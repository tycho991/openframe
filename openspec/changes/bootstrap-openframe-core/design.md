# Design

## Context

Motivation und Umfang stehen in proposal.md, die Anforderungen in den sechs Specs dieses Changes. Für die Architektur zählen diese Randbedingungen:

- **Zielhardware**: Frameo `PFF_1024` (Android 6.0.1, armeabi-v7a, 1024×600) und Lenovo Yoga Tablet 2 1050F (Android 5.0.1, x86, 1920×1200, CA-Store von 2014). Beide haben wenig Heap (48 bis 96 MB pro App) und eine System-WebView auf Chromium-Stand der Auslieferung.
- **Erfahrung aus `pictureframe`**: NanoHTTPD läuft auf beiden Geräten stabil. Decodieren mit `inSampleSize` in zwei Durchgängen, Rendern auf einem einzigen Worker-Thread und Foreground-Service plus Wake-/Wifi-Lock sind dort erprobt. Erprobt ist auch, dass die Anzeige erst lädt, wenn der Rahmen bereit ist. In openframe heißt das: wenn der Core im Service bereit ist (Entscheidung 4). Diese Erkenntnisse werden übernommen, der Code nicht.
- **AndroidX** hat 2025 für die meisten Bibliotheken, darunter Jetpack Compose, `minSdk` auf 23 angehoben. Das Lenovo (API 21) fiele damit heraus.
- **Werkzeuge**: Node und OpenSpec sind über `.mise.toml` gepinnt, Renovate ist eingerichtet. JDK und Android-SDK liegen heute unter `~/android-toolchain` und `~/android-sdk`.

## Goals / Non-Goals

**Goals:**

- Eine Codebasis in Kotlin. Die Fachlogik liegt in `:core` und läuft auf der reinen JVM, damit sie ohne Emulator testbar ist.
- Die Slideshow rendert nativ und ist von der HTTP-Schicht entkoppelt. Sie bekommt Änderungen als Datenstrom und fragt nicht über HTTP nach.
- Ein expliziter API-Vertrag, aus dem die Typen der Web-UI erzeugt und gegen den die Server-Antworten getestet werden.
- Die Erweiterungspunkte für die Folge-Changes werden jetzt angelegt, damit diese ohne Umbau andocken können: weitere Fotoquellen (Nextcloud), Medientypen (Video), Steuerung (Home Assistant, Zeitplan) und Identität (mehrere Rahmen).

**Non-Goals:**

- Desktop- oder Server-Build von `:core`. Die JVM dient nur für Tests. Ein Desktop-Modul kann später ohne Änderungen an `:core` dazukommen.
- Erkennung im Netz per mDNS/NSD, TLS und Root-Funktionen. Dazu gehören echtes Display-Aus, Self-Update und Status-LED.
- Bildschirm-Zeitplan, Helligkeit, Screen-Pinning und Device-Owner.
- Wiederverwendung von Code aus `pictureframe`. Übernommen werden nur Erkenntnisse und Testfälle.

## Decisions

### 1. Module: `:core` (JVM) und `:android`, dazu `webadmin/`

```
openframe/
  api/openapi.yaml     API-Vertrag (Quelle der Wahrheit)
  core/                Kotlin/JVM: Domäne, Speicher, Bibliothek, HTTP-Routen, Auth
  android/             APK: Activity (Anzeige), Service (Server), AndroidMediaEngine
  webadmin/            Svelte + TypeScript + Vite, Build-Ergebnis in die APK-Assets
```

`:core` darf keine `android.*`-Importe enthalten. Das prüft ein Gradle-Check, der an `check` hängt. Plattformabhängiges liegt hinter schmalen Schnittstellen in `:core`, in `:android` echt implementiert und in den Tests als JVM-Fake:

- `MediaEngine`: Maße, EXIF, Rendern von Bildgrößen
- `Clock`
- `PlatformInfo`: Modell, Android-Version, Auflösung, IP-Adressen
- `AssetSource`: Dateien der Web-UI
- `MediaStoreNotifier`: meldet selbst angelegte oder gelöschte Fotodateien an das System, damit sie per USB sichtbar werden (siehe Entscheidung 7)

*Alternative:* Kotlin Multiplatform. Verworfen, weil es keinen zweiten Zielrahmen ohne JVM gibt und KMP Build und Tooling ohne Gegenwert verkompliziert.

### 2. Native Slideshow mit Android-Views statt WebView oder Compose

Die Anzeige ist eine eigene `Activity` mit zwei übereinanderliegenden `ImageView`s. Die Übergänge laufen über `ViewPropertyAnimator` (`alpha`, `translationX`), bewusst ohne `withLayer()`: Eine `ImageView` ohne Hintergrund überlappt sich nicht selbst und braucht für beides keinen Layer. Auf dem Frameo kostete ein expliziter Layer etwa jeden fünften Vsync (verpasste Vsyncs pro Überblendung: 20 bis 22 % mit, 2 bis 7 % ohne). Uhr und Hinweis sind gewöhnliche Views mit Schatten für die Lesbarkeit. Ein gemeinsames Theme mit Typografie und Farben gibt der Oberfläche am Gerät eine einheitliche, zeitgemäße Optik. Alle Texte am Gerät liegen als String-Ressourcen auf Deutsch und Englisch vor. Welche Sprache gilt, bestimmt die Einstellung „Sprache am Rahmen“, `auto` folgt der Systemsprache.

*Alternativen:*

- Eine moderne Web-Oberfläche in der WebView, wie in `pictureframe`. Verworfen, weil die WebViews der Zielgeräte zu alt sind, Animationen ruckeln und Polyfills nötig werden. Genau das soll besser werden.
- Jetpack Compose. Verworfen, weil es `minSdk 23` voraussetzt und auf schwacher Hardware messbar mehr kostet als Views.
- OpenGL. Verworfen, weil zwei Bitmaps mit Alpha-Animation über das normale, hardwarebeschleunigte View-System das Ziel erreichen, ohne eigene Render-Pipeline.

### 3. Bildpipeline: vorab gerendert, ein Bild voraus

- `:core` rendert für jedes Foto eine Anzeigegröße (JPEG, Qualität 85, EXIF-gedreht) und ein Vorschaubild mit 400 px. Beide landen im Cache. Das Rendern läuft auf einem einzigen Worker-Thread mit niedriger Priorität. Gerendert wird bei Bedarf und für die nächsten Fotos der Reihenfolge vorab.
- Die Anzeigegröße ist die kleinste unbeschnittene Größe, die den Bildschirm in der eingestellten Ausrichtung vollständig bedeckt. So bleibt `fill` scharf, und `fit` skaliert nur herunter. Ein Hochformatfoto 3:4 wird auf 1920×1200 also zu 1920×2560. Bei extremen Seitenverhältnissen wie Panoramen begrenzt eine Obergrenze die Größe auf das 2,5-Fache der Pixelzahl des Bildschirms. Dann wird `fill` leicht hochskaliert. Der Cache-Schlüssel enthält Foto-ID, Bildversion und Zielmaße. Nach einem Wechsel der Ausrichtung fallen die alten Einträge über LRU heraus.
- Die Anzeige decodiert nur die Anzeigegröße, ohne weitere Skalierung, auf einem eigenen Thread. Sie hält höchstens zwei Bitmaps, das aktuelle und das nächste. Ein Bitmap in ARGB_8888 belegt im schlimmsten Fall etwa 23 MB auf 1920×1200 und etwa 6 MB auf 1024×600, zwei zusammen also etwa 46 MB bzw. 12 MB. `largeHeap` wird gesetzt.
- `fill` und `fit` erledigt die Anzeige über die Matrix der `ImageView`. Die gerenderten Größen sind deshalb unbeschnitten. Der Crop-Editor setzt später an genau dieser Stelle an.
- Ausrichtung: Die Activity setzt `sensorLandscape` oder `sensorPortrait` aus der Einstellung per `setRequestedOrientation`, ohne Neustart. Nach einem Wechsel priorisiert der Render-Worker die nächsten Fotos in der neuen Größe. Bis eine neue Anzeigegröße fertig ist, zeigt die Anzeige die alte skaliert, statt das Foto zu überspringen.
- Erster Start: Ist für noch kein sichtbares Foto eine Anzeigegröße fertig, rendert der Worker zuerst das erste Foto der Reihenfolge, und die Anzeige zeigt es, sobald es fertig ist. Den Leerzustand gibt es nur, wenn kein Foto sichtbar ist.
- `HEIC` wird nur aufgenommen, wenn `BitmapFactory` es decodieren kann. Das ist ab API 28 der Fall und wird beim Import per Probe-Decode geprüft.

*Alternative:* Originale direkt in der Anzeige decodieren. Verworfen, weil das bei 12 bis 48 MP auf diesen Geräten zu langsam ist und den Heap sprengt, wie `pictureframe` gezeigt hat.

### 4. In-Process-Verbindung zwischen Anzeige und Core

Die Anzeige bindet sich an den Foreground-Service und erhält von `:core` `StateFlow`s für Einstellungen und die sichtbare Bibliothek. HTTP kommt dabei nicht vor. Änderungen aus der Web-UI wirken dadurch sofort. Die Anzeige wartet nur darauf, dass der Core Einstellungen und Bibliothek geladen hat, nicht auf den HTTP-Server oder das Netzwerk. Die Bibliotheksversion und die Einstellungsversion in der API dienen nur externen Clients.

*Alternative:* Die Anzeige pollt wie in `pictureframe` die eigene HTTP-API. Verworfen, weil das Latenz, unnötige Arbeit und eine Fehlerquelle beim Kaltstart bedeutet.

### 5. HTTP: NanoHTTPD hinter einem eigenen, schmalen Routing

`:core` enthält eine kleine Routing-Schicht mit typisierten Handlern, JSON über `kotlinx.serialization`, Fehler-Mapping, Auth-Filter und Sicherheitsheadern. NanoHTTPD dient nur als Transport, mit Range-Support und Streaming-Upload auf die Platte. Vor dem Upload prüft der Handler den freien Speicher anhand von `Content-Length` und während des Schreibens noch einmal. Blieben danach weniger als 100 MB frei, antwortet er mit 507 und entfernt die Teildatei. Die Handler sind `suspend` und laufen auf einem begrenzten Dispatcher.

*Alternative:* Ktor Server (CIO). Verworfen, weil es auf Android nicht offiziell unterstützt wird, zusätzliche Abhängigkeiten mitbringt und das Risiko bei API 21 auf x86 ungewiss ist. Dagegen ist NanoHTTPD auf genau diesen Geräten erprobt. Weil der Transport hinter der eigenen Schicht liegt, lässt er sich später austauschen.

### 6. API-Vertrag als OpenAPI-Datei

`api/openapi.yaml` beschreibt `/api/v1`. Daraus werden die TypeScript-Typen der Web-UI erzeugt (`openapi-typescript`). Contract-Tests in `:core` prüfen echte Antworten gegen das Schema. Die Kotlin-DTOs werden von Hand gepflegt, ihre Abweichung vom Schema fangen die Contract-Tests ab.

Die Endpunkte im Überblick (Details stehen in der Datei):

| Bereich | Endpunkte |
|---|---|
| Info, ohne Auth | `GET /api/v1/info` |
| Kopplung | `POST /api/v1/pairing`, `POST /api/v1/pairing/code` (Code am Rahmen anzeigen, ohne Auth), `GET/DELETE /api/v1/clients[/{id}]` |
| Einstellungen | `GET /api/v1/settings`, `PATCH /api/v1/settings` (einschließlich Name des Rahmens, ohne „nur lokal“, siehe Entscheidung 9) |
| Fotos | `GET /api/v1/photos?album&visible&sort&cursor&limit` (Einträge mit signierten Bild-URLs), `GET /api/v1/skipped-files`, `PATCH /api/v1/photos/{id}`, `DELETE /api/v1/photos/{id}`, `POST /api/v1/photos` (multipart), `GET /api/v1/photos/{id}/image?size=thumb\|display&v=&t=` |
| Alben | `GET /api/v1/albums`, `PATCH /api/v1/albums/{name}` |
| Batch | `POST /api/v1/photos:batch` (hide/show/delete für mehrere IDs) |
| Zustand | `GET /api/v1/state` (Bibliotheks- und Einstellungsversion) |
| Diagnose | `GET /api/v1/logs` |

*Alternative:* Kotlin als Quelle und das Schema daraus generieren. Verworfen, weil das für `kotlinx.serialization` keine ausgereiften Werkzeuge hat und eine Datei als Vertrag sich besser reviewen lässt.

### 7. Speicher: JSON-Dateien, atomar, Index im Speicher

Einstellungen, Index, Clients und Identität liegen als JSON-Dateien. Die Identität enthält nur die unveränderliche ID. Der Name des Rahmens ist eine gewöhnliche Einstellung, die `GET /api/v1/info` mit ausliefert. Geschrieben wird atomar: Temp-Datei, `fsync`, dann `rename`. Der Index wird beim Start komplett geladen und im Speicher gehalten. Schreibzugriffe werden gebündelt und von einem einzigen Schreiber nach höchstens einer Sekunde abgesetzt. Bei 10.000 Fotos ist das etwa 3 MB JSON und damit unkritisch. Liste, Filter und Sortierung arbeiten auf vorab sortierten Listen im Speicher, geblättert wird mit Cursor.

Die Fotos selbst liegen in `getExternalFilesDir("photos")`, das per USB ohne Root erreichbar ist. Was die App dort selbst anlegt oder löscht (Upload, Löschen), sieht ein USB-Rechner erst, nachdem es dem MediaStore gemeldet wurde. `:core` ruft dafür nach jeder solchen Änderung `MediaStoreNotifier` auf, `:android` setzt das mit `MediaScannerConnection.scanFile` um. Weil der Ordner zur App gehört, löscht Android ihn beim Deinstallieren mit, siehe Migration Plan. Cache und Zustand liegen in `filesDir` bzw. `cacheDir`. Tokens werden nur als SHA-256-Hash gespeichert.

*Alternative:* SQLite (SQLDelight mit Android- und JVM-Treiber). Vorerst verworfen, weil es Abhängigkeit, Schema-Migrationen und Treiberwahl mitbringt, für Datenmengen, die locker in den Speicher passen. Der Index liegt hinter einem `PhotoIndexStore`-Interface. Wechselt ein Folge-Change zu SQLite, etwa für mehrere Quellen und große Nextcloud-Bibliotheken, bleibt der Rest unberührt.

### 8. Scannen: periodisch mit Abkürzung über mtime und Größe

Der Fotoordner wird alle 15 Sekunden gescannt, direkt nach einem Upload sofort. Unveränderte Dateien erkennt der Scan an `mtime` und Größe und öffnet sie nicht. Die ID ist `SHA-1(relativer Pfad)` mit dem Präfix der Quelle (`local-…`). Bildversion und Inhalt hängen an `mtime` und Größe. `FileObserver` ist für MTP-Kopien auf alten Geräten unzuverlässig und dient deshalb höchstens als Beschleuniger.

Dateien mit Bild-Endung, die sich nicht aufnehmen lassen (HEIC ohne Decoder, beschädigt), merkt sich der Scan mit Pfad, Grund, `mtime` und Größe in einer Liste übersprungener Dateien. Er versucht sie erst wieder, wenn sich `mtime` oder Größe ändern. Die Liste liefert `GET /api/v1/skipped-files`.

Die Quelle (`PhotoSource`) ist eine Schnittstelle: `local` jetzt, `nextcloud` später. Jede Quelle verwaltet nur ihre eigenen Einträge im Index.

### 9. Zugriff: Kopplungscode, Bearer-Token, Origin-Prüfung

- Der Code besteht aus sechs Ziffern aus `SecureRandom`, gilt 5 Minuten und nur einmal. Nach 5 Fehlversuchen wird er verworfen und eine Sperre von 60 Sekunden greift. Der Rahmen zeigt den Code im Leerzustand, im Hinweis nach Tippen und auf Anfrage aus der Web-UI („Code am Rahmen anzeigen“, `POST /api/v1/pairing/code`). Die Anfrage antwortet mit 204 und gibt den Code nie zurück. Ist kein gültiger Code vorhanden, entsteht beim Anzeigen ein neuer. Nach einer Anfrage aus der Web-UI bleibt der Hinweis mit dem Code 5 Minuten stehen, oder bis eine Kopplung gelingt. Läuft der Code ab, während er angezeigt wird, also auch im Leerzustand, ersetzt ihn der Rahmen durch einen neuen. Während der Sperre nach Fehlversuchen entsteht kein Code.
- Beim Koppeln schickt der Client einen Gerätenamen mit. Fehlt er, wird er aus dem User-Agent abgeleitet (z. B. „Firefox auf Android“). `GET /api/v1/clients` kennzeichnet das anfragende Gerät.
- Das Token besteht aus 32 zufälligen Bytes in Base64url und wird als `Authorization: Bearer` gesendet. Die Web-UI speichert es in `localStorage`. Weil `<img>` keine Header schicken kann, tragen Bild-URLs stattdessen eine Signatur `?t=`. Die stellt nur der Server aus: Die Fotoliste liefert pro Eintrag fertige URLs für `thumb` und `display` mit `v=` und `t=`. Signiert wird per HMAC über Foto-ID und Ablaufzeit. Der Schlüssel ist ein zufälliges Server-Geheimnis, das „Alle Kopplungen aufheben“ neu erzeugt. Die Ablaufzeit wird auf die übernächste volle Stunde gerundet. Damit bleibt eine URL innerhalb einer Stunde gleich und der Browser-Cache greift, gültig ist sie ein bis zwei Stunden.
- Bei ändernden Methoden wird `Origin` bzw. `Sec-Fetch-Site` geprüft. Fremde Origins erhalten 403. Dazu kommen `X-Content-Type-Options`, `X-Frame-Options: DENY` und eine strikte CSP für die Web-UI.
- Im Modus „nur lokal“ bindet der Server an `127.0.0.1`. Weil die Anzeige in-process arbeitet, verliert sie dadurch nichts. Der Modus wird zusammen mit den Einstellungen gespeichert, lässt sich aber nur über das Menü am Gerät schalten (Entscheidung 10) und ist nicht Teil von `PATCH /api/v1/settings`. So kann sich niemand aus dem Browser aussperren.

*Alternative:* PIN oder Passwort. Verworfen, weil es vergessen wird, im LAN abgehört werden kann und eine Verwaltung am Gerät braucht. Der Code auf dem Display beweist dagegen physische Nähe. Einen Schutz gegen ein abgehörtes Token im LAN ohne TLS bietet allerdings keine der beiden Lösungen, siehe Risiken.

### 10. Bedienung am Gerät: Hinweis-Overlay und Gerätemenü

Ein Tippen blendet den Hinweis ein: Pause/Weiter, Name, Adresse und Kopplungscode. Ein langes Drücken von 3 Sekunden in einer Ecke öffnet ein kleines natives Gerätemenü mit „nur lokal“ an/aus, „Alle Kopplungen aufheben“ (mit Bestätigung) und Info. Alle anderen Einstellungen gibt es nur in der Web-UI, damit das Gerät selbst nichts Kompliziertes zu bedienen hat.

### 11. Kiosk-Lebenszyklus

- Ein `BootReceiver` startet Service und Activity. Ein HOME-Intent-Filter bietet die App als Launcher an.
- Der Service läuft als Foreground-Service mit Notification-Channel ab API 26 und `START_STICKY`. Er hält einen `PARTIAL_WAKE_LOCK` und einen `WifiLock`.
- Die Activity setzt `FLAG_KEEP_SCREEN_ON` und immersive Flags und bringt das Vollbild bei `onSystemUiVisibilityChange` zurück.
- Ein `UncaughtExceptionHandler` protokolliert den Absturz und plant per `AlarmManager` einen Neustart in 5 Sekunden.
- Die Paket-ID ist `io.github.tycho991.openframe`, `minSdk 21`, `targetSdk` die neueste stabile Version. Die APK enthält keine nativen Bibliotheken und läuft daher auf allen ABIs.

### 12. Web-UI: Svelte 5, TypeScript, Vite

Svelte erzeugt kleine Bundles ohne Laufzeit-Framework. Das ist wichtig, weil der schwache Rahmen die Dateien ausliefert, und die Ladezeit über WLAN hängt an der Größe. Das Raster nutzt eine eigene virtuelle Liste mit Cursor-Paginierung und `loading="lazy"`. Die signierten Bild-URLs laufen nach ein bis zwei Stunden ab. Deshalb lädt die Web-UI geladene Seiten spätestens nach 50 Minuten neu, auch wenn sich die Bibliotheksversion nicht geändert hat. Scheitert ein Bild trotzdem, lädt sie seine Seite einmal nach. i18n besteht aus zwei JSON-Katalogen ohne Bibliothek. Die Sprache folgt dem Browser, eine abweichende Wahl speichert die Web-UI pro Browser in `localStorage`. Gestylt wird mit CSS-Variablen und eigenen Komponenten, ohne UI-Kit. Getestet wird mit Vitest für die Logik und mit Playwright (Chromium, Firefox, WebKit) gegen einen `:core`-Testserver mit Fake-MediaEngine.

Gradle ruft den Vite-Build als Task vor `mergeAssets` auf. Node kommt aus `.mise.toml`. Das Build-Ergebnis wird nicht eingecheckt.

*Alternativen:* React oder Vue. Verworfen, weil sie größere Bundles erzeugen und Vorteile bringen, die hier nicht zählen. Preact wäre gleichwertig, Svelte hat beim Schreiben von Formularen und Animationen weniger Boilerplate.

### 13. Werkzeuge und Qualität

- Gradle Kotlin DSL mit Version Catalog (`gradle/libs.versions.toml`), Kotlin 2.x, JDK 17 als Toolchain. Das JDK kommt über `.mise.toml`, der Android-SDK-Pfad wird dokumentiert.
- Formatierung und Lint: ktlint über Spotless für Kotlin, ESLint und Prettier für die Web-UI, Android Lint mit `minSdk`-Prüfungen.
- Tests: JUnit 5 und `kotlinx-coroutines-test` in `:core`, darunter Contract-Tests und Tests mit einem echten `ServerSocket`. In `:android` gibt es Robolectric nur für die Bildpipeline. Eine Checkliste für die Geräte (`docs/DEVICE_TEST.md`) deckt ab, was nur auf echter Hardware messbar ist: Frame-Drops, Dauerlauf, Autostart.
- CI auf GitHub Actions führt `./gradlew check :android:assembleDebug` aus, dazu Web-UI-Lint, -Tests und -Build. Renovate pflegt die Versionen, die Drift-Prüfung von OpenSpec bleibt.

## Risks / Trade-offs

- **[Token im LAN im Klartext, kein TLS]** → Die Kopplung erfordert physische Nähe. Tokens lassen sich aufheben und werden nur als Hash gespeichert. Der Modus „nur lokal“ ist der Notschalter. TLS mit selbstsigniertem Zertifikat und Pinning bei der Kopplung ist als Folge-Change denkbar. Bis dahin ist das gleiche Modell wie bei vielen Heimnetz-Geräten dokumentiert.
- **[HEIC fehlt auf API < 28]** → Solche Dateien werden in der Web-UI als „nicht unterstützt“ gezeigt statt stillschweigend zu fehlen. Eine Umwandlung beim Upload im Browser per WebAssembly kommt als Folge-Change infrage.
- **[Heap auf alten Geräten]** → Die Anzeige decodiert nur vorab gerenderte, bildschirmdeckende Größen mit Obergrenze und hält höchstens zwei Bitmaps, im schlimmsten Fall etwa 46 MB auf dem Lenovo. Reicht der Heap dort nicht, ist RGB_565 für das wartende Bitmap der Ausweg. Rendern läuft auf einem einzigen Thread mit `inSampleSize` in zwei Durchgängen. Ein Dauerlauftest auf beiden Geräten gehört zur Abnahme.
- **[Erstes Rendern einer großen Bibliothek dauert]** → Gerendert wird bei Bedarf und für die nächsten Fotos vorab, nicht alles auf einmal. Bis eine Anzeigegröße fertig ist, überspringt die Slideshow das Foto, statt zu warten. Ausnahme: Ist noch gar keine fertig, wartet sie auf das erste (Entscheidung 3).
- **[OpenAPI-Datei und Kotlin-DTOs laufen auseinander]** → Contract-Tests in CI prüfen jede Route gegen das Schema.
- **[NanoHTTPD wird kaum noch gepflegt]** → Der Transport steckt hinter der eigenen Routing-Schicht, ein Wechsel betrifft eine Datei. Die genutzte Fläche ist klein, und Range und Multipart werden selbst getestet.
- **[Node im APK-Build]** → Node ist über mise exakt gepinnt, der Lockfile ist eingecheckt und `npm ci` läuft in CI. Ohne Node schlägt der Build mit einer klaren Meldung fehl, statt eine APK ohne Web-UI zu bauen.
- **[Deinstallieren löscht die Fotos]** → Der Fotoordner liegt in `getExternalFilesDir` und gehört damit der App. Der Rückweg im Migration Plan sichert die Fotos deshalb vor dem Deinstallieren. Ein Ordner außerhalb der App bräuchte ab API 29 Scoped-Storage-Umwege und bleibt einem Folge-Change vorbehalten.
- **[Ganzer Index im Speicher]** → Bei 10.000 Fotos sind das wenige MB. Für deutlich mehr steht der Wechsel zu SQLite hinter dem Interface bereit.

## Migration Plan

1. Die APK wird zuerst auf dem Lenovo installiert, parallel zu `pictureframe`, das HOME-Launcher bleibt. Fotos kommen per USB oder Upload. Danach wird die Checkliste in `docs/DEVICE_TEST.md` abgearbeitet.
2. Ist sie grün, wird openframe auf dem Lenovo als HOME-Launcher gewählt. Es folgen ein Dauerlauf über 24 Stunden und ein Neustarttest.
3. Danach folgt dasselbe auf dem Frameo. Dort bleibt `pictureframe` installiert und wird erst entfernt, wenn openframe mit Nextcloud-Sync (Folge-Change) dessen Rolle übernehmen kann.
4. Rückweg: Den HOME-Launcher in den Android-Einstellungen bzw. per `cmd package set-home-activity` oder über den Auswahldialog wieder auf `com.pictureframe.kiosk` setzen. Dann die Fotos sichern (`adb pull /sdcard/Android/data/io.github.tycho991.openframe/files/photos`), weil das Deinstallieren sie mitlöscht, und erst danach openframe deinstallieren. Systemdateien und Root-Änderungen fasst openframe nicht an.

## Open Questions

- Soll die Release-APK mit einem eigenen Schlüssel signiert werden statt mit dem Debug-Keystore wie bei `pictureframe`? Das wird erst mit dem Self-Update-Change relevant und ändert hier nichts.
- Lizenz: übernehmen wir MIT aus `pictureframe`? Das hat keinen Einfluss auf Specs oder Tasks.
