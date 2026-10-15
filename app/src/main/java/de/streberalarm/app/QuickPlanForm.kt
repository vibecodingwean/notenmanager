package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.time.LocalDate

@Composable
fun QuickPlanForm(p: Profile, subjects: List<Subject>, existing: Assessment? = null, saving: Boolean = false, save: (Assessment) -> Unit) {
    var selected by rememberSaveable(existing?.id) { mutableStateOf(existing?.subjectId.orEmpty()) }
    var date by rememberSaveable(existing?.id) { mutableStateOf(existing?.date ?: LocalDate.now().plusDays(7).toString()) }
    var topic by rememberSaveable(existing?.id) { mutableStateOf(existing?.learningNotes.orEmpty()) }
    var error by rememberSaveable { mutableStateOf("") }
    Page(if (existing == null) "Prüfung planen" else "Prüfung bearbeiten") {
        Panel {
            DateField("Datum", date) { date = it }
            Pick("Fach", subjects.find { it.id == selected }?.name ?: "Bitte wählen", subjects.alphabetical().map { it.name }) { name ->
                selected = subjects.first { it.name == name }.id
            }
            Field("Thema (optional)", topic, 2) { topic = it }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Action("Speichern", {
                runCatching {
                    val subject = requireNotNull(subjects.find { it.id == selected })
                    val day = LocalDate.parse(date)
                    require(day >= LocalDate.of(p.year, 8, 1) && day <= LocalDate.of(p.year + 1, 9, 30))
                    val original = existing ?: Assessment(subjectId = subject.id, title = "Prüfung · ${subject.name}", date = date, kind = kinds(p).first())
                    save(original.copy(subjectId = selected, date = date,
                        time = original.time.takeIf { date == original.date },
                        material = if (topic == existing?.learningNotes) original.material else topic))
                }.onFailure { error = "Bitte Fach und Datum im Schuljahr prüfen." }
            }, !saving)
        }
    }
}
