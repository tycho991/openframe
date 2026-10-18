# Spec Delta

## Purpose

Zeigt die sichtbaren Fotos der Bibliothek auf dem Rahmen als flüssige Vollbild-Slideshow an, auch auf alter, schwacher Android-Hardware.

## ADDED Requirements

### Requirement: Fotos im Vollbild wechseln
Der Rahmen MUSS die sichtbaren Fotos der Bibliothek nacheinander im Vollbild anzeigen und nach dem eingestellten Intervall zum nächsten Foto wechseln. Nach dem letzten Foto MUSS die Slideshow von vorn beginnen.

#### Scenario: Wechsel nach Intervall
- **WHEN** das Intervall auf 30 Sekunden steht und ein Foto 30 Sekunden angezeigt wurde
- **THEN** zeigt der Rahmen das nächste sichtbare Foto

#### Scenario: Umlauf
- **WHEN** das letzte Foto der Reihenfolge angezeigt wurde und das Intervall abläuft
- **THEN** beginnt die Slideshow wieder mit dem ersten Foto der Reihenfolge

### Requirement: Reihenfolge
Der Rahmen MUSS die Reihenfolge `shuffle` (Standard) und `chronological` (nach Aufnahmedatum, ersatzweise Dateidatum) unterstützen. Bei `shuffle` MUSS jedes sichtbare Foto einmal pro Durchgang gezeigt werden, bevor sich eines wiederholt.

#### Scenario: Shuffle ohne Wiederholung im Durchgang
- **WHEN** 10 Fotos sichtbar sind und die Reihenfolge `shuffle` ist
- **THEN** erscheint in den ersten 10 Wechseln jedes Foto genau einmal

#### Scenario: Chronologisch
- **WHEN** die Reihenfolge `chronological` ist
- **THEN** erscheinen die Fotos aufsteigend nach Aufnahmedatum

### Requirement: Übergänge
Der Wechsel MUSS mit dem eingestellten Übergang erfolgen: `fade` (Überblendung, Standard), `slide` oder `none`. Die Übergangsdauer MUSS einstellbar sein. Bei einer Übergangsdauer von 0 MUSS sofort gewechselt werden.

#### Scenario: Überblendung
- **WHEN** der Übergang `fade` mit 1500 ms eingestellt ist
- **THEN** blendet das neue Foto über 1500 ms über das alte, ohne schwarzes Zwischenbild

### Requirement: Flüssige Darstellung auf schwacher Hardware
Übergänge MÜSSEN auf den Referenzgeräten (Frameo `PFF_1024`, Android 6.0.1; Lenovo Yoga Tablet 2 1050F, Android 5.0.1) ohne sichtbares Ruckeln laufen. Das Decodieren des nächsten Fotos DARF den laufenden Übergang NICHT verzögern.

#### Scenario: Nächstes Foto ist vorbereitet
- **WHEN** ein Wechsel fällig wird
- **THEN** ist das nächste Foto bereits decodiert und der Übergang beginnt ohne Wartezeit

#### Scenario: Bildrate während eines Übergangs
- **WHEN** auf einem Referenzgerät eine Überblendung läuft
- **THEN** fallen während des Übergangs höchstens 5 % der Frames aus (gemessen mit `dumpsys gfxinfo` oder gleichwertig)

### Requirement: Speicherbegrenzung
Die Slideshow MUSS mit dem Heap der Referenzgeräte auskommen. Fotos MÜSSEN in Bildschirmauflösung statt in Originalgröße in den Speicher geladen werden, und es DÜRFEN höchstens das aktuelle und das nächste Foto als Bitmap gehalten werden.

#### Scenario: Großes Foto
- **WHEN** ein Foto mit 48 Megapixeln angezeigt wird
- **THEN** stürzt die App nicht ab und das Foto erscheint in Bildschirmauflösung

#### Scenario: Dauerlauf
- **WHEN** die Slideshow 24 Stunden mit dem Intervall 5 Sekunden läuft
- **THEN** wächst der Speicherverbrauch nicht stetig an und die App läuft weiter

### Requirement: Füllmodus
Der Rahmen MUSS die Füllmodi `fill` (bildschirmfüllend, beschnitten, Standard) und `fit` (vollständig sichtbar, mit Rändern) unterstützen. Das Seitenverhältnis des Fotos DARF dabei NICHT verzerrt werden.

