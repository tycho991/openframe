# Spec Delta

## Purpose

Verwaltet die Fotos eines Rahmens: Import aus dem lokalen Ordner und per Upload, einen Index mit stabilen IDs, Alben, Sichtbarkeit und gerenderte Bildgrößen für Slideshow und Verwaltungsoberfläche.

## ADDED Requirements

### Requirement: Unterstützte Formate
Die Bibliothek MUSS JPEG, PNG, WebP und GIF aufnehmen. GIF MUSS als Standbild ins erste Frame übernommen werden. HEIC/HEIF MUSS nur dann aufgenommen werden, wenn das Gerät es decodieren kann. Andere Dateien MÜSSEN ignoriert werden, ohne den Import abzubrechen. Dateien mit Bild-Endung, die nicht aufgenommen werden können (nicht decodierbares Format oder beschädigte Datei), MÜSSEN mit relativem Pfad und Grund in einer Liste übersprungener Dateien stehen, die die API liefert.

#### Scenario: Gemischter Ordner
- **WHEN** der Fotoordner `a.jpg`, `b.png`, `notiz.txt` und `c.mp4` enthält
- **THEN** nimmt die Bibliothek `a.jpg` und `b.png` auf und ignoriert die übrigen Dateien

#### Scenario: HEIC auf altem Gerät
- **WHEN** auf einem Gerät mit Android 6 eine HEIC-Datei im Ordner liegt
- **THEN** wird sie nicht in den Index aufgenommen und erscheint in der Liste übersprungener Dateien mit dem Grund „nicht unterstützt“

### Requirement: Lokaler Fotoordner
Der Rahmen MUSS einen Fotoordner haben, der per USB (MTP) ohne Root erreichbar ist. Unterordner MÜSSEN durchsucht werden. Neue, geänderte und entfernte Dateien MÜSSEN spätestens 30 Sekunden nach der Änderung im Index stehen. Dateien, die der Rahmen selbst anlegt oder löscht, etwa per Upload, MÜSSEN danach auch per USB richtig sichtbar sein.

#### Scenario: Foto per USB kopiert
- **WHEN** ein Foto per USB in den Fotoordner kopiert wird
- **THEN** steht es spätestens nach 30 Sekunden im Index und ist sichtbar

#### Scenario: Datei entfernt
- **WHEN** eine Datei aus dem Fotoordner gelöscht wird
- **THEN** verschwindet ihr Eintrag spätestens nach 30 Sekunden aus dem Index

#### Scenario: Hochgeladenes Foto per USB
- **WHEN** ein Foto über die Verwaltungsoberfläche hochgeladen und der Rahmen danach per USB verbunden wird
- **THEN** ist das Foto im Fotoordner sichtbar

### Requirement: Stabile Foto-IDs
Jedes Foto MUSS eine ID haben, die sich aus seinem relativen Pfad ergibt und über Neustarts und Neuscans gleich bleibt. Wird eine Datei am selben Pfad ersetzt, MUSS die ID gleich bleiben, ihre Bildversion MUSS sich aber ändern, damit Caches ungültig werden.

#### Scenario: Neustart
- **WHEN** die App neu startet
- **THEN** haben alle Fotos dieselben IDs wie vorher

#### Scenario: Datei ersetzt
- **WHEN** `urlaub/1.jpg` durch eine andere Datei gleichen Namens ersetzt wird
- **THEN** behält das Foto seine ID, seine Bildversion steigt und das Ausblenden bleibt erhalten

### Requirement: Metadaten
Für jedes Foto MUSS der Index Breite und Höhe (nach EXIF-Drehung), Aufnahmedatum (EXIF, ersatzweise Dateidatum), Dateigröße, Album und Sichtbarkeit enthalten. Unveränderte Dateien DÜRFEN beim Neuscan NICHT erneut gelesen werden.

#### Scenario: Neuscan ohne Änderungen
- **WHEN** ein Neuscan läuft und sich keine Datei geändert hat
- **THEN** wird keine Bilddatei geöffnet und der Index bleibt unverändert

### Requirement: Alben aus Ordnern
Der erste Unterordner unterhalb des Fotoordners MUSS das Album eines Fotos bestimmen. Fotos direkt im Fotoordner MÜSSEN zu keinem Album gehören. Ein Album MUSS als Ganzes ausgeblendet werden können. Das MUSS auch für Fotos gelten, die später in diesen Ordner kommen.

#### Scenario: Album zuordnen
- **WHEN** `urlaub/2025/strand.jpg` importiert wird
- **THEN** gehört das Foto zum Album `urlaub`

#### Scenario: Ausgeblendetes Album bekommt neues Foto
- **WHEN** das Album `urlaub` ausgeblendet ist und ein neues Foto in `urlaub/` kopiert wird
- **THEN** ist das neue Foto ebenfalls ausgeblendet

### Requirement: Einzelne Fotos ausblenden
Einzelne Fotos MÜSSEN ausgeblendet und wieder eingeblendet werden können. Ein Foto ist für die Slideshow sichtbar, wenn weder das Foto selbst noch sein Album ausgeblendet ist.

#### Scenario: Foto ausblenden
- **WHEN** ein Foto ausgeblendet wird
- **THEN** zeigt die Slideshow es nicht mehr, es bleibt aber in der Bibliothek

