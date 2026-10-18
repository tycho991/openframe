# Spec Delta

## Purpose

Bietet eine zeitgemäße Verwaltungsoberfläche im Browser von Laptop oder Handy. Damit lassen sich Fotos, Alben und Einstellungen eines Rahmens pflegen, ohne am Rahmen selbst etwas bedienen zu müssen.

## ADDED Requirements

### Requirement: Auslieferung durch den Rahmen
Die Verwaltungsoberfläche MUSS vom Rahmen unter `/` ausgeliefert werden und ohne Internetzugang funktionieren. Sie DARF keine Ressourcen von fremden Servern laden.

#### Scenario: Offline im Heimnetz
- **WHEN** das Heimnetz keinen Internetzugang hat und der Nutzer die Adresse des Rahmens öffnet
- **THEN** lädt die Verwaltungsoberfläche vollständig

### Requirement: Unterstützte Browser
Die Verwaltungsoberfläche MUSS in den jeweils zwei aktuellen Hauptversionen von Firefox, Chrome und Safari auf Desktop und Mobilgeräten funktionieren. Sie MUSS ab 360 px Breite ohne horizontales Scrollen bedienbar sein.

#### Scenario: Handy
- **WHEN** der Nutzer die Oberfläche auf einem Handy mit 390 px Breite öffnet
- **THEN** sind alle Funktionen ohne horizontales Scrollen erreichbar

### Requirement: Kopplung in der Oberfläche
Ohne gültiges Token MUSS die Oberfläche den Kopplungsdialog zeigen. Nach erfolgreicher Kopplung MUSS sie das Token im Browser speichern. Bei einer 401-Antwort MUSS sie zum Kopplungsdialog zurückkehren.

#### Scenario: Erster Besuch
- **WHEN** ein Browser die Oberfläche zum ersten Mal öffnet
- **THEN** sieht der Nutzer den Kopplungsdialog mit dem Hinweis, wo der Code am Rahmen erscheint

#### Scenario: Wiederkehrender Besuch
- **WHEN** ein gekoppelter Browser die Oberfläche erneut öffnet
- **THEN** sieht der Nutzer direkt seine Fotos

### Requirement: Fotoübersicht
Die Oberfläche MUSS die Fotos als Raster mit Vorschaubildern zeigen, filterbar nach Album und Sichtbarkeit. Auch bei 10.000 Fotos MUSS das Blättern flüssig bleiben, es dürfen also nur die sichtbaren Vorschaubilder geladen werden. Ausgeblendete Fotos MÜSSEN erkennbar sein.

#### Scenario: Große Bibliothek
- **WHEN** die Bibliothek 10.000 Fotos enthält und der Nutzer durch das Raster scrollt
- **THEN** lädt die Oberfläche nur Vorschaubilder im sichtbaren Bereich und reagiert ohne spürbare Verzögerung

#### Scenario: Tab stundenlang offen
- **WHEN** die Oberfläche drei Stunden offen bleibt, ohne dass sich die Bibliothek ändert
- **THEN** zeigt das Raster weiterhin alle Vorschaubilder, ohne dass der Nutzer neu lädt

### Requirement: Übersprungene Dateien
Liegen im Fotoordner Dateien, die der Rahmen nicht aufnehmen konnte, MUSS die Oberfläche darauf hinweisen und sie mit Pfad und Grund auflisten.

#### Scenario: HEIC per USB auf altem Gerät
- **WHEN** auf einem Rahmen mit Android 6 eine HEIC-Datei per USB in den Fotoordner kopiert wurde
- **THEN** zeigt die Oberfläche einen Hinweis, und die Liste nennt die Datei mit dem Grund „nicht unterstützt“

### Requirement: Fotos bearbeiten
In der Oberfläche MÜSSEN einzelne oder mehrere ausgewählte Fotos ein- und ausgeblendet und gelöscht werden können. Das Löschen MUSS vorher bestätigt werden. Eine Detailansicht MUSS das Foto größer mit Album, Aufnahmedatum und Maßen zeigen.

#### Scenario: Mehrere ausblenden
- **WHEN** der Nutzer drei Fotos auswählt und „Ausblenden“ wählt
- **THEN** sind alle drei als ausgeblendet markiert und erscheinen nicht mehr in der Slideshow

#### Scenario: Löschen bestätigen
- **WHEN** der Nutzer „Löschen“ wählt
- **THEN** fragt die Oberfläche nach Bestätigung und löscht erst danach

### Requirement: Upload in der Oberfläche
Fotos MÜSSEN per Dateiauswahl und per Drag-and-drop hochgeladen werden können, optional in ein bestehendes oder neues Album. Der Fortschritt MUSS pro Datei sichtbar sein. Fehler MÜSSEN pro Datei gemeldet werden, ohne die übrigen Uploads abzubrechen.

#### Scenario: Teilweiser Fehler
- **WHEN** der Nutzer fünf Dateien hochlädt, von denen eine eine Textdatei ist
- **THEN** werden vier hochgeladen und für die Textdatei erscheint eine Fehlermeldung

### Requirement: Alben
Die Oberfläche MUSS die Alben mit Anzahl der Fotos und einem Titelbild zeigen und ein Album mit einem Schalter ein- und ausblenden können.

#### Scenario: Album ausblenden
- **WHEN** der Nutzer den Schalter des Albums `urlaub` ausschaltet
- **THEN** zeigt die Slideshow keine Fotos aus `urlaub` mehr

### Requirement: Einstellungen bearbeiten
Die Oberfläche MUSS alle Einstellungen bearbeiten können, die die API anbietet, und Validierungsfehler am betroffenen Feld anzeigen. Gespeicherte Änderungen MÜSSEN ohne Neuladen bestätigt werden.

#### Scenario: Fehler am Feld
- **WHEN** der Nutzer ein ungültiges Intervall speichert
- **THEN** erscheint die Fehlermeldung am Feld Intervall und nichts wird gespeichert

### Requirement: Gekoppelte Geräte
Die Oberfläche MUSS die gekoppelten Geräte auflisten und das Aufheben einer Kopplung ermöglichen. Das aktuelle Gerät MUSS dabei erkennbar sein.

#### Scenario: Altes Gerät entfernen
- **WHEN** der Nutzer bei einem anderen Gerät „Kopplung aufheben“ wählt
- **THEN** verschwindet es aus der Liste und das aktuelle Gerät bleibt gekoppelt

### Requirement: Sprache
Die Oberfläche MUSS Deutsch und Englisch unterstützen und die Sprache aus dem Browser übernehmen, solange der Nutzer keine andere wählt. Die Wahl gilt pro Browser und ist unabhängig von der Sprache am Rahmen.

#### Scenario: Deutscher Browser
- **WHEN** der Browser Deutsch als bevorzugte Sprache meldet
- **THEN** erscheint die Oberfläche auf Deutsch

### Requirement: Aktueller Stand
Die Oberfläche MUSS Änderungen, die anderswo vorgenommen wurden, etwa per USB oder von einem zweiten Browser, spätestens nach 30 Sekunden anzeigen, ohne dass der Nutzer neu lädt.

#### Scenario: Foto per USB
- **WHEN** die Oberfläche offen ist und ein Foto per USB hinzukommt
- **THEN** erscheint es spätestens nach 30 Sekunden im Raster
