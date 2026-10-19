# Spec Delta

## Purpose

Legt fest, wer den Rahmen über das Netzwerk verwalten darf. Ein Client wird mit einem Code gekoppelt, der auf dem Rahmen angezeigt wird. Danach weist er sich mit einem Token aus. Ein Modus „nur lokal“ schließt das Netzwerk ganz aus.

## ADDED Requirements

### Requirement: Erreichbarkeit im LAN
Der Server des Rahmens MUSS im lokalen Netzwerk auf einem festen Port (Standard 8080) erreichbar sein, solange der Modus „nur lokal“ aus ist. Die Adresse MUSS auf dem Rahmen ablesbar sein.

#### Scenario: Adresse anzeigen
- **WHEN** der Nutzer auf den Rahmen tippt
- **THEN** zeigt der Hinweis die Adresse der Verwaltungsoberfläche, z. B. `http://192.168.100.215:8080/`

### Requirement: Geschützte Endpunkte
Alle Endpunkte, die Fotos oder Einstellungen lesen oder ändern, MÜSSEN ein gültiges Token verlangen. Ohne gültiges Token MÜSSEN sie mit 401 antworten. Frei zugänglich DÜRFEN nur die Info des Rahmens, die Kopplung (einschließlich der Anforderung, einen Code am Rahmen anzuzeigen) und die statischen Dateien der Verwaltungsoberfläche sein. Bildabrufe DÜRFEN statt des Tokens eine vom Server ausgestellte Signatur tragen, die nur für dieses eine Foto und höchstens zwei Stunden gilt.

#### Scenario: Ohne Token
- **WHEN** ein Client ohne Token die Fotoliste abruft
- **THEN** antwortet die API mit 401

#### Scenario: Mit Token
- **WHEN** ein gekoppelter Client mit seinem Token die Fotoliste abruft
- **THEN** antwortet die API mit 200

#### Scenario: Signierte Bild-URL
- **WHEN** ein Browser eine Bild-URL aus der Fotoliste ohne Token abruft
- **THEN** liefert die API das Bild, solange die Signatur gültig ist

#### Scenario: Signatur auf anderes Foto übertragen
- **WHEN** ein Client die Signatur eines Fotos an die URL eines anderen Fotos hängt
- **THEN** antwortet die API mit 401

### Requirement: Kopplung per Code
Ein Client MUSS sich koppeln können, indem er einen Code eingibt, den der Rahmen anzeigt. Der Code MUSS sechs Ziffern haben, 5 Minuten gelten und nur einmal verwendbar sein. Bei erfolgreicher Kopplung MUSS der Client ein Token erhalten. Läuft ein Code ab, während der Rahmen ihn zeigt, MUSS der Rahmen ihn durch einen neuen ersetzen.

#### Scenario: Erfolgreiche Kopplung
- **WHEN** der Nutzer in der Verwaltungsoberfläche „Koppeln“ wählt und den auf dem Rahmen angezeigten Code eingibt
- **THEN** erhält der Browser ein Token und hat danach Zugriff auf Fotos und Einstellungen

#### Scenario: Code abgelaufen
- **WHEN** ein Code nach mehr als 5 Minuten eingegeben wird
- **THEN** wird die Kopplung abgelehnt und der Rahmen zeigt auf Anfrage einen neuen Code

### Requirement: Gerätename bei der Kopplung
Ein gekoppelter Client MUSS unter einem Gerätenamen in der Liste der gekoppelten Geräte erscheinen. Den Namen gibt der Client an, ersatzweise MUSS er aus dem User-Agent abgeleitet werden.

#### Scenario: Name vom Client
- **WHEN** ein Client mit dem Gerätenamen „Laptop“ koppelt
- **THEN** erscheint er in der Liste der gekoppelten Geräte als „Laptop“

#### Scenario: Name aus dem User-Agent
- **WHEN** ein Client ohne Gerätenamen koppelt
- **THEN** erscheint er in der Liste mit einem aus dem User-Agent abgeleiteten Namen, z. B. „Firefox auf Android“

### Requirement: Code am Rahmen anfordern
Ein Client ohne Token MUSS den Rahmen auffordern können, einen gültigen Code anzuzeigen. Die Antwort der API DARF den Code NICHT enthalten.

#### Scenario: Code am Rahmen anfordern
- **WHEN** der Nutzer im Kopplungsdialog „Code am Rahmen anzeigen“ wählt
- **THEN** zeigt der Rahmen für 5 Minuten einen gültigen Code, und die Antwort der API enthält den Code nicht

### Requirement: Schutz vor Durchprobieren
Nach 5 falschen Codes MUSS der Rahmen den aktuellen Code verwerfen und weitere Kopplungsversuche für 60 Sekunden ablehnen.

#### Scenario: Zu viele Fehlversuche
- **WHEN** fünfmal ein falscher Code eingegeben wird
- **THEN** antwortet die Kopplung 60 Sekunden lang mit 429 und danach gilt ein neuer Code

### Requirement: Token verwalten
Ein gekoppelter Client MUSS die Liste der gekoppelten Geräte sehen und einzelne Kopplungen aufheben können. Ein aufgehobenes Token MUSS sofort ungültig sein. Tokens MÜSSEN auf dem Rahmen nur als Hash gespeichert werden.

#### Scenario: Kopplung aufheben
- **WHEN** der Nutzer die Kopplung seines alten Laptops aufhebt
- **THEN** erhält der alte Laptop beim nächsten Aufruf 401

### Requirement: Alle Kopplungen am Rahmen zurücksetzen
Am Rahmen selbst MUSS es eine Möglichkeit geben, alle Kopplungen aufzuheben, ohne einen gekoppelten Client zu brauchen. Die Aktion MUSS bestätigt werden.

#### Scenario: Zugriff verloren
- **WHEN** der Nutzer am Rahmen „Alle Kopplungen aufheben“ wählt und bestätigt
- **THEN** sind alle Tokens und alle ausgestellten Bild-Signaturen ungültig und der Rahmen zeigt einen neuen Kopplungscode

### Requirement: Modus „nur lokal“
Ist der Modus „nur lokal“ an, DARF der Server NICHT über das Netzwerk erreichbar sein, sondern nur vom Gerät selbst. Der Modus MUSS dauerhaft gespeichert werden und DARF NUR am Rahmen selbst ein- und ausgeschaltet werden, nicht über die API. So kann sich niemand aus dem Browser aussperren.

#### Scenario: Nur lokal an
- **WHEN** der Modus „nur lokal“ eingeschaltet ist
- **THEN** ist der Port aus dem LAN nicht erreichbar und die Slideshow läuft weiter

#### Scenario: Am Rahmen ausschalten
- **WHEN** der Nutzer am Rahmen den Modus „nur lokal“ ausschaltet
- **THEN** ist die Verwaltungsoberfläche wieder aus dem LAN erreichbar

#### Scenario: Über die API
- **WHEN** ein gekoppelter Client versucht, den Modus „nur lokal“ über die Einstellungen zu setzen
- **THEN** antwortet die API mit 400 und der Modus bleibt unverändert

### Requirement: Sicherheitsheader
Alle Antworten MÜSSEN Header setzen, die das Einbetten in fremde Seiten und MIME-Sniffing verhindern. Ändernde Anfragen von fremden Origins MÜSSEN abgelehnt werden.

#### Scenario: Fremde Seite
- **WHEN** eine Seite auf einem anderen Origin per Browser eine ändernde Anfrage an den Rahmen schickt
- **THEN** lehnt der Server sie mit 403 ab
