package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

@Composable
fun ProbeCard(
    a: MainActivity,
    d: SchoolData,
    e: Assessment,
    grade: () -> Unit,
    estimate: () -> Unit,
    details: (() -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    var practice by rememberSaveable(e.id, e.stage) { mutableStateOf(false) }
    val subject = d.subjects.find { it.id == e.subjectId }
    val sessions = d.studies.filter { it.assessmentId == e.id }
    val active = d.studies.firstOrNull { it.end == null }
    val own = active?.takeIf { it.assessmentId == e.id }
    val last = sessions.maxByOrNull { it.start }
    val points = d.profiles.find { it.id == subject?.profileId }?.points == true
    val wantsEstimate = e.estimate == null && EstimatePrompts.skipKey(e) !in d.delivered
    Card(
        Modifier.fillMaxWidth().monitor().testTag("probe-${e.id}"),
        shape = monitorShape(30.dp),
        border = lookBorder(),
        colors =
            CardDefaults.cardColors(containerColor = themedSubjectTint(subject), contentColor = Ink),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                subject?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${e.kind.label} · ${dateLabel(e.date)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (e.title != "${e.kind.label} · ${subject?.name}")
                Text(e.title, style = MaterialTheme.typography.bodyMedium)
            if (e.stage != Stage.PLANNED)
                Text(
                    when (e.stage) {
                        Stage.PLANNED -> "Kommt bald"
                        Stage.WRITTEN -> "Geschrieben"
                        Stage.GRADED -> "Note zurück"
                        Stage.CANCELLED -> "Fällt aus"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            if (e.stage == Stage.PLANNED) {
                val days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(e.date))
                Text(
                    when {
                        days > 1 -> "Noch $days Tage"
                        days == 1L -> "Morgen ist es soweit"
                        days == 0L -> "Heute ist es soweit"
                        else -> "Schon geschrieben? Du kannst es unten eintragen."
                    }
                )
            }
            if (own != null || e.stage == Stage.PLANNED || practice) {
                StudyClock(
                    (own ?: last)?.seconds(now) ?: 0,
                    own != null,
                    enabled = own != null || active == null && e.stage != Stage.CANCELLED,
                ) {
                    a.action { if (own != null) a.repo.stop() else a.repo.start(e.id) }
                }
                if (active != null && own == null)
                    Text(
                        "Für eine andere Probe läuft noch die Uhr. Du findest sie unter Heute.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (own != null && own.boot != a.repo.boot())
                    Text(
                        "Gerät neu gestartet: Prüfe deine Dauer nach dem Stoppen unter Lernzeiten."
                    )
                if (own == null && last?.end != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Lernrunde geschafft!", fontWeight = FontWeight.Bold)
                    }
                }
                if (sessions.isNotEmpty())
                    Text(
                        "${sessions.sumOf { it.seconds(now) } / 60} Minuten insgesamt geübt",
                        style = MaterialTheme.typography.bodySmall,
                    )
            } else
                when (e.stage) {
                    Stage.WRITTEN -> {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(56.dp), tint = Green)
                        Text(
                            if (wantsEstimate) "Wie ist es gelaufen?"
                            else "Jetzt warten wir auf die Note.",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (wantsEstimate) {
                            Action("Gefühlte Note eintragen", estimate)
                            TextButton(grade) { Text("Note eintragen") }
                        } else Action("Note eintragen", grade)
                    }
                    Stage.GRADED -> {
                        Text(
                            "${e.actual}${if (points) " Punkte" else ""}",
                            style = MaterialTheme.typography.displayLarge,
                            color = Ink,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (e.actual == null) "Die Note fehlt noch."
                            else if (
                                d.profiles.find { it.id == subject?.profileId }?.school ==
                                    School.GRUNDSCHULE
                            )
                                "Du hast etwas geschafft. Schau mit deiner Lehrkraft, was du schon kannst und wo du Hilfe brauchst."
                            else if (if (points) e.actual!! >= 5 else e.actual!! <= 4)
                                "Probe geschafft. Haken dran – das muss keine 1 sein."
                            else
                                "Diesmal hat es noch nicht gereicht. Ein Thema nach dem anderen – hol dir Hilfe, wenn du festhängst.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (sessions.isNotEmpty())
                            Text(
                                "Dafür hast du ${sessions.sumOf { it.seconds(now) } / 60} Minuten geübt."
                            )
                        TextButton(grade) { Text("Note ändern") }
                    }
                    Stage.CANCELLED -> Text("Für diese Probe musst du nicht mehr lernen.")
                    else -> Unit
                }
            if (e.stage == Stage.PLANNED) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        {
                            a.action {
                                a.repo.update { current ->
                                    current.copy(
                                        assessments =
                                            current.assessments.map { old ->
                                                if (old.id == e.id && old.stage == Stage.PLANNED)
                                                    old.copy(stage = Stage.WRITTEN)
                                                else old
                                            }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("mark-written-${e.id}"),
                    ) {
                        Text("Geschrieben")
                    }
                    if (details != null) TextButton(details) { Text("Lernstoff & Details") }
                    else TextButton(grade) { Text("Note eintragen") }
                }
            }
            if (e.stage != Stage.CANCELLED && e.stage != Stage.PLANNED) {
                if (e.estimate != null)
                    TextButton(estimate) { Text("Dein Gefühl: ${e.estimate} · ändern") }
                else if (e.stage == Stage.GRADED)
                    TextButton(estimate) { Text("Gefühlte Note eintragen") }
                if (own == null && !practice)
                    TextButton({ practice = true }) { Text("Weiter üben") }
            }
            if (e.stage != Stage.PLANNED)
                details?.let { TextButton(it) { Text("Lernstoff & Details") } }
        }
    }
}
