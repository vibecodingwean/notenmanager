# NotenManager

<img src="assets/branding/notenmanager.png" alt="NotenManager: Offenes Schulbuch mit einem Wegpfeil" width="160" />

Deutschsprachiger Noten- und Lernplaner: sicher durchkommen, ohne ständig Bestnoten anzustreben. Android und iOS verwenden einen gemeinsamen Kotlin-Multiplatform-Kern. Die Android-App ist kostenlos, die iOS-App kostet im deutschen App Store 1,99 €.

Android und iOS sind in den Stores veröffentlicht. Der Entwicklungsstand dieses Repositories kann neuer als die jeweilige Store-Version sein.

Die vereinfachte Android-Oberfläche bietet drei feste Bereiche:

- **Heute:** Gesamtschnitt und Vorrückungsampel als Orientierung, „Note eintragen“, die nächste Prüfung und ein tageweise durchblätterbarer Stundenplan mit Fachschnitten. Die Entscheidung über Zeugnis und Vorrücken trifft die Schule.
- **Meine Noten:** alphabetische Fächer mit Schnitt und Notenzahl. Im Fach stehen die tatsächlichen Noten nach Datum, mit Art, Thema und direkt sichtbaren Dokumentvorschauen. Noten lassen sich über das sichtbare Menü bearbeiten oder löschen.
- **Lernen:** kommende Prüfungen nach Datum planen; ältere Prüfungen ohne Note bleiben erreichbar. Darunter stehen eigene Vokabelsets und allgemeine Frage-/Antwortkarten. Vokabeln lassen sich schriftlich abfragen oder als Karteikarten üben; allgemeine Karten werden nach dem Aufdecken selbst bewertet. Fortschritt und offene Runden werden gespeichert. Ein abschaltbarer lokaler Erfolgston und Animationen beachten die Geräte- und Bewegungseinstellungen.

Auf Android erfasst ein kompaktes Formular Fach, Note oder Oberstufenpunkte, ausdrücklich gewählte Notenart, Datum und optional Thema, Foto oder Dokument. Eine passende ältere unbenotete Prüfung kann zugeordnet werden. Neue Unterlagen bleiben bis zum erfolgreichen Speichern im privaten Entwurf. Einstellungen bündeln Schuljahr/Profile, Fächer/Gewichtungen, offizielle Ergebnisse, Aussehen, Erinnerungen, Backup und Informationen. Geschätzte Noten, Notenziele, Lerntimer und ausführliche Berechnungsansichten sind in dieser Android-Oberfläche nicht aktiv; vorhandene historische Daten bleiben gespeichert.

Die iOS-Oberfläche wird durch diese Android-Vereinfachung nicht umgestaltet. Ihre bisherigen Ansichten und Funktionen bleiben bestehen; der Vokabel- und Karteikartentrainer ist Android-spezifisch. Beide Plattformen verwenden weiterhin den gemeinsamen Kern und behalten bestehende Schulprofile, Noten, offizielle Ergebnisse, Dokumente und Stundenpläne.

- Einrichtung mit Auswahlrädern für Klasse, Schuljahr und Schulzweig; Termine über Kalender, Stundenplanzeiten über Zeitwähler.
- Stundenplan mit Fach- und Pausenzeilen, zusätzlichen Stunden, A/B-Wochen, datierten Planversionen, zusammenhängenden Doppelstunden, Ferien und Ausnahmen. Fehlende Fächer können direkt ergänzt werden.
- Zehn umschaltbare Looks auf Android und iOS, darunter Hacker mit Matrix-Code und CRT-Karten sowie Pixelwelt. Auf dem iPhone führt ein Pinsel oben in „Dein Tag“ oder „Look auswählen“ unter „Meine Noten“ zur Auswahl. Bewegungen lassen sich ausschalten.
- Versionierte bayerische Noten- und Schulregeln für Gymnasium, Realschule und Mittelschule/M-Zug; andere Bundesländer verwenden keine bayerischen Regeln. Rechnerische Schnitte bleiben von offiziellen Zeugnis- und Halbjahresleistungen getrennt.
- Gebündelte Erinnerungen an kommende Prüfungen um 16 Uhr.
- Lokaler Dokumentenscanner mit PDF-Ausgabe: auf Android mit Zuschneiden, Drehen und mehreren Seiten, auf iOS über Apples nativen Dokumentenscanner. Auf Android ist Google ML Kit nach Datenschutzhinweis optional.
- Bilder/PDFs importieren und privat ansehen. Verschlüsseltes vollständiges Backup mit Passwort. Neue Versionen lesen alte Backups; alte App-Versionen müssen neue Backups nicht lesen können.

Android 10 oder neuer beziehungsweise iOS 16 oder neuer. Kein Konto, keine Werbung und keine eigene Nutzungsanalyse. Der optionale Google-Scanner auf Android kann Diagnose- und Nutzungsdaten verarbeiten. Die privaten App-Daten sind von automatischen Betriebssystem-Backups ausgenommen.

## Bauen und prüfen

JDK 17 und Android SDK 36 werden benötigt. Den SDK-Pfad in einer lokalen `local.properties` mit `sdk.dir=...` oder über `ANDROID_HOME` bereitstellen.

```sh
./gradlew :core:test :core:compileCommonMainKotlinMetadata :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:bundleRelease
```

Der zweite Befehl benötigt einen Android-Emulator. Release-Artefakte sind ohne eingerichtete eigene Signierung nicht zur Veröffentlichung bestimmt.

Für iOS werden macOS, Xcode und JDK 17 benötigt. Das SwiftUI-Projekt liegt unter `iosApp/NotenManager.xcodeproj`; sein Build-Skript bindet den gemeinsamen Kotlin-Kern als `StreberAlarmCore` ein. In Xcode das Schema `NotenManager` für einen Simulator auswählen und bauen beziehungsweise die Tests aus `iosAppTests` und `iosApp/UITests` ausführen. Für einen eigenständig auf dem iPhone startbaren Test ist ein signierter Release-Build nötig; Simulator- und Build-Erfolg ersetzen keine Prüfung auf dem Gerät.

## Geltungsbereich

Das enthaltene Regelpaket ist an Bayern und das Schuljahr 2026/27 gebunden. Rechnerische Leistungsstände sind keine amtlichen Zeugnisnoten. Abschlussprüfungen verwenden offizielle Eingaben und explizite schulische Nachweise. Nicht vollständig nachgewiesene oder nicht abgebildete Konstellationen ergeben keine positive Freigabe.

[Rechtsabdeckung und offene Release-Abnahme](docs/rechtsabdeckung.md) · [Plattformarchitektur](docs/plattformen.md) · [Backup-Format](docs/backup-format.md)

## Lizenz und Verteilung

Der Quellcode steht unter der [MIT-Lizenz](LICENSE). Android-APKs und App Bundles werden nicht in diesem Repository oder seinen Releases angeboten. Du kannst die App selbst bauen. Eine kostenpflichtige App-Verteilung ändert die MIT-Rechte am veröffentlichten Quellcode nicht.

## Website und Tutorial

[NotenManager entdecken](https://app.wean.de/notenmanager/) – mit deutschem Tutorial und Datenschutzhinweisen.
