package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.time.LocalDate

@Composable
fun QuickPlanForm(p: Profile, subjects: List<Subject>, save: (Assessment) -> Unit) {
    var selected by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(7).toString()) }
    var kind by rememberSaveable { mutableStateOf(kinds(p).first()) }
    var error by rememberSaveable { mutableStateOf("") }
    val subject = subjects.find { it.id == selected }
    Page(
        if (subject == null) "In welchem Fach?" else "Wann ist deine Probe?",
        if (subject == null) "Tippe dein Fach an." else subject.name,
    ) {
        if (subject == null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2,
            ) {
                subjects.alphabetical().forEach { s ->
                    FilledTonalButton(
                        { selected = s.id },
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 64.dp)
                                .testTag("plan-subject-${s.id}"),
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = themedSubjectTint(s),
                                contentColor = Ink,
                            ),
                    ) {
                        Text(s.name)
                    }
                }
            }
            if (subjects.isEmpty()) Text("Lege zuerst unter Meine Noten ein Fach an.")
        } else {
            DateField("Datum", date) { date = it }
            Pick("Was ist es?", kind.label, kinds(p).map { it.label }) { label ->
                kind = kinds(p).first { it.label == label }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Action("Probe planen") {
                runCatching {
                        val day = LocalDate.parse(date)
                        require(
                            day >= LocalDate.of(p.year, 8, 1) &&
                                day <= LocalDate.of(p.year + 1, 9, 30)
                        )
                        save(
                            Assessment(
                                subjectId = subject.id,
                                title = "${kind.label} · ${subject.name}",
                                date = date,
                                kind = kind,
                            )
                        )
                    }
                    .onFailure { error = "Wähle einen Tag in deinem Schuljahr." }
            }
            Text(
                "Den Lernstoff kannst du danach auf deiner Karte ergänzen.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton({ selected = "" }) { Text("Anderes Fach wählen") }
        }
    }
}
