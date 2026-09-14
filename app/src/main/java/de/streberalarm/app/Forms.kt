package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.time.*

@Composable
fun ProfileForm(save: (Profile) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var state by rememberSaveable { mutableStateOf("Bayern") }
    var school by rememberSaveable { mutableStateOf(School.GYMNASIUM) }
    var year by rememberSaveable { mutableIntStateOf(2026) }
    var grade by rememberSaveable { mutableIntStateOf(8) }
    var track by rememberSaveable { mutableStateOf("NTG") }
    var term by rememberSaveable { mutableStateOf("Ganzes Schuljahr") }
    var error by remember { mutableStateOf("") }
    val points = school == School.GYMNASIUM && grade >= 12
    val terms =
        if (points) if (grade == 12) listOf("12/1", "12/2") else listOf("13/1", "13/2")
        else listOf("Ganzes Schuljahr", "1. Halbjahr", "2. Halbjahr")
    LaunchedEffect(terms) { if (term !in terms) term = terms.first() }
    Page(
        "Willkommen bei StreberAlarm",
        "Schritt ${step + 1} von 2 · Nimm deinen Stundenplan dazu.",
    ) {
        if (step == 0) {
            Panel("Deine Schule") {
                Pick("Bundesland", state, Defaults.states) { state = it }
                Pick("Schulart", school.label, School.entries.map { it.label }) {
                    val selectedSchool = School.entries.first { s -> s.label == it }
                    val selectedGrade = grade.coerceIn(Defaults.grades(selectedSchool))
                    school = selectedSchool
                    grade = selectedGrade
                    track = Defaults.tracks(selectedSchool, selectedGrade).first()
                }
                val tracks = Defaults.tracks(school, grade)
                if (school != School.GRUNDSCHULE)
                    Pick(
                        "Schulzweig",
                        Defaults.trackLabel(school, track),
                        tracks.map { Defaults.trackLabel(school, it) },
                    ) { chosen ->
                        track = tracks.first { Defaults.trackLabel(school, it) == chosen }
                    }
            }
            if (state != "Bayern")
                Panel("Noch nicht unterstützt") {
                    Text(
                        "Die Schulregeln gibt es zurzeit nur für Bayern. Andere Bundesländer kommen später dazu."
                    )
                }
            Text(
                if (school == School.GRUNDSCHULE)
                    "Für Klasse 3 und 4: Entdecke mit deiner Familie deinen nächsten Schulweg."
                else "Unsicher beim Schulzweig? Frag zu Hause oder in der Schule nach."
            )
            Action("Weiter", { step = 1 }, state == "Bayern")
        } else {
            Panel("Deine Klasse") {
                Pick(
                    "Deine Klasse",
                    "$grade. Klasse",
                    Defaults.grades(school).map { "$it. Klasse" },
                ) {
                    val selectedGrade = it.substringBefore('.').toInt()
                    val tracks = Defaults.tracks(school, selectedGrade)
                    grade = selectedGrade
                    if (track !in tracks) track = tracks.first()
                }
                if (school == School.REALSCHULE && grade < 7)
                    Text("In Klasse 5 und 6 gibt es noch keinen Schulzweig.")
                Pick(
                    "Schuljahr",
                    "$year/${(year + 1) % 100}",
                    (2000..2100).map { "$it/${(it + 1) % 100}" },
                ) {
                    year = it.substringBefore('/').toInt()
                }
                Pick("Für welche Zeit?", term, terms) { term = it }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Action(
                "Los geht’s",
                {
                    runCatching {
                            val p =
                                Profile(
                                    state = state,
                                    school = school,
                                    year = year,
                                    grade = grade,
                                    track = track,
                                    term = term,
                                    graduationYear = if (points) year + 14 - grade else null,
                                )
                            p.validate()
                            save(p)
                        }
                        .onFailure {
                            error = "Bitte prüfe deine Klasse und dein Schuljahr noch einmal."
                        }
                },
                state == "Bayern",
            )
            TextButton({ step = 0 }) { Text("Zurück zur Schulauswahl") }
        }
    }
}

@Composable
fun SubjectForm(p: Profile, existing: Subject?, delete: () -> Unit, save: (Subject) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var count by rememberSaveable {
        mutableStateOf(existing?.annualSchoolworks?.toString().orEmpty())
    }
    var core by rememberSaveable { mutableStateOf(existing?.core ?: false) }
    var promotion by rememberSaveable { mutableStateOf(existing?.promotion ?: true) }
    var advanced by rememberSaveable { mutableStateOf(existing?.advanced ?: false) }
    var exam by rememberSaveable { mutableStateOf(existing?.examSubject ?: false) }
    var written by rememberSaveable {
        mutableStateOf((existing?.smallWrittenWeight ?: 1.0).toString())
    }
    var oral by rememberSaveable { mutableStateOf((existing?.oralWeight ?: 1.0).toString()) }
    var practical by rememberSaveable {
        mutableStateOf((existing?.practicalWeight ?: 1.0).toString())
    }
    var error by remember { mutableStateOf("") }
    Page(if (existing == null) "Fach hinzufügen" else "Fach bearbeiten") {
        Field("Fachname", name) { name = it }
        HelpPanel("Schulregeln für dieses Fach") {
            Check("Kern-/Hauptfach", core) { core = it }
            Check("Vorrückungsfach (Pflicht-/Wahlpflichtfach)", promotion) { promotion = it }
            if (p.points) {
                Check("Leistungsfach", advanced) { advanced = it }
                Check("Abiturprüfungsfach", exam) { exam = it }
            }
            if (p.school == School.GYMNASIUM && !p.points)
                Field("Schulaufgaben im gesamten Schuljahr (0 = keine)", count) { count = it }
            HelpPanel("Gewichtung anpassen (optional)") {
                Text(
                    "Nur von der Schule mitgeteilte Gewichtungen eintragen. 1 bedeutet: keine zusätzliche Änderung. Schulaufgaben werden automatisch nach der Schulart gewichtet."
                )
                Field("Kleine schriftliche Leistungen", written) { written = it }
                Field("Mündliche Leistungen", oral) { oral = it }
                Field("Praktische Leistungen", practical) { practical = it }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Action("Fach speichern") {
            runCatching {
                    val weights =
                        listOf(written, oral, practical).map { it.replace(',', '.').toDouble() }
                    require(name.isNotBlank() && weights.all { it.isFinite() && it > 0 })
                    require(count.isBlank() || count.toInt() in 0..12)
                    save(
                        Subject(
                            id = existing?.id ?: id(),
                            profileId = p.id,
                            name = name.trim(),
                            annualSchoolworks = count.toIntOrNull(),
                            core = core,
                            promotion = promotion,
                            weightsConfirmed = existing?.weightsConfirmed ?: false,
                            advanced = advanced,
                            examSubject = exam,
                            smallWrittenWeight = weights[0],
                            oralWeight = weights[1],
                            practicalWeight = weights[2],
                        )
                    )
                }
                .onFailure { error = "Fachname, Schulaufgabenanzahl und positive Gewichte prüfen." }
        }
        if (existing != null)
            TextButton({ confirmDelete = true }) {
                Text("Fach löschen", color = MaterialTheme.colorScheme.error)
            }
    }
    if (existing != null) {
        if (confirmDelete)
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Fach vollständig löschen?") },
                text = {
                    Text(
                        "Alle zugehörigen Leistungen, offiziellen Noten, Lernphasen, Dokumente und Stundenplaneinträge werden entfernt."
                    )
                },
                confirmButton = {
                    TextButton({
                        confirmDelete = false
                        delete()
                    }) {
                        Text("Fach löschen")
                    }
                },
                dismissButton = { TextButton({ confirmDelete = false }) { Text("Abbrechen") } },
            )
    }
}

@Composable
fun ExamForm(
    p: Profile,
    subjects: List<Subject>,
    existing: Assessment?,
    initialSubject: String,
    grading: Boolean = false,
    attachments: @Composable () -> Unit = {},
    save: (Assessment) -> Unit,
) {
    var sid by rememberSaveable {
        mutableStateOf(
            existing?.subjectId
                ?: subjects.find { it.id == initialSubject }?.id
                ?: subjects.firstOrNull()?.id.orEmpty()
        )
    }
    var date by rememberSaveable {
        mutableStateOf(existing?.date ?: LocalDate.now().plusDays(7).toString())
    }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: kinds(p).first()) }
    var stage by rememberSaveable {
        mutableStateOf(if (grading) Stage.GRADED else existing?.stage ?: Stage.PLANNED)
    }
    var material by rememberSaveable { mutableStateOf(existing?.learningNotes.orEmpty()) }
    var actual by rememberSaveable { mutableStateOf(existing?.actual?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(existing?.weight?.toString() ?: "1") }
    var error by remember { mutableStateOf("") }
    Page(
        if (grading) "Welche Note ist es?"
        else if (existing == null) "Test hinzufügen" else "Test bearbeiten",
        if (grading) "${subjects.find { it.id == sid }?.name.orEmpty()} · ${dateLabel(date)}"
        else null,
    ) {
        if (!grading) {
            Pick(
                "Fach",
                subjects.find { it.id == sid }?.name.orEmpty(),
                subjects.map { it.name },
            ) { v ->
                sid = subjects.first { it.name == v }.id
            }
            Pick("Was ist es?", kind.label, kinds(p).map { it.label }) { v ->
                kind = kinds(p).first { it.label == v }
            }
            DateField("Datum", date) { date = it }
            Field("Was kommt / kam dran?", material, 2) { material = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Stage.PLANNED, Stage.WRITTEN, Stage.GRADED).forEach { choice ->
                    FilterChip(
                        selected = stage == choice,
                        onClick = {
                            stage = choice
                            if (stage != Stage.GRADED) actual = ""
                        },
                        label = { Text(if (choice == Stage.GRADED) "Note da" else choice.label) },
                        modifier = Modifier.testTag("stage-${choice.name}"),
                    )
                }
            }
        }
        if (stage == Stage.GRADED) {
            Text(if (p.points) "Deine Punkte" else "Deine Note")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (p.points) 4 else 3,
            ) {
                (if (p.points) 0..15 else 1..6).forEach { value ->
                    FilterChip(
                        selected = actual == value.toString(),
                        onClick = { actual = value.toString() },
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
        }
        if (grading) attachments()
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Action("Test speichern") {
            runCatching {
                    require(subjects.any { it.id == sid })
                    val day = LocalDate.parse(date)
                    require(
                        day >= LocalDate.of(p.year, 8, 1) && day <= LocalDate.of(p.year + 1, 9, 30)
                    )
                    val e =
                        Assessment(
                            id = existing?.id ?: id(),
                            subjectId = sid,
                            title =
                                existing?.title
                                    ?: run {
                                        "${kind.label} · ${subjects.first { it.id == sid }.name}"
                                    },
                            date = date,
                            time = existing?.time?.takeIf { date == existing.date },
                            kind = kind,
                            stage = stage,
                            material = material,
                            notes = "",
                            estimate = existing?.estimate,
                            actual = if (stage == Stage.GRADED) actual.toInt() else null,
                            weight = weight.replace(',', '.').toDouble(),
                        )
                    e.validate(p.points)
                    save(e)
                }
                .onFailure {
                    error = "Bitte Fach, Datum im Schuljahr, Gewichtung und gültige Note prüfen."
                }
        }
        if (!grading)
            HelpPanel("Mehr Optionen") {
                Text("Gewicht nur ändern, wenn deine Lehrkraft es dir gesagt hat.")
                Field("Gewicht", weight) { weight = it }
                Check("Test fällt aus", stage == Stage.CANCELLED) {
                    stage = if (it) Stage.CANCELLED else Stage.PLANNED
                    actual = ""
                }
            }
    }
}

@Composable
fun OfficialForm(p: Profile, s: Subject, d: SchoolData, save: (Official) -> Unit) {
    var period by rememberSaveable {
        mutableStateOf(
            if (p.points) p.term
            else if (p.school == School.GRUNDSCHULE && p.grade == 4) Transfer.PERIOD
            else "Jahreszeugnis"
        )
    }
    var value by rememberSaveable {
        mutableStateOf(
            d.officials
                .find { it.subjectId == s.id && it.period == period }
                ?.value
                ?.toString()
                .orEmpty()
        )
    }
    var omitted by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page("Offizielles Ergebnis", s.name) {
        Pick(
            "Zeugnis / Halbjahr",
            period,
            if (p.points) listOf("12/1", "12/2", "13/1", "13/2")
            else if (p.school == School.GRUNDSCHULE && p.grade == 4)
                listOf(Transfer.PERIOD, "Zwischenzeugnis", "Jahreszeugnis")
            else listOf("Zwischenzeugnis", "Jahreszeugnis", "Abschlusszeugnis"),
        ) {
            period = it
            value =
                d.officials
                    .find { o -> o.subjectId == s.id && o.period == it }
                    ?.value
                    ?.toString()
                    .orEmpty()
        }
        GradePick(if (p.points) "Punkte im Halbjahr" else "Deine Zeugnisnote", value, p.points) {
            value = it
        }
        if (!p.points)
            Check("Nicht feststellbare Leistung laut Zeugnisbemerkung (wie 6)", omitted) {
                omitted = it
                if (it) value = ""
            }
        Text(
            "Trage nur die Note ein, die wirklich auf deinem Zeugnis steht. Du kannst das Feld auch offen lassen."
        )
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Action("Offizielle Note speichern") {
            runCatching {
                    val n = value.takeIf { it.isNotBlank() }?.toInt()
                    require(n == null || n in if (p.points) 0..15 else 1..6)
                    save(Official(subjectId = s.id, period = period, value = n, omitted = omitted))
                }
                .onFailure { error = "Gültige Note eingeben oder das Feld leer lassen." }
        }
    }
}

@Composable
fun DayOffForm(p: Profile, save: (DayOff) -> Unit) {
    var from by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var to by rememberSaveable { mutableStateOf(from) }
    var reason by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Page("Unterrichtsfreie Zeit") {
        DateField("Von", from) { from = it }
        DateField("Bis einschließlich", to) { to = it }
        Field("Grund / Feiertag", reason) { reason = it }
        if (error.isNotBlank()) Text(error)
        Action("Freie Zeit speichern") {
            runCatching {
                    require(LocalDate.parse(from) <= LocalDate.parse(to) && reason.isNotBlank())
                    save(DayOff(profileId = p.id, from = from, to = to, reason = reason))
                }
                .onFailure { error = "Zeitraum und Grund prüfen." }
        }
    }
}

@Composable
fun ExceptionForm(p: Profile, d: SchoolData, save: (ExceptionLesson) -> Unit) {
    val ss = d.subjects.filter { it.profileId == p.id }
    var sid by rememberSaveable { mutableStateOf(ss.firstOrNull()?.id.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var start by rememberSaveable { mutableStateOf("08:00") }
    var end by rememberSaveable { mutableStateOf("09:30") }
    var cancelled by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    Page("Ausfall / Zusatzunterricht") {
        Pick("Fach", ss.find { it.id == sid }?.name.orEmpty(), ss.map { it.name }) { v ->
            sid = ss.first { it.name == v }.id
        }
        Check("Unterricht fällt aus", cancelled) { cancelled = it }
        Text(
            if (cancelled) "Überlappende Stunden werden entfernt."
            else "Zusätzlicher Termin zählt auch an eingetragenen freien Tagen."
        )
        DateField("Datum", date) { date = it }
        ClockField("Beginn", start) { start = it }
        ClockField("Ende", end) { end = it }
        if (error.isNotBlank()) Text(error)
        Action("Änderung speichern") {
            runCatching {
                    LocalDate.parse(date)
                    require(sid.isNotBlank() && LocalTime.parse(start) < LocalTime.parse(end))
                    save(
                        ExceptionLesson(
                            subjectId = sid,
                            date = date,
                            start = start,
                            end = end,
                            cancelled = cancelled,
                        )
                    )
                }
                .onFailure { error = "Fach, Datum und Uhrzeiten prüfen." }
        }
    }
}
