# Verschlüsseltes Backup, Version 1

Dateiendung: `.sabackup`. Alle Ganzzahlen im Textteil sind JSON-Zahlen. Das Format ist unabhängig von der späteren iOS-Oberfläche.

| Bytebereich | Inhalt |
| --- | --- |
| 0–7 | ASCII `SALM0001` |
| 8–23 | zufälliges 16-Byte-Salt |
| 24–35 | zufällige 12-Byte-GCM-Nonce |
| ab 36 | AES-256-GCM-Chiffrat mit angehängtem 16-Byte-Authentifizierungstag |

Der Schlüssel wird mittels PBKDF2-HMAC-SHA256 mit 600.000 Iterationen abgeleitet. Das Passwort wird gemäß der Java-PBEKeySpec/PBKDF2-Implementierung verarbeitet; für eine neue Plattform sind insbesondere Unicode-Passwörter mit gemeinsamen Testvektoren abzugleichen. Die acht Magic-Bytes sind Additional Authenticated Data. Export erfordert mindestens acht Passwortzeichen.

Der entschlüsselte Inhalt ist ein ZIP mit genau `data.json` und den in dessen Dokumentenliste referenzierten `documents/<uuid>.(pdf|jpg|png)`. JSON enthält Profile, Fächer, Leistungsnachweise, offizielle Ergebnisse, Stundenpläne, freie Tage, Ausnahmen, Lernphasen, Dokumentmetadaten, zugestellte Hinweise und Abschlussangaben. Jede Referenz wird vor der Wiederherstellung validiert.

Authentifizierung, ZIP-Pfadprüfung, Größenprüfung, Schema-/Referenzvalidierung und Vollständigkeitsprüfung erfolgen vor einer Änderung der bestehenden Daten. Dateien erhalten bei der Wiederherstellung neue interne Namen. Erst nach erfolgreicher Dateibereitstellung wird der gesamte Datenstand atomar in einer Room-Transaktion ersetzt. Nicht mehr referenzierte Dateien werden anschließend entfernt.

Grenzen: 40 MiB pro Dokument, 80 MiB Dokumentinhalt pro Export, 160 MiB verschlüsseltes beziehungsweise entpacktes Eingabelimit. Laufende Lernphasen aus einem Backup werden beendet und zur Zeitkorrektur markiert, da monotone Uhren zwischen Geräten nicht vergleichbar sind.

Unterrichtseinträge können zusätzlich `slot` (Position im Tagesplan, 1–30) und `isBreak` enthalten. Eine Pause hat keinen Fachverweis und zählt weder als Fach noch als Lerntermin. Ältere Einträge ohne diese Felder werden mit `slot = 0` und `isBreak = false` gelesen. Beim Öffnen des alten Stundenplans werden Positionen aus den Uhrzeiten aufgebaut; zusammenhängende 90-Minuten-Blöcke erscheinen als zwei benachbarte 45-Minuten-Stunden und zählen weiterhin gemeinsam als ein Termin.

Seit 0.1.2 werden zeitliche Lücken im Stundenplan als Pausen angezeigt, aber nicht als zusätzliche Unterrichtseinträge gespeichert. Bestehende Unterrichtszeiten bleiben erhalten. Das gemeinsame Lernstoff-/Notizfeld zeigt ältere `material`- und `notes`-Inhalte zusammen an; beim Speichern wandert der vollständige Text nach `material`, `notes` wird geleert. Beide gespeicherten Felder bleiben für ältere Backups lesbar. Bestehende Prüfungszeiten bleiben bei Änderungen am selben Datum erhalten; bei einem neuen Datum wird keine alte, unsichtbare Uhrzeit übernommen.

Ab 0.1.3 kennzeichnet `catalogVersion` die einmalige Fachlisten-Aktualisierung. „Ethik“ wird unter Beibehaltung der Fach-ID zu „Ethik/Religion“, sofern der Zielname nicht bereits im selben Profil vorkommt; in bayerischen Realschulprofilen wird fehlendes IT ergänzt. Bereits vorhandenes IT wird nicht dupliziert und später gelöschtes IT nicht erneut angelegt. Einschätzungsfragen nutzen datierte `estimate:`- und `estimate-skip:`-Schlüssel in `delivered`; Antworten bleiben getrennt in `estimate`.
