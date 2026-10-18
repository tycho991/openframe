# Spec Delta

## Purpose

Betreibt openframe auf einem Android-Gerät als unbeaufsichtigten Bilderrahmen. Die App startet von selbst, bleibt im Vordergrund und hält Display und Server am Laufen. Sie läuft auf alten Geräten ab Android 5.0 ohne Root.

## ADDED Requirements

### Requirement: Unterstützte Geräte
Die App MUSS auf Android 5.0 (API 21) und neuer laufen, auf den Architekturen armeabi-v7a, arm64-v8a, x86 und x86_64. Sie DARF für den Grundbetrieb keinen Root-Zugriff voraussetzen.

#### Scenario: Lenovo Yoga Tablet 2
- **WHEN** die APK auf dem Lenovo Yoga Tablet 2 1050F (Android 5.0.1, x86) installiert wird
- **THEN** startet sie und zeigt die Slideshow

#### Scenario: Frameo
- **WHEN** die APK auf dem Frameo `PFF_1024` (Android 6.0.1, armeabi-v7a) installiert wird
- **THEN** startet sie und zeigt die Slideshow

### Requirement: Autostart
Die App MUSS nach dem Hochfahren des Geräts ohne Eingriff starten und die Slideshow zeigen. Sie MUSS sich als HOME-Launcher anbieten, damit die Home-Taste zur Slideshow führt.

#### Scenario: Neustart
- **WHEN** das Gerät neu startet
- **THEN** zeigt es ohne Eingriff die Slideshow, sofern keine Bildschirmsperre eingerichtet ist

#### Scenario: Home-Taste
- **WHEN** die App HOME-Launcher ist und jemand die Home-Taste drückt
- **THEN** erscheint die Slideshow

### Requirement: Vollbild
Die Slideshow MUSS im immersiven Vollbild ohne Status- und Navigationsleiste laufen. Nach einer Systemeinblendung MUSS sie das Vollbild selbst wiederherstellen.

#### Scenario: Leiste eingeblendet
- **WHEN** jemand vom Rand wischt und die Systemleisten erscheinen
- **THEN** blendet die App sie nach wenigen Sekunden wieder aus

### Requirement: Display wach halten
Solange die Slideshow läuft, DARF das Gerät NICHT in den Standby gehen oder das Display abschalten.

#### Scenario: Stundenlang ohne Berührung
- **WHEN** niemand das Gerät 12 Stunden berührt
- **THEN** ist das Display weiterhin an und die Slideshow läuft

### Requirement: Server unabhängig von der Anzeige
Der Server MUSS in einem Foreground-Service laufen, damit er erreichbar bleibt, wenn die Anzeige im Hintergrund ist oder neu gestartet wird. Die Anzeige MUSS erst laden, wenn der Core im Service bereit ist, also Einstellungen und Bibliothek geladen sind. Vom HTTP-Server und vom Netzwerk DARF sie NICHT abhängen.

#### Scenario: Kaltstart
- **WHEN** die App startet
- **THEN** zeigt die Anzeige keine Fehlermeldung wegen eines noch nicht bereiten Cores, sondern das erste Foto oder den Leerzustand, auch ohne WLAN

#### Scenario: Anzeige im Hintergrund
- **WHEN** eine andere App in den Vordergrund kommt
- **THEN** bleibt die Verwaltungsoberfläche aus dem LAN erreichbar

### Requirement: Selbstheilung
Stürzt die Anzeige oder der Server ab, MUSS die App sich innerhalb von 30 Sekunden selbst neu starten. Die Ursache MUSS protokolliert werden.

#### Scenario: Absturz der Anzeige
- **WHEN** die Anzeige wegen eines Fehlers beendet wird
- **THEN** läuft die Slideshow spätestens 30 Sekunden später wieder

### Requirement: Netzwerk ist optional
Die Slideshow MUSS ohne Netzwerk weiterlaufen. Fällt das WLAN aus und kehrt zurück, MUSS der Server ohne Neustart wieder erreichbar sein.

#### Scenario: WLAN-Ausfall
- **WHEN** das WLAN für eine Stunde ausfällt
- **THEN** läuft die Slideshow durch und nach Rückkehr des WLANs ist die Verwaltungsoberfläche wieder erreichbar

### Requirement: Diagnose
Die App MUSS ein rotierendes Protokoll mit begrenzter Größe auf dem Gerät führen. Ein gekoppelter Client MUSS es über die API abrufen können.

#### Scenario: Protokoll abrufen
- **WHEN** ein gekoppelter Client das Protokoll abruft
- **THEN** erhält er die letzten Einträge mit Zeitstempel und Stufe

### Requirement: Parallelbetrieb mit pictureframe
Die App MUSS eine eigene Paket-ID haben, damit sie neben `com.pictureframe.kiosk` installiert werden kann, ohne dessen Daten zu berühren.

#### Scenario: Beide installiert
- **WHEN** openframe auf einem Gerät installiert wird, auf dem pictureframe läuft
- **THEN** bleiben pictureframe und seine Daten unverändert
