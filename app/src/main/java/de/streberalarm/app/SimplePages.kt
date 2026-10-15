package de.streberalarm.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun adjacentDay(date: String, direction: Int): String =
    LocalDate.parse(date).plusDays(direction.toLong()).toString()

@Composable
fun TodayPage(
    a: MainActivity,
    p: Profile,
    d: SchoolData,
    exam: (String) -> Unit,
    add: () -> Unit,
    timetable: () -> Unit,
) {
    val today = LocalDate.now()
    var chosen by rememberSaveable(p.id) { mutableStateOf(today.toString()) }
    val day = LocalDate.parse(chosen)
    val next =
        d.assessments
            .filter { e ->
                e.actual == null &&
                    e.stage == Stage.PLANNED &&
                    e.date >= today.toString() &&
                    d.subjects.any { it.id == e.subjectId && it.profileId == p.id }
            }
            .minByOrNull { it.date + it.time.orEmpty() }
    Page("Heute") {
        GradeOverviewPanel(p, d)
        Action(
            "Note eintragen",
            add,
            d.subjects.any { it.profileId == p.id },
            Modifier.testTag("today-add-grade"),
        )
        next?.let { e ->
            Text("Nächste Prüfung", style = MaterialTheme.typography.titleMedium)
            ExamCard(d, e) { exam(e.id) }
        }
        Column(
            Modifier.fillMaxWidth().testTag("day-timetable").pointerInput(chosen) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                    },
                    onDragEnd = {
                        if (kotlin.math.abs(drag) > 80f)
                            chosen = adjacentDay(chosen, if (drag < 0) 1 else -1)
                    },
                )
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton({ chosen = adjacentDay(chosen, -1) }, Modifier.testTag("day-previous")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Vorheriger Tag")
                }
                Text(dateLabel(chosen), Modifier.testTag("day-date"), fontWeight = FontWeight.Bold)
                IconButton({ chosen = adjacentDay(chosen, 1) }, Modifier.testTag("day-next")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Nächster Tag")
                }
            }
            if (day != today)
                TextButton(
                    { chosen = today.toString() },
                    Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Zurück zu heute")
                }
            val lessons = dailyLessons(d, p, day)
            Panel("Unterricht") {
                if (lessons.isEmpty())
                    Text(
                        if (d.timetables.none { it.profileId == p.id })
                            "Dein Stundenplan fehlt noch."
                        else "Kein Unterricht eingetragen."
                    )
                lessons.forEach { lesson ->
                    val subject = d.subjects.find { it.id == lesson.subjectId }
                    Surface(
                        color = if (lesson.isBreak) BreakTint else themedSubjectTint(subject),
                        shape = lookShape(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("today-lesson-${lesson.id}"),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (lesson.isBreak) "Pause" else subject?.name.orEmpty(),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("${lesson.start}–${lesson.end}")
                            }
                            if (!lesson.isBreak)
                                Text(
                                    "Ø ${number(subject?.let { Grades.calculate(p, it, d.assessments).value })}"
                                )
                        }
                    }
                }
                TextButton(timetable) { Text("Stundenplan in Einstellungen") }
            }
        }
    }
}

/** Null means there is no subject schedule applicable before this exam. */
internal fun remainingLessonCount(data: SchoolData, exam: Assessment, now: LocalDateTime): Int? {
    val subject = data.subjects.find { it.id == exam.subjectId } ?: return null
    val end = LocalDate.parse(exam.date)
    val start = now.toLocalDate()
    if (end < start) return null
    val tables = data.timetables.filter { it.profileId == subject.profileId }.sortedBy { it.validFrom }
    val matching = tables.withIndex().any { (index, table) ->
        val next = tables.getOrNull(index + 1)?.validFrom
        table.validFrom <= exam.date && (next == null || next > start.toString()) &&
            table.blocks.any { !it.isBreak && it.subjectId == subject.id }
    }
    if (!matching) return null
    return Lessons.remaining(data, exam, now).size
}