#### Scenario: Hochformat bei fill
- **WHEN** ein Hochformatfoto auf einem Querformat-Display im Modus `fill` angezeigt wird
- **THEN** füllt das Foto die Breite aus, oben und unten wird mittig beschnitten

#### Scenario: Hochformat bei fit
- **WHEN** dasselbe Foto im Modus `fit` angezeigt wird
- **THEN** ist das ganze Foto sichtbar und links und rechts bleiben Ränder

### Requirement: Ausrichtung des Rahmens
Der Rahmen MUSS im eingestellten Format laufen, `landscape` (Standard) oder `portrait`. Innerhalb des Formats DARF er dem Lagesensor folgen, also um 180° drehen. Ein Wechsel des Formats MUSS ohne Neustart der App wirksam werden.

#### Scenario: Auf Hochformat umstellen
- **WHEN** die Ausrichtung von `landscape` auf `portrait` geändert wird
- **THEN** läuft die Slideshow ohne Neustart im Hochformat weiter, und die Fotos werden für das neue Format neu gerendert

### Requirement: Ausrichtung aus EXIF
Fotos MÜSSEN entsprechend ihrer EXIF-Orientierung gedreht angezeigt werden.

#### Scenario: Gedrehtes Handyfoto
- **WHEN** ein Foto die EXIF-Orientierung 6 (90° im Uhrzeigersinn) hat
- **THEN** erscheint es aufrecht

### Requirement: Uhr-Overlay
Der Rahmen MUSS eine abschaltbare Uhr mit Datum über dem Foto anzeigen. Position (vier Ecken), Größe, 12- oder 24-Stunden-Format und die Sprache des Datums MÜSSEN einstellbar sein. Die Uhr MUSS auf hellen wie dunklen Fotos lesbar sein.

#### Scenario: Minutenwechsel
- **WHEN** die Uhr eingeschaltet ist und eine neue Minute beginnt
- **THEN** zeigt die Uhr spätestens eine Sekunde danach die neue Uhrzeit

#### Scenario: Uhr aus
- **WHEN** die Uhr in den Einstellungen ausgeschaltet wird
- **THEN** verschwindet sie ohne Neustart der App

### Requirement: Bedienung am Rahmen
Ein Wischen nach links oder rechts MUSS zum nächsten oder vorherigen Foto wechseln. Ein Tippen MUSS für einige Sekunden einen Hinweis einblenden, mit Pause/Weiter, dem Namen des Rahmens, der Adresse der Verwaltungsoberfläche und einem gültigen Kopplungscode.

#### Scenario: Zurückwischen
- **WHEN** der Nutzer nach rechts wischt
- **THEN** zeigt der Rahmen das vorherige Foto und das Intervall beginnt neu

#### Scenario: Pause
- **WHEN** der Nutzer pausiert
- **THEN** bleibt das aktuelle Foto stehen, bis er die Wiedergabe fortsetzt

### Requirement: Leerzustand
Gibt es keine sichtbaren Fotos, MUSS der Rahmen einen Hinweis zeigen, wie man Fotos hinzufügt. Dazu gehören die Adresse der Verwaltungsoberfläche und der Kopplungscode, solange noch kein Gerät gekoppelt ist.

#### Scenario: Neuer Rahmen
- **WHEN** die App zum ersten Mal ohne Fotos startet
- **THEN** zeigt der Rahmen die Adresse der Verwaltungsoberfläche und den Kopplungscode

### Requirement: Änderungen ohne Neustart übernehmen
Neue, ausgeblendete oder gelöschte Fotos und geänderte Einstellungen MÜSSEN spätestens beim nächsten Wechsel wirksam werden, ohne dass die App neu startet.

#### Scenario: Album ausgeblendet
- **WHEN** ein Album in der Verwaltungsoberfläche ausgeblendet wird
- **THEN** erscheint ab dem nächsten Wechsel kein Foto dieses Albums mehr

#### Scenario: Aktuelles Foto gelöscht
- **WHEN** das gerade angezeigte Foto gelöscht wird
- **THEN** wechselt der Rahmen sofort zum nächsten Foto

### Requirement: Fehlerhafte Dateien überspringen
Lässt sich ein Foto nicht decodieren, MUSS der Rahmen es überspringen und das nächste anzeigen. Das fehlerhafte Foto DARF die Slideshow NICHT anhalten.

#### Scenario: Defekte Datei
- **WHEN** das nächste Foto eine beschädigte JPEG-Datei ist
- **THEN** zeigt der Rahmen stattdessen das darauf folgende Foto und protokolliert den Fehler
