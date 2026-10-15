# Verschlüsseltes Backup, Version 1

Dateiendung: `.sabackup`. Alle Ganzzahlen im Textteil sind JSON-Zahlen. Android und die vorhandene SwiftUI-iOS-App verwenden dasselbe Format.

| Bytebereich | Inhalt |
| --- | --- |
| 0–7 | ASCII `SALM0001` |
| 8–23 | zufälliges 16-Byte-Salt |
| 24–35 | zufällige 12-Byte-GCM-Nonce |
| ab 36 | AES-256-GCM-Chiffrat mit angehängtem 16-Byte-Authentifizierungstag |

Der Schlüssel wird mittels PBKDF2-HMAC-SHA256 mit 600.000 Iterationen abgeleitet. Das Passwort wird gemäß der Java-PBEKeySpec/PBKDF2-Implementierung verarbeitet; die iOS-Implementierung gleicht insbesondere Unicode-Passwörter mit Android/Java-Testvektoren ab. Die acht Magic-Bytes sind Additional Authenticated Data. Export erfordert mindestens acht Passwortzeichen.

Der entschlüsselte Inhalt ist ein ZIP mit genau `data.json` und den in dessen Dokumentenliste referenzierten `documents/<uuid>.(pdf|jpg|png)`. JSON enthält Profile, Fächer, Leistungsnachweise, offizielle Ergebnisse, Stundenpläne, freie Tage, Ausnahmen, Lernphasen, Dokumentmetadaten, zugestellte Hinweise und Abschlussangaben. Jede Referenz wird vor der Wiederherstellung validiert.

Authentifizierung, ZIP-Pfadprüfung, Größenprüfung, Schema-/Referenzvalidierung und Vollständigkeitsprüfung erfolgen vor einer Änderung der bestehenden Daten. Dateien erhalten bei der Wiederherstellung neue interne Namen. Auf Android wird der Datenstand nach erfolgreicher Dateibereitstellung in einer Room-Transaktion ersetzt; auf iOS werden die Dokumente vorbereitet und der validierte JSON-Datenstand atomar geschrieben. Schlägt die iOS-Wiederherstellung vor dem JSON-Schreibvorgang fehl, werden die neu bereitgestellten Dateien entfernt. Nicht mehr referenzierte Dateien werden anschließend entfernt.

Grenzen: 40 MiB pro Dokument, 80 MiB Dokumentinhalt pro Export, 160 MiB verschlüsseltes beziehungsweise entpacktes Eingabelimit. Laufende Lernphasen aus einem Backup werden beendet und zur Zeitkorrektur markiert, da monotone Uhren zwischen Geräten nicht vergleichbar sind.

Unterrichtseinträge können zusätzlich `slot` (Position im Tagesplan, 1–30) und `isBreak` enthalten. Eine Pause hat keinen Fachverweis und zählt weder als Fach noch als Lerntermin. Ältere Einträge ohne diese Felder werden mit `slot = 0` und `isBreak = false` gelesen. Beim Öffnen des alten Stundenplans werden Positionen aus den Uhrzeiten aufgebaut; zusammenhängende 90-Minuten-Blöcke erscheinen als zwei benachbarte 45-Minuten-Stunden und zählen weiterhin gemeinsam als ein Termin.

Seit 0.1.2 werden zeitliche Lücken im Stundenplan als Pausen angezeigt, aber nicht als zusätzliche Unterrichtseinträge gespeichert. Bestehende Unterrichtszeiten bleiben erhalten. Das gemeinsame Lernstoff-/Notizfeld zeigt ältere `material`- und `notes`-Inhalte zusammen an; beim Speichern wandert der vollständige Text nach `material`, `notes` wird geleert. Beide gespeicherten Felder bleiben für ältere Backups lesbar. Bestehende Prüfungszeiten bleiben bei Änderungen am selben Datum erhalten; bei einem neuen Datum wird keine alte, unsichtbare Uhrzeit übernommen.

Ab 0.1.3 kennzeichnet `catalogVersion` die einmalige Fachlisten-Aktualisierung. „Ethik“ wird unter Beibehaltung der Fach-ID zu „Ethik/Religion“, sofern der Zielname nicht bereits im selben Profil vorkommt; in bayerischen Realschulprofilen wird fehlendes IT ergänzt. Bereits vorhandenes IT wird nicht dupliziert und später gelöschtes IT nicht erneut angelegt. Einschätzungsfragen nutzen datierte `estimate:`- und `estimate-skip:`-Schlüssel in `delivered`; Antworten bleiben getrennt in `estimate`.

## Vokabeltrainer ab Android 0.1.28

Der JSON-Datenstand enthält zusätzlich `vocabularyCollections`, `vocabularyCards`, `vocabularyProgress` und `vocabularySessions`. Sammlungen und Runden gehören zu einem Schulprofil; Fortschritt, offene Wörter, Antwortzähler und die zuletzt gespeicherte Rückmeldung werden gemeinsam gesichert. Eine wiederhergestellte Rückmeldung wird nicht erneut als Antwort gewertet. Das damalige Backupformat blieb `SALM0001`, die Datenschema-Version blieb 1.

Die Kompatibilität gilt nur in dieser Richtung. Ältere App-Versionen mit strikter JSON-Prüfung können neue Backups mit diesen zusätzlichen Feldern ablehnen, auch wenn noch keine Vokabeln angelegt wurden. Insbesondere wird kein Import in die bisherige iOS-Version oder eine ältere Android-Version zugesichert. Ein bereits vorhandenes älteres Backup sollte vor einem Wechsel zu einer älteren App-Version aufbewahrt werden.

Die Referenz-, Revisions-, Größen- und Rundenzustandsprüfung erfolgt vor der Wiederherstellung. `data.json` ist auf 40 MiB begrenzt; der Export prüft diese Grenze ebenfalls. Die Grenze von 2.000 Dokumenten zählt zusätzlich zu dem verpflichtenden `data.json`-Eintrag. Verschlüsselung, Passwortableitung und Dokumentpfadprüfung bleiben unverändert.

## Allgemeine Lernkarten und JSON-Schema 2

Neue Backups behalten die verschlüsselte Hülle `SALM0001`; der JSON-Datenstand trägt `schema: 2`. Die Felder `flashcardSets`, `flashcards`, `flashcardProgress` und `flashcardSessions` speichern Sets, Frage-/Antwortkarten, Bewertungszähler und die offene oder abgeschlossene Runde getrennt von den Vokabeldaten. Alle vier Felder werden auch leer ausgeschrieben. Ein Set gehört zu einem Schulprofil. Eine bearbeitete Karte erhält eine neue Revision; ihr Fortschritt und eine betroffene Runde werden entfernt. Vokabelrunden speichern zusätzlich den Stil `TYPED` oder `FLIP`; bei `FLIP` bleibt auch der Zustand der aufgedeckten Antwort erhalten. Alte Runden ohne Stil bleiben `TYPED`.

Beim Lesen einer authentifizierten Sicherung oder eines lokal gespeicherten JSON-Datenstands mit Schema 1 wird die Schemaangabe ausdrücklich auf 2 migriert; fehlende Lernkartenfelder erhalten leere Listen. Danach erfolgen dieselbe strenge JSON-Prüfung und vollständige Referenz-/Rundenzustandsprüfung wie bei Schema 2. Unbekannte Felder, unbekannte Schema-Versionen und ungültige Kartenbezüge werden abgewiesen; ein Lesefehler erzeugt keinen leeren Ersatz-Datenstand. Alte Backups bleiben importierbar. Ältere App-Versionen können neue Backups nicht zuverlässig lesen.
