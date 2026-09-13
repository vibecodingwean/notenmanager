# StreberAlarm

<img src="assets/branding/streberalarm.png" alt="StreberAlarm: Schüler mit erhobenem Zeigefinger und rotem Sirenenring" width="160" />

Deutschsprachiger Noten- und Lernplaner: sicher durchkommen, ohne ständig Bestnoten anzustreben. Die Android-App ist kostenlos vorgesehen; eine spätere iOS-App kann kostenpflichtig angeboten werden. Android startet zuerst; der gemeinsame Kotlin-Multiplatform-Kern bereitet eine spätere iOS-App vor.

Aktueller Stand: lokale Entwicklungsversion, noch keine Store-Veröffentlichung.

- Drei einfache Bereiche: Heute, Lernen und Meine Noten. Den Stundenplan erreichst du über Heute. Bunte Lernkarten, große Schaltflächen und kurze Erklärungen. Fächer und Fachauswahlen sind alphabetisch sortiert; Fachfarben stimmen in Noten, Tests und Stundenplan überein: Violett für Haupt-/Kernfächer, Blau für weitere Vorrückungsfächer, Grau für weitere Fächer. Die Einordnung im Fach bestimmt die Farbe; Haupt-/Kernfach hat Vorrang.
- Einrichtung in zwei Schritten; einrastende Auswahlräder für Klasse, Schuljahr, Schulzweig und Noten. Testtermine über Kalender, Stundenplanzeiten über Zeitwähler.
- Stundenplan mit sechs Feldern je Schultag: Fach oder Pause wählen, fehlende Fächer direkt ergänzen, zusätzliche Stunden oder Doppelstunden hinzufügen. Vorgegebene Schulzeiten bis zur neunten Stunde; zeitliche Lücken werden automatisch als Pausen hervorgehoben.
- Tests ohne Namensfeld oder Uhrzeiteingabe erfassen. Ein gemeinsames Feld für Lernstoff und Notizen; automatisch erscheint beispielsweise „Schulaufgabe · Mathematik“. Die gefühlte Note wird separat am Testtag ab 16 Uhr oder beim Eintragen der echten Note erfragt. Ohne Benachrichtigungsfreigabe bleibt die Frage in der App verfügbar.
- Getrennte tatsächliche Noten, Einschätzungen und offizielle Zeugnis-/Halbjahresleistungen.
- Grundschule Klasse 3/4: Übertrittsübersicht für Bayern, getrennte Vorschau und offizielle Übertrittsnoten in Deutsch, Mathematik und HSU. Probeunterricht und schulische Entscheidungen bleiben eigenständig.
- Sechs umschaltbare Looks, darunter Hacker mit Matrix-Code und CRT-Karten sowie Pixelwelt. Bewegungen lassen sich ausschalten.
- Vorrückungsampel und konkrete Fachnotenziele; entspannte Hinweise bei passendem Stand statt Druck zum Einser-Schnitt.
- Versionierte bayerische Berechnungen für Gymnasium, Realschule und Mittelschule/M-Zug; andere Bundesländer verwenden keine bayerischen Regeln.
- A/B-Wochen, datierte Stundenplanänderungen, zusammenhängende Doppelstunden, Ferien und Ausnahmen.
- Lerntimer mit gespeicherten Sitzungen und Korrekturmöglichkeit; gebündelte Erinnerungen um 16 Uhr.
- Lokaler Scanner mit Zuschneiden, Drehen, Seitensortierung und PDF-Ausgabe. Optional Google ML Kit nach Datenschutzhinweis.
- Bilder/PDFs importieren und privat ansehen. Verschlüsseltes vollständiges Backup mit Passwort.

Android 10 oder neuer. Kein Konto, keine Werbung und keine eigene Nutzungsanalyse. Der optionale Google-Scanner kann Diagnose- und Nutzungsdaten verarbeiten. Automatische Betriebssystem-Backups sind ausgeschaltet.

## Bauen und prüfen

JDK 17 und Android SDK 36 werden benötigt. Den SDK-Pfad in einer lokalen `local.properties` mit `sdk.dir=...` oder über `ANDROID_HOME` bereitstellen.

```sh
./gradlew :core:test :core:compileCommonMainKotlinMetadata :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:bundleRelease
```

Der zweite Befehl benötigt einen Android-Emulator. Release-Artefakte sind ohne eingerichtete eigene Signierung nicht zur Veröffentlichung bestimmt.

## Geltungsbereich

Das enthaltene Regelpaket ist an Bayern und das Schuljahr 2026/27 gebunden. Rechnerische Leistungsstände sind keine amtlichen Zeugnisnoten. Abschlussprüfungen verwenden offizielle Eingaben und explizite schulische Nachweise. Nicht vollständig nachgewiesene oder nicht abgebildete Konstellationen ergeben keine positive Freigabe.

[Rechtsabdeckung und offene Release-Abnahme](docs/rechtsabdeckung.md) · [Plattformarchitektur](docs/plattformen.md) · [Backup-Format](docs/backup-format.md)

## Lizenz und Verteilung

Der Quellcode steht unter der [MIT-Lizenz](LICENSE). Android-APKs und App Bundles werden nicht in diesem Repository oder seinen Releases angeboten. Du kannst die App selbst bauen. Eine kostenpflichtige App-Verteilung ändert die MIT-Rechte am veröffentlichten Quellcode nicht.

## Website und Tutorial

[StreberAlarm entdecken](https://app.wean.de/streberalarm/) – mit deutschem Tutorial und Datenschutzhinweisen.