@Composable
fun ExamCard(d: SchoolData, e: Assessment, click: () -> Unit) {
    val subject = d.subjects.find { it.id == e.subjectId }
    Card(
        onClick = click,
        modifier = Modifier.fillMaxWidth().monitor().testTag("exam-${e.id}"),
        colors =
            CardDefaults.cardColors(
                containerColor = themedSubjectTint(subject),
                contentColor = Ink,
            ),
        shape = monitorShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${dateLabel(e.date)} · ${subject?.name.orEmpty()}", fontWeight = FontWeight.Bold)
            if (e.learningNotes.isNotBlank()) Text(e.learningNotes)
            if (e.stage == Stage.PLANNED) remainingLessonCount(d, e, LocalDateTime.now())?.let { count ->
                Text("Noch $count Unterrichtseinheiten", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SubjectPage(
    p: Profile,
    s: Subject,
    d: SchoolData,
    edit: () -> Unit,
    exam: (String) -> Unit,
    add: () -> Unit,
    official: () -> Unit,
    a: MainActivity,
    document: (String) -> Unit,
    delete: (String) -> Unit,
) {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    Page(s.name) {
        Text(
            "Ø ${number(Grades.calculate(p, s, d.assessments).value)}",
            style = MaterialTheme.typography.displayMedium,
        )
        Action("Note eintragen", add)
        val grades =
            d.assessments
                .filter { it.subjectId == s.id && it.actual != null && it.stage != Stage.CANCELLED }
                .sortedByDescending { it.date + it.time.orEmpty() }
        if (grades.isEmpty()) Text("Noch keine Noten eingetragen.")
        grades.forEach { e ->
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${e.actual}${if (p.points) " Punkte" else ""}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    GradeMenu(e, { exam(e.id) }, { pending = e.id })
                }
                Text("${e.kind.label} · ${dateLabel(e.date)}")
                if (e.learningNotes.isNotBlank()) Text(e.learningNotes)
                DocumentThumbnails(a, d.documents.filter { it.assessmentId == e.id }, document)
            }
        }
    }
    pending?.let { id ->
        DeleteAssessmentDialog({ pending = null }) {
            delete(id)
            pending = null
        }
    }
}

@Composable
internal fun GradeMenu(e: Assessment, edit: () -> Unit, delete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }, Modifier.testTag("grade-menu-${e.id}")) {
            Icon(Icons.Outlined.MoreVert, "Bearbeiten oder löschen")
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Bearbeiten") },
                onClick = {
                    expanded = false
                    edit()
                },
            )
            DropdownMenuItem(
                text = { Text("Löschen") },
                onClick = {
                    expanded = false
                    delete()
                },
            )
        }
    }
}

@Composable
internal fun DeleteAssessmentDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Eintrag löschen?") },
        text = { Text("Die Note, Prüfung und ihre Dokumente werden entfernt.") },
        confirmButton = { TextButton(confirm) { Text("Löschen") } },
        dismissButton = { TextButton(dismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun ExamPage(
    a: MainActivity,
    d: SchoolData,
    e: Assessment,
    edit: () -> Unit,
    grade: () -> Unit,
    document: (String) -> Unit,
    delete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Page(d.subjects.find { it.id == e.subjectId }?.name ?: "Prüfung", dateLabel(e.date)) {
        if (e.learningNotes.isNotBlank()) Text(e.learningNotes)
        if (e.actual != null)
            Text("Note ${e.actual}", style = MaterialTheme.typography.displayMedium)
        Action(if (e.actual == null) "Note eintragen" else "Note bearbeiten", grade)
        DocumentThumbnails(a, d.documents.filter { it.assessmentId == e.id }, document)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(edit) { Text("Bearbeiten") }
            TextButton({ confirm = true }) { Text("Löschen") }
        }
    }
    if (confirm)
        DeleteAssessmentDialog({ confirm = false }) {
            confirm = false
            delete()
        }
}

@Composable
internal fun DocumentThumbnails(
    a: MainActivity,
    documents: List<Document>,
    open: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        documents.forEach { doc ->
            var bitmap by remember(doc.id) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(doc.id, doc.file) {
                bitmap =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                val file = File(a.repo.documentsDir, doc.file)
                                if (doc.mime == "application/pdf")
                                    PdfRenderer(
                                            ParcelFileDescriptor.open(
                                                file,
                                                ParcelFileDescriptor.MODE_READ_ONLY,
                                            )
                                        )
                                        .use { pdf ->
                                            pdf.openPage(0).use { page ->
                                                Bitmap.createBitmap(
                                                        160,
                                                        210,
                                                        Bitmap.Config.ARGB_8888,
                                                    )
                                                    .also {
                                                        it.eraseColor(android.graphics.Color.WHITE)
                                                        page.render(
                                                            it,
                                                            null,
                                                            null,
                                                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                                        )
                                                    }
                                            }
                                        }
                                else {
                                    val bounds =
                                        BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(file.path, bounds)
                                    bounds.inSampleSize =
                                        maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 240)
                                    bounds.inJustDecodeBounds = false
                                    BitmapFactory.decodeFile(file.path, bounds)
                                }
                            }
                            .getOrNull()
                    }
            }
            Surface(
                Modifier.width(104.dp)
                    .height(140.dp)
                    .clickable { open(doc.id) }
                    .testTag("document-thumb-${doc.id}"),
                shape = lookShape(12.dp),
                tonalElevation = 3.dp,
            ) {
                bitmap?.let { Image(it.asImageBitmap(), doc.name, contentScale = ContentScale.Fit) }
                    ?: Text(doc.name, Modifier.padding(8.dp))
            }
        }
    }
}
