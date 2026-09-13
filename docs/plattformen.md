# Android zuerst, iOS vorbereitet

Die Android-App verwendet Kotlin und Jetpack Compose. Der gemeinsame `core` ist ein echtes Kotlin-Multiplatform-Modul mit JVM-, iOS-ARM64- und iOS-Simulator-ARM64-Zielen. Sein Apple-Framework heißt `StreberAlarmCore`.

`commonMain` enthält Datenmodelle, JSON-Schema, Notenberechnungen, versionierte Rechtsregeln, Unterrichtszählung, Erinnerungsplanung und Timer-Zustandsübergänge. Zeitberechnungen verwenden `kotlinx-datetime`; der gemeinsame Code importiert keine Java- oder Android-APIs. Gemeinsame Tests laufen derzeit auf dem JVM-Ziel. Die plattformübergreifenden Metadaten werden zusätzlich kompiliert.

`jvmMain` enthält ausschließlich die Android/JVM-Zeitadapter sowie die aktuelle AES-GCM/PBKDF2-/ZIP-Backup-Implementierung. Android verwaltet mit Room und privatem Dateispeicher die Persistenz und setzt den gemeinsamen Erinnerungsplan mit AlarmManager um.

Für iOS sind später eine Oberfläche, Persistenz, UNUserNotificationCenter-Anbindung, lokaler Dokumentenscanner, Dateiauswahl und eine zum dokumentierten Backup-Format kompatible Kryptografie-/ZIP-Implementierung erforderlich. SwiftUI kann den gemeinsamen Kern verwenden; Compose Multiplatform bleibt eine mögliche UI-Option. Der Android-ML-Kit-Dokumentenscanner ist keine zugesicherte iOS-Funktion; dafür bietet sich der native Apple-Scanner an.

Die iOS-Ziele sind vorbereitet, aber noch keine iOS-App. Apple-Framework-Builds und iOS-Bedientests benötigen macOS/Xcode und wurden auf dem Linux-Entwicklungsrechner nicht ausgeführt. Vor dem iOS-Start sind die jeweiligen Plattformvorgaben und Benachrichtigungsgrenzen erneut zu prüfen.
