package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.streberalarm.core.*

/** Shared choice and Google disclosure, regardless of the entry point. */
@Composable
fun AssessmentScannerButton(a: MainActivity, examId: String, label: String, scan: () -> Unit) {
    var scannerChoice by rememberSaveable(examId) { mutableStateOf(false) }
    var googleNotice by rememberSaveable(examId) { mutableStateOf(false) }
    Action(label) { scannerChoice = true }
    if (scannerChoice)
        AlertDialog(
            onDismissRequest = { scannerChoice = false },
            title = { Text("Scanner auswählen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Du entscheidest, wie deine Arbeit erfasst wird.")
                    Action("Lokaler Scanner") {
                        scannerChoice = false
                        scan()
                    }
                    OutlinedButton(
                        {
                            scannerChoice = false
                            googleNotice = true
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Text("Google ML Kit")
                    }
                }
            },
            confirmButton = { TextButton({ scannerChoice = false }) { Text("Abbrechen") } },
        )
    if (googleNotice)
        AlertDialog(
            onDismissRequest = { googleNotice = false },
            title = { Text("Google-Scanner: Datenschutz") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Google ML Kit verarbeitet die Dokumentbilder auf deinem Gerät. Google kann jedoch Geräte- und Appinformationen, Kennungen sowie Diagnose- und Nutzungsdaten erfassen. Die erste Nutzung benötigt möglicherweise einen Download über Google Play-Dienste."
                    )
                    Text(
                        "Wenn du das nicht möchtest, nutze den lokalen Scanner. Erst mit deiner Bestätigung wird Google ML Kit gestartet."
                    )
                    Source("https://developers.google.com/ml-kit/android-data-disclosure")
                }
            },
            confirmButton = {
                TextButton({
                    googleNotice = false
                    a.scanGoogle(examId)
                }) {
                    Text("Google-Scanner starten")
                }
            },
            dismissButton = {
                TextButton({
                    googleNotice = false
                    scannerChoice = true
                }) {
                    Text("Zur Scanner-Auswahl")
                }
            },
        )
}

@Composable
fun GradeAttachments(a: MainActivity, d: SchoolData, e: Assessment) {
    var scanning by rememberSaveable(e.id) { mutableStateOf(false) }
    AssessmentScannerButton(a, e.id, "Probe fotografieren") { scanning = true }
    TextButton({ a.import(e.id) }) { Text("Bild oder PDF hinzufügen") }
    val count = d.documents.count { it.assessmentId == e.id }
    if (count > 0)
        Text(
            if (count == 1) "1 Unterlage gespeichert" else "$count Unterlagen gespeichert",
            style = MaterialTheme.typography.bodySmall,
        )
    if (scanning)
        Dialog(
            onDismissRequest = { scanning = false },
            properties =
                DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            // Keep the grade form mounted behind the camera: its unsaved choice survives the scan.
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TextButton({ scanning = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Zurück zur Note")
                    }
                    Box(Modifier.weight(1f)) { ScannerPage(a, e.id) { scanning = false } }
                }
            }
        }
}
