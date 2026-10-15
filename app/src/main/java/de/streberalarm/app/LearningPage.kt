package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.time.LocalDate

@Composable
fun LearningPage(a: MainActivity, p: Profile, d: SchoolData, open: (String) -> Unit,
                 add: () -> Unit, grade: (String) -> Unit,
                 vocabulary: () -> Unit, flashcards: () -> Unit) {
    val today = LocalDate.now().toString()
    val pending = d.assessments.filter { e -> e.actual == null && e.stage != Stage.CANCELLED &&
        d.subjects.any { it.id == e.subjectId && it.profileId == p.id } }
    val future = pending.filter { it.date >= today && it.stage == Stage.PLANNED }.sortedBy { it.date + it.time.orEmpty() }
    val past = pending.filter { it.date < today || it.stage == Stage.WRITTEN }.sortedByDescending { it.date }
    Page("Lernen") {
        Action("Prüfung planen", add, d.subjects.any { it.profileId == p.id }, Modifier.testTag("learn-plan"))
        if (future.isEmpty()) Text("Gerade steht keine Prüfung an.")
        future.forEach { e -> ExamCard(d, e) { open(e.id) } }
        if (past.isNotEmpty()) {
            Text("Note noch offen", style = MaterialTheme.typography.titleMedium)
            past.forEach { e ->
                OutlinedCard(onClick = { open(e.id) }, modifier = Modifier.fillMaxWidth().testTag("open-grade-${e.id}")) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${d.subjects.find { it.id == e.subjectId }?.name.orEmpty()} · ${dateLabel(e.date)}")
                        if (e.learningNotes.isNotBlank()) Text(e.learningNotes, style = MaterialTheme.typography.bodySmall)
                        TextButton({ grade(e.id) }) { Text("Note eintragen") }
                    }
                }
            }
        }
        Panel("Vokabeln") {
            Text("Deine Wörter: Karteikarten oder Abfragen.")
            Action("Vokabeln üben", vocabulary, true, Modifier.testTag("learn-vocabulary"))
        }
        Panel("Karteikarten") {
            Text("Frage und Antwort für jedes Fach.")
            Action("Karteikarten üben", flashcards, true, Modifier.testTag("learn-flashcards"))
        }
    }
}