### Requirement: Upload
Fotos MÜSSEN über die API hochgeladen werden können, einzeln oder mehrere, optional in ein Album. Uploads MÜSSEN auf Datenträger gestreamt werden statt im Arbeitsspeicher zu liegen. Dateinamen und Albumnamen MÜSSEN so bereinigt werden, dass keine Datei außerhalb des Fotoordners entstehen kann. Ein Albumname MUSS genau eine Ordnerebene bilden. Ein Upload, nach dem weniger als 100 MB Speicher frei blieben, MUSS abgelehnt werden.

#### Scenario: Upload in Album
- **WHEN** zwei JPEG-Dateien mit dem Album `familie` hochgeladen werden
- **THEN** liegen sie in `familie/`, stehen sofort im Index und die Antwort enthält ihre IDs

#### Scenario: Pfad-Traversal
- **WHEN** eine Datei mit dem Namen `../../evil.jpg` oder in das Album `../../x` hochgeladen wird
- **THEN** landet sie bereinigt in einem Ordner direkt unterhalb des Fotoordners oder der Upload wird mit 400 abgelehnt, und außerhalb des Fotoordners entsteht keine Datei

#### Scenario: Gleicher Name
- **WHEN** eine Datei hochgeladen wird, deren Name im Zielordner schon existiert
- **THEN** wird die vorhandene Datei nicht überschrieben, sondern die neue erhält einen eindeutigen Namen

#### Scenario: Kein unterstütztes Format
- **WHEN** eine Datei hochgeladen wird, die kein unterstütztes Format hat
- **THEN** antwortet die API mit 415 und speichert nichts

#### Scenario: Speicher voll
- **WHEN** nach einem Upload weniger als 100 MB Speicher frei blieben
- **THEN** antwortet die API mit 507, speichert nichts und lässt keine Teildatei zurück

### Requirement: Löschen
Fotos MÜSSEN über die API gelöscht werden können. Dabei MÜSSEN die Datei und alle abgeleiteten Bildgrößen entfernt werden.

#### Scenario: Foto löschen
- **WHEN** ein Foto gelöscht wird
- **THEN** sind Datei, Index-Eintrag und gerenderte Größen entfernt

### Requirement: Gerenderte Bildgrößen
Die Bibliothek MUSS für jedes Foto ein Vorschaubild (längste Kante 400 px) und eine Anzeigegröße liefern. Die Anzeigegröße MUSS den Bildschirm in der eingestellten Ausrichtung vollständig bedecken, damit auch der Füllmodus `fill` scharf ist. Sie DARF dabei höchstens das 2,5-Fache der Pixelzahl des Bildschirms haben, etwa bei Panoramen. Beide Größen MÜSSEN EXIF-gedreht sein und zwischengespeichert werden. Die Antwort MUSS über die Bildversion dauerhaft cachebar sein.

#### Scenario: Hochformatfoto auf Querformat-Rahmen
- **WHEN** auf einem Rahmen mit 1920×1200 im Format `landscape` ein Foto mit 3000×4000 Pixeln gerendert wird
- **THEN** hat die Anzeigegröße 1920×2560 Pixel

#### Scenario: Vorschaubild abrufen
- **WHEN** die Verwaltungsoberfläche das Vorschaubild eines Fotos abruft
- **THEN** erhält sie ein JPEG mit längster Kante 400 px und `Cache-Control: immutable`

#### Scenario: Wiederholter Abruf
- **WHEN** dasselbe Vorschaubild ein zweites Mal mit `If-None-Match` angefragt wird
- **THEN** antwortet die API mit 304 ohne neu zu rendern

### Requirement: Speicherplatz begrenzen
Die zwischengespeicherten Bildgrößen MÜSSEN das Budget aus den Einstellungen (`frame-settings`) einhalten. Bei Überschreitung MÜSSEN die am längsten nicht genutzten Einträge entfernt werden. Originalfotos DÜRFEN dabei NICHT gelöscht werden.

#### Scenario: Budget überschritten
- **WHEN** der Cache das Budget überschreitet
- **THEN** werden die am längsten nicht genutzten Cache-Einträge entfernt, bis er wieder darunter liegt, und alle Originale bleiben erhalten

### Requirement: Fotoliste über die API
Die API MUSS die Fotos seitenweise liefern, filterbar nach Album und Sichtbarkeit und sortierbar nach Aufnahmedatum oder Name. Jeder Eintrag MUSS ID, Album, Sichtbarkeit, Maße, Aufnahmedatum, Bildversion und vom Server signierte URLs für Vorschaubild und Anzeigegröße enthalten. Eine Bibliothek mit 10.000 Fotos MUSS dabei ohne spürbare Verzögerung blättern.

#### Scenario: Seite abrufen
- **WHEN** die Seite 2 mit 100 Einträgen aus dem Album `urlaub` abgerufen wird
- **THEN** enthält die Antwort höchstens 100 Fotos aus `urlaub`, die Gesamtzahl und einen Verweis auf die nächste Seite

### Requirement: Änderungen signalisieren
Die API MUSS eine Bibliotheksversion liefern, die sich bei jeder Änderung am Index ändert. Clients MÜSSEN damit erkennen können, ob sie neu laden müssen.

#### Scenario: Neues Foto
- **WHEN** ein Foto hinzukommt
- **THEN** liefert die API danach eine andere Bibliotheksversion als vorher
