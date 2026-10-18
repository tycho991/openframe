# Spec Delta

## Purpose

Hält die Einstellungen und die Identität eines Rahmens. Sie sind validiert, überstehen Neustarts und Updates und lassen sich über die API lesen und ändern.

## ADDED Requirements

### Requirement: Einstellungsmodell mit Defaults
Der Rahmen MUSS für jede Einstellung einen dokumentierten Standardwert haben. Fehlt eine Einstellung in den gespeicherten Daten, etwa nach einem Update mit neuen Einstellungen, MUSS der Standardwert gelten, ohne dass vorhandene Werte verloren gehen.

#### Scenario: Erster Start
- **WHEN** die App ohne gespeicherte Einstellungen startet
- **THEN** gelten alle Standardwerte und die Slideshow läuft

#### Scenario: Update mit neuer Einstellung
- **WHEN** eine neue App-Version eine zusätzliche Einstellung einführt
- **THEN** behält der Rahmen alle bisherigen Werte und die neue Einstellung hat ihren Standardwert

### Requirement: Umfang der Einstellungen
Die Einstellungen MÜSSEN mindestens umfassen: Intervall (5 s bis 24 h, Standard 30 s), Reihenfolge, Übergang und Übergangsdauer (0 bis 5000 ms), Füllmodus, Ausrichtung (`landscape` Standard, `portrait`), Uhr (an/aus, Position, Größe 50 bis 300 %, 12/24 h), Sprache am Rahmen (`auto` für die Systemsprache, `de`, `en`) für das Datum der Uhr und alle Texte am Rahmen, Name des Rahmens und Budget für zwischengespeicherte Bildgrößen (100 bis 10.000 MB, Standard 1.000 MB). Der Modus „nur lokal“ ist keine Einstellung der API, siehe `admin-access`.

#### Scenario: Einstellungen lesen
- **WHEN** ein gekoppelter Client die Einstellungen abruft
- **THEN** enthält die Antwort alle genannten Einstellungen mit ihren aktuellen Werten

### Requirement: Validierung
Änderungen an den Einstellungen MÜSSEN vollständig validiert werden, bevor sie gespeichert werden. Ungültige Änderungen MÜSSEN mit 400 und einer Fehlerangabe pro Feld abgelehnt werden. Dabei DARF keine Einstellung teilweise übernommen werden.

#### Scenario: Ungültiges Intervall
- **WHEN** ein Client das Intervall auf 1 Sekunde und die Uhr auf aus setzt
- **THEN** antwortet die API mit 400, benennt das Feld des Intervalls, und auch die Uhr bleibt unverändert

#### Scenario: Unbekanntes Feld
- **WHEN** ein Client ein unbekanntes Feld mitschickt
- **THEN** antwortet die API mit 400 und benennt das Feld

### Requirement: Teiländerungen
Clients MÜSSEN einzelne Einstellungen ändern können, ohne alle Einstellungen mitzuschicken. Nicht genannte Einstellungen MÜSSEN unverändert bleiben.

#### Scenario: Nur Intervall ändern
- **WHEN** ein Client nur das Intervall auf 60 s setzt
- **THEN** ist das Intervall 60 s und alle anderen Einstellungen sind unverändert

### Requirement: Dauerhafte, atomare Speicherung
Gespeicherte Einstellungen MÜSSEN Neustarts und App-Updates überstehen. Ein Stromausfall während des Speicherns DARF NICHT zu beschädigten oder leeren Einstellungen führen.

#### Scenario: Neustart nach Änderung
- **WHEN** eine Einstellung geändert und das Gerät danach neu gestartet wird
- **THEN** gilt nach dem Start der geänderte Wert

#### Scenario: Stromausfall beim Schreiben
- **WHEN** der Schreibvorgang unterbrochen wird
- **THEN** gilt beim nächsten Start entweder der alte oder der neue Stand vollständig

### Requirement: Änderungen sofort wirksam
Gespeicherte Einstellungen MÜSSEN ohne Neustart wirksam werden. Die API MUSS eine Einstellungsversion liefern, an der Clients Änderungen erkennen.

#### Scenario: Übergang geändert
- **WHEN** der Übergang von `fade` auf `slide` geändert wird
- **THEN** nutzt die Slideshow beim nächsten Wechsel `slide`

### Requirement: Identität des Rahmens
Jeder Rahmen MUSS beim ersten Start eine zufällige, dauerhafte ID erhalten, die sich nie ändert. Er MUSS einen änderbaren Namen haben, Standard ist das Gerätemodell. ID, Name, App-Version, Bildschirmauflösung und Android-Version MÜSSEN ohne Kopplung abrufbar sein.

#### Scenario: Info abrufen
- **WHEN** ein Client ohne Token die Info des Rahmens abruft
- **THEN** erhält er ID, Name, App-Version, Bildschirmauflösung und Android-Version, aber keine Einstellungen

#### Scenario: Name ändern
- **WHEN** der Name auf „Wohnzimmer“ geändert wird
- **THEN** zeigt die Info den neuen Namen, und die ID ist unverändert
