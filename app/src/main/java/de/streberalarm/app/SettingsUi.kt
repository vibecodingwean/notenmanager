package de.streberalarm.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*

@Composable
fun SettingsHero(title: String, hint: String, icon: ImageVector, tag: String, click: () -> Unit) {
    Card(
        onClick = click,
        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp).testTag(tag),
        shape = lookShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = lookShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .semantics { stateDescription = if (expanded) "Aufgeklappt" else "Zugeklappt" },
            colors = bayernButtonColors(ButtonDefaults.textButtonColors()),
        ) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
fun SettingsLink(title: String, click: () -> Unit) {
    OutlinedButton(
        onClick = click,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = lookShape(16.dp),
        colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors()),
    ) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
    }
}

@Composable
fun PrivacyRulesPage(p: Profile?) {
    Page("Datenschutz & Rechtsgrundlagen", "Zum Nachlesen – auch für deine Eltern.") {
        PrivacyPanel()
        Panel("Rechtsgrundlagen") {
            Text("NotenManager hilft dir beim Überblick. Die Schule entscheidet über Zeugnisse, Vorrücken, Übertritt und Abschlüsse.")
            if (p != null) {
                Text("Dein Profil: ${p.school.label} · ${p.grade}. Klasse · ${p.year}")
                Text("Regelpaket ${p.ruleVersion} · Quellenprüfung ${Rules.REVIEWED}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Rechnerische Schnitte und Einschätzungen sind keine offiziellen Zeugnisnoten. Offizielle Ergebnisse bleiben getrennt erfasst.")
            Text("Unterstützt werden reguläre Schülerlaufbahnen in Bayern im Schuljahr 2026/27. Andere Bundesländer, historische Schuljahre und Sonderfälle sind nicht vollständig abgedeckt.")
            if (p != null && !Rules.supported(p)) Text("Für dieses Profil ist kein passendes Regelpaket verfügbar.")
            Source(law("BayEUG-52"))
            Source(law("BayEUG-53"))
            if (p != null) {
                Source(law(when (p.school) {
                    School.GRUNDSCHULE -> "BayVSO-6"
                    School.GYMNASIUM -> "BayGSO-30"
                    School.REALSCHULE -> "BayRSO-24"
                    School.MITTELSCHULE, School.M_ZUG -> "BayMSO-15"
                }))
            }
        }
    }
}

@Composable
fun PrivacyPanel() {
    Panel("Datenschutz") {
        Text("Wer kann meine Daten sehen?", fontWeight = FontWeight.Bold)
        Text(
            "NotenManager speichert Profile, Noten, Lernzeiten und Dokumente lokal im privaten App-Speicher. Kein Konto, keine Werbung, keine eigene App-Analyse, keine Cloud-KI. Automatische Android-Cloud-Sicherungen und Geräteübertragung sind deaktiviert."
        )
        Text(
            "Den Speicherort exportierter Backups wählst du selbst. Dort kann ein von dir gewählter Dateidienst beteiligt sein. Der lokale Scanner arbeitet ohne Übermittlung von Bildern oder Nutzungsdaten. Optional steht Google ML Kit zur Verfügung: Erst nach Auswahl und Bestätigung des Hinweises wird das SDK initialisiert. Google kann Geräte- und Appinformationen, Kennungen, Diagnose- und Nutzungsdaten verarbeiten; Scans selbst werden laut Google auf dem Gerät verarbeitet. Die erste Nutzung kann einen Download benötigen. Bereits bei Google entstandene Diagnosedaten werden durch spätere Wahl des lokalen Scanners nicht zurückgerufen."
        )
        Text(
            "Beim Deinstallieren gehen lokale Daten verloren, sofern du zuvor kein eigenes Backup exportiert hast. Externe Quellenlinks öffnen deinen Browser."
        )
        Text(
            "Entwicklungsversion ${BuildConfig.VERSION_NAME} · Regeln ${Rules.VERSION} · Quellenprüfung ${Rules.REVIEWED}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
