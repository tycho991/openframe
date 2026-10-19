# Proposal

## Why

`pictureframe` (v1.2.0) funktioniert, trägt aber seine Entstehungsgeschichte mit sich. Der Code ist doppelt vorhanden: ein Node-Server mit etwa 1.900 Zeilen und ein Kotlin-Core mit etwa 5.000 Zeilen in der APK. Die Slideshow ist eine Web-App mit etwa 8.000 Zeilen Vanilla-JS, die in den alten WebViews der Zielgeräte laufen muss. Dafür braucht sie unter anderem einen `fetch()`-Polyfill, und sie kann weder flüssig animieren noch eine moderne Oberfläche bieten.

Gleichzeitig ist der wichtigste Schritt schon belegt: Der Server läuft auf dem Gerät selbst. openframe baut deshalb auf dieser Architektur neu auf, mit nur noch einer Codebasis. Die Slideshow wird nativ und hardwarebeschleunigt gerendert, sodass sie auch auf schwacher Hardware flüssig läuft. Die Verwaltungsoberfläche wird zeitgemäß und läuft im Browser von Laptop oder Handy statt auf dem Rahmen. Specs und Tests bilden von Anfang an den Vertrag.

Dieser erste Change legt das Fundament und den Kern. Er soll so geschnitten sein, dass Nextcloud, Video, Zuschnitt, Bildschirm-Zeitplan, Home Assistant und mehrere Rahmen als eigene, kleinere Changes darauf aufsetzen können.

## What Changes

- Neues Projekt in Kotlin mit Coroutines und `kotlinx.serialization`. Es gibt ein plattformneutrales Modul `core` ohne `android.*`-Importe und ein Modul `android` für die Kiosk-APK. Einen Node-Server gibt es nicht mehr.
- **Native Slideshow auf dem Gerät** statt WebView: Die Bilder werden mit hardwarebeschleunigten Android-Views dargestellt, mit Überblendung und einer Uhr als Overlay. Die Decodierung läuft gedrosselt und mit vorab heruntergerechneten Bildern, damit der Speicher auf Geräten mit 1 GB RAM und API 21 reicht.
- **Lokale Fotobibliothek**: Fotos kommen per USB-Ordner oder Upload hinein. Ein Index mit stabilen IDs und Album-Ordnern bildet die Grundlage, auf der später Nextcloud und weitere Quellen aufsetzen.
- **Eingebetteter HTTP-Server** auf dem Gerät. Er liefert eine versionierte JSON-API unter `/api/v1` und die Verwaltungsoberfläche aus.
- **Moderne Verwaltungsoberfläche** mit TypeScript, Komponenten und Build-Step, gebaut für aktuelle Browser auf Laptop und Handy. Sie bietet Fotoübersicht, Upload, Alben ein- und ausblenden, die Einstellungen der Slideshow und die Verwaltung der gekoppelten Geräte.
- **Zugriffsschutz**: Neu ist eine Kopplung per Code, der auf dem Rahmen angezeigt wird. Danach verwaltet ein Token den Zugriff, statt einer völlig offenen API im LAN. Zusätzlich gibt es am Rahmen einen Schalter, der den Server nur lokal erreichbar macht.
- **Kiosk-Betrieb**: Die App ist HOME-Launcher, startet nach dem Boot von selbst, läuft im Vollbild, hält das Display wach und betreibt den Server in einem Foreground-Service.
- **Kennung pro Rahmen**: Jeder Rahmen erhält eine eindeutige ID und einen Namen. Damit kann ein späterer Change für mehrere Rahmen ohne Umbau darauf aufsetzen.
- Werkzeuge für Gradle-Build, CI mit Tests für `core`, Web-UI und APK sowie eine Prüfung, dass `core` keine Android-Importe enthält.

Nicht Teil dieses Changes und als eigene Folge-Changes geplant:

- Nextcloud-Sync
- Video
- Crop-Editor
- Bildschirm-Zeitplan und Auto-Helligkeit
- Home Assistant
- Verwaltung mehrerer Rahmen
- Self-Update
- Reverse-Geocoding
- Übernahme der Daten aus `pictureframe`

## Capabilities

### New Capabilities

- `slideshow-display`: Native Darstellung der Fotos auf dem Rahmen: Reihenfolge, Intervall, Übergänge, Füllmodus, Ausrichtung, Uhr-Overlay, Bedienung am Rahmen mit Hinweis und Gerätemenü, Leerzustand und Speicherverhalten auf schwacher Hardware.
- `photo-library`: Lokale Fotos und Alben. Dazu gehören Import per Ordner und Upload, Index mit stabilen IDs, Erkennung von Änderungen, Liste übersprungener Dateien, Ausblenden, Löschen und gerenderte Bildgrößen mit Speicherbudget.
- `frame-settings`: Einstellungsmodell des Rahmens mit Defaults, Validierung und Persistenz, darunter Ausrichtung, Sprache am Rahmen und Cache-Budget, dazu die Identität des Rahmens (ID, Name).
- `admin-access`: Erreichbarkeit der API im LAN, Kopplung per Code, Tokens und der Modus „nur lokal“.
- `web-admin`: Verwaltungsoberfläche im Browser für Fotos, Alben, Einstellungen und gekoppelte Geräte.
- `kiosk-host`: Betrieb der App als Kiosk auf Android: Autostart, HOME-Launcher, Vollbild, Display wach halten und Lebenszyklus des Servers.

### Modified Capabilities

Keine. Das Projekt hat noch keine Specs.

## Impact

- Neues Repository `openframe` mit `settings.gradle.kts` und den Modulen `:core` und `:android`, dem API-Vertrag `api/openapi.yaml` und `webadmin/` für den Node-Build der Verwaltungsoberfläche, deren Ergebnis in die APK-Assets gelangt.
- Abhängigkeiten: Kotlin, `kotlinx-coroutines`, `kotlinx-serialization`, ein eingebetteter HTTP-Server (siehe design.md) und ein Frontend-Toolchain über Node, gepinnt über `.mise.toml`.
- Zielgeräte: Frameo `PFF_1024` (Android 6.0.1, armeabi-v7a) und Lenovo Yoga Tablet 2 1050F (Android 5.0.1, x86). Das bedeutet `minSdk 21`.
- Neue Paket-ID. Dadurch kann openframe parallel zu `com.pictureframe.kiosk` installiert und getestet werden, bevor es den alten Rahmen ablöst.
- Die API ist zur alten `pictureframe`-API nicht kompatibel. **BREAKING** gilt nur für Werkzeuge, die gegen die alte API gebaut sind, zum Beispiel die Home-Assistant-Integration. Sie wird in einem Folge-Change neu angebunden.
