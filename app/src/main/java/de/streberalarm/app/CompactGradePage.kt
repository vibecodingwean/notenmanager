package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.streberalarm.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A grade and its new attachments become visible together only after the save commits. */
@Composable
fun CompactGradePage(
    activity: MainActivity,
    p: Profile,
    d: SchoolData,
    selection: String,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
    onBackChanged: ((() -> Unit)?) -> Unit = {},
) {
    val subjects = d.subjects.filter { it.profileId == p.id }.alphabetical()
    val original = d.assessments.find { it.id == selection }
    val initialSubject = original?.subjectId ?: selection.removePrefix("new:")
    val repo = activity.repo
    val generation by repo.vocabularyGeneration.collectAsState()
    val revision by repo.gradeDraftRevision.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember(selection, p.id, generation) { mutableStateOf<GradeDraft?>(null) }
    var draftId by rememberSaveable(selection, p.id) { mutableStateOf<String?>(null) }
    var sid by rememberSaveable(selection) { mutableStateOf(initialSubject) }
    var date by
        rememberSaveable(selection) {
            mutableStateOf(original?.date ?: java.time.LocalDate.now().toString())
        }
    var kind by rememberSaveable(selection) { mutableStateOf(original?.kind) }
    var topic by
        rememberSaveable(selection) {
            mutableStateOf(original?.material?.ifBlank { original.notes }.orEmpty())
        }
    var actual by rememberSaveable(selection) { mutableStateOf(original?.actual) }
    var weight by
        rememberSaveable(selection) { mutableStateOf(original?.weight?.toString() ?: "1") }
    var associatedId by rememberSaveable(selection) { mutableStateOf(original?.id) }
    var hydrated by rememberSaveable(selection) { mutableStateOf(false) }
    var scanning by rememberSaveable(selection) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var documentCount by remember { mutableIntStateOf(0) }
    val cancelAction: () -> Unit = {
        if (!saving)
            scope.launch {
                val opened = draft
                try {
                    if (opened != null)
                        withContext(Dispatchers.IO) { repo.cancelGradeDraft(opened.id, generation) }
                    onCancel()
                } catch (e: Exception) {
                    error = e.message ?: "Notenentwurf konnte nicht entfernt werden."
                }
            }
    }
    val latestCancel by rememberUpdatedState(cancelAction)
    val latestBackChanged by rememberUpdatedState(onBackChanged)
    DisposableEffect(selection) {
        latestBackChanged { latestCancel() }
        onDispose { latestBackChanged(null) }
    }

    LaunchedEffect(selection, p.id, generation) {
        if (draftId == null && subjects.none { it.id == initialSubject }) return@LaunchedEffect
        try {
            val opened =
                withContext(Dispatchers.IO) {
                    draftId?.let { repo.resumeGradeDraft(it, p.id, generation) }
                        ?: repo.openGradeDraft(p.id, initialSubject, original?.id, generation)
                }
            draft = opened
            draftId = opened.id
            if (!hydrated) {
                sid = opened.subjectId
                associatedId = opened.targetId
                if (original == null && opened.targetId != null) {
                    d.assessments
                        .find { it.id == opened.targetId }
                        ?.let {
                            date = it.date
                            kind = it.kind
                            topic = it.material.ifBlank { it.notes }
                            weight = it.weight.toString()
                        }
                }
                opened.date?.let { date = it }
                opened.kind?.let { kind = it }
                opened.topic?.let { topic = it }
                opened.actual?.let { actual = it }
                opened.weight?.let { weight = it }
                hydrated = true
            }
        } catch (e: Exception) {
            error = e.message ?: "Notenentwurf konnte nicht geöffnet werden."
        }
    }
    LaunchedEffect(draft?.id, revision) {
        documentCount =
            draft?.let { withContext(Dispatchers.IO) { repo.gradeDraftDocuments(it.id).size } } ?: 0
    }
    LaunchedEffect(draft?.id, date, kind, topic, actual, weight, hydrated, generation) {
        val opened = draft ?: return@LaunchedEffect
        if (!hydrated) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                repo.updateGradeDraftForm(opened.id, date, kind, topic, actual, weight, generation)
            }
        } catch (e: Exception) {
            error = e.message ?: "Notenentwurf konnte nicht gespeichert werden."
        }
    }

    val currentDraft = draft
    Page(
        "Note eintragen",
        "${subjects.find { it.id == sid }?.name.orEmpty()} · ${dateLabel(date)}",
    ) {
        if (subjects.isEmpty()) {
            Text("Lege zuerst ein Fach an.")
        } else {
            if (original == null) {
                Pick(
                    "Fach",
                    subjects.find { it.id == sid }?.name.orEmpty(),
                    subjects.map { it.name },
                ) { label ->
                    val subject = subjects.first { it.name == label }
                    if (subject.id != sid)
                        scope.launch {
                            try {
                                val changed =
                                    withContext(Dispatchers.IO) {
                                        if (currentDraft == null)
                                            repo.openGradeDraft(p.id, subject.id, null, generation)
                                        else
                                            repo.changeGradeDraft(
                                                currentDraft.id,
                                                subject.id,
                                                null,
                                                generation,
                                            )
                                    }
                                draft = changed
                            draftId = changed.id
                                sid = changed.subjectId
                                associatedId = changed.targetId
                                if (currentDraft == null) {
                                    val linked = d.assessments.find { it.id == changed.targetId }
                                    date =
                                        changed.date
                                            ?: linked?.date
                                            ?: java.time.LocalDate.now().toString()
                                    kind = changed.kind ?: linked?.kind
                                    topic =
                                        changed.topic
                                            ?: linked
                                                ?.let { it.material.ifBlank { it.notes } }
                                                .orEmpty()
                                    actual = changed.actual
                                    weight = changed.weight ?: linked?.weight?.toString() ?: "1"
                                } else {
                                    date = java.time.LocalDate.now().toString()
                                    kind = null
                                    topic = ""
                                    actual = null
                                    weight = "1"
                                }
                                hydrated = true
                                error = ""
                            } catch (e: Exception) {
                                error = e.message ?: "Fach konnte nicht gewechselt werden."
                            }
                        }
                }
            } else
                Text(
                    subjects.find { it.id == sid }?.name.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                )
            val choices = kinds(p).alphabetical()
            Pick("Art der Note", kind?.label.orEmpty(), choices.map { it.label }, tag = "kind") {
                label ->
                kind = choices.first { it.label == label }
            }
            if (original == null && currentDraft != null) {
                val past =
                    d.assessments
                        .filter {
                            it.subjectId == sid &&
                                it.actual == null &&
                                it.stage != Stage.GRADED &&
                                it.stage != Stage.CANCELLED &&
                                it.date <= java.time.LocalDate.now().toString()
                        }
                        .sortedByDescending { it.date }
                val labels =
                    past.associateBy { "${dateLabel(it.date)} · ${it.title} · ${it.id.take(6)}" }
                Pick(
                    "Frühere Probe zuordnen (optional)",
                    labels.entries.find { it.value.id == associatedId }?.key ?: "Neue Note",
                    listOf("Neue Note") + labels.keys,
                    tag = "associate",
                ) { choice ->
                    val exam = labels[choice]
                    scope.launch {
                        try {
                            val changed =
                                withContext(Dispatchers.IO) {
                                    repo.changeGradeDraft(
                                        currentDraft.id,
                                        sid,
                                        exam?.id,
                                        generation,
                                    )
                                }
                            draft = changed
                            draftId = changed.id
                            associatedId = exam?.id
                            if (exam != null) {
                                date = exam.date
                                kind = exam.kind
                                topic = exam.material.ifBlank { exam.notes }
                                weight = exam.weight.toString()
                            } else {
                                date = java.time.LocalDate.now().toString()
                                kind = null
                                topic = ""
                                weight = "1"
                            }
                            error = ""
                        } catch (e: Exception) {
                            error = e.message ?: "Probe konnte nicht zugeordnet werden."
                        }
                    }
                }
            }
            DateField("Datum", date) { date = it }
            Text(if (p.points) "Punkte" else "Note", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (p.points) 4 else 3,
            ) {
                (if (p.points) 0..15 else 1..6).forEach { value ->
                    FilterChip(
                        selected = actual == value,
                        onClick = { actual = value },
                        label = {
                            Text(value.toString(), style = MaterialTheme.typography.titleLarge)
                        },
                        modifier =
                            Modifier.weight(1f)
                                .sizeIn(minWidth = 64.dp, minHeight = 56.dp)
                                .testTag("grade-$value"),
                    )
                }
            }
            Field("Thema / Lernstoff (optional)", topic, 2) { topic = it }
            if (currentDraft != null) {
                AssessmentScannerButton(activity, currentDraft.id, "Probe fotografieren") {
                    scanning = true
                }
                TextButton({ activity.import(currentDraft.id) }) {
                    Text("Bild oder PDF hinzufügen")
                }
                if (documentCount > 0)
                    Text(
                        if (documentCount == 1) "1 Unterlage im Entwurf"
                        else "$documentCount Unterlagen im Entwurf",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            HelpPanel("Gewichtung") {
                Text("Nur ändern, wenn deine Lehrkraft eine andere Gewichtung nennt.")
                Field("Gewicht", weight) { weight = it }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Action(
                "Note speichern",
                {
                    val chosenKind = kind ?: return@Action
                    val grade = actual ?: return@Action
                    val opened = currentDraft ?: return@Action
                    saving = true
                    scope.launch {
                        try {
                            val id =
                                withContext(Dispatchers.IO) {
                                    repo.saveCompactGrade(
                                        opened.id,
                                        date,
                                        chosenKind,
                                        topic,
                                        grade,
                                        weight.replace(',', '.').toDouble(),
                                        generation,
                                    )
                                }
                            onSaved(id)
                        } catch (e: Exception) {
                            error = e.message ?: "Note konnte nicht gespeichert werden."
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = currentDraft != null && kind != null && actual != null && !saving,
            )
            TextButton(cancelAction, enabled = !saving) { Text("Abbrechen") }
        }
    }
    if (scanning && currentDraft != null)
        Dialog(
            onDismissRequest = { scanning = false },
            properties =
                DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TextButton({ scanning = false }) { Text("Zurück zur Note") }
                    Box(Modifier.weight(1f)) {
                        ScannerPage(activity, currentDraft.id) { scanning = false }
                    }
                }
            }
        }
}
