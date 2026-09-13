package de.streberalarm.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.streberalarm.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

fun number(n: Double?) = n?.let { String.format(Locale.GERMANY, "%.2f", it) } ?: "–"

fun dateLabel(date: String) =
    runCatching {
            LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMAN))
        }
        .getOrDefault(date)

@Composable
fun Page(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun Panel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().monitor(),
        shape = monitorShape(26.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Ink,
            ),
    ) {
        Column(
            Modifier.padding(18.dp)
                .then(
                    if (LocalLook.current == AppLook.HACKER) Modifier.testTag("crt-monitor")
                    else Modifier
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null)
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            content()
        }
    }
}

@Composable
fun Field(label: String, value: String, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = lines == 1,
        minLines = lines,
        shape = monitorShape(18.dp),
    )
}

@Composable
fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(value, onChange)
        Text(label, Modifier.weight(1f))
    }
}

@Composable fun Action(text: String, onClick: () -> Unit) = Action(text, onClick, true)

@Composable
fun Action(text: String, onClick: () -> Unit, enabled: Boolean) {
    val look = LocalLook.current
    val shape = lookShape(20.dp)
    val gradient =
        when (look) {
            AppLook.SOCIAL -> listOf(Color(0xFF993066), Color(0xFFAC3F36))
            AppLook.STREAMER -> listOf(Color(0xFFDFB6FF), Color(0xFF79E7FA))
            AppLook.HACKER -> listOf(Color(0xFF71FF48), Color(0xFFA1FF70))
            AppLook.ORBIT -> listOf(Color(0xFFA1D9FF), Color(0xFFCCC2FF))
            else -> listOf(Green, Green)
        }
    Button(
        onClick,
        Modifier.fillMaxWidth()
            .heightIn(min = 58.dp)
            .then(
                if (enabled && look != AppLook.CLASSIC)
                    Modifier.clip(shape).background(Brush.horizontalGradient(gradient))
                else Modifier
            ),
        enabled = enabled,
        shape = shape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (look == AppLook.CLASSIC) Green else Color.Transparent
            ),
        elevation =
            ButtonDefaults.buttonElevation(
                defaultElevation = if (look == AppLook.CLASSIC) 3.dp else 0.dp,
                pressedElevation = 0.dp,
            ),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
fun Source(url: String) {
    val uri = LocalUriHandler.current
    TextButton({ uri.openUri(url) }) { Text("Quelle: ${url.substringAfterLast('/')} ↗") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreberAlarm(activity: MainActivity) {
    val data by activity.repo.state.collectAsState()
    val message by activity.message.collectAsState()
    val busy by activity.busy.collectAsState()
    val estimateRequest by activity.estimateRequest.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("home") }
    var selection by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tab) { if (tab !in 0..2) tab = 0 }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snack.showSnackbar(it)
            activity.message.value = null
        }
    }
    fun open(where: String, id: String = "") {
        selection = id
        page = where
    }
    fun update(block: (SchoolData) -> SchoolData) {
        activity.action { activity.repo.update(block) }
    }
    LaunchedEffect(estimateRequest, data != null) {
        val examId = estimateRequest
        val current = data
        if (examId != null && current != null) {
            val exam = current.assessments.find { it.id == examId && it.stage != Stage.CANCELLED }
            val profileId = current.subjects.find { it.id == exam?.subjectId }?.profileId
            if (exam != null && profileId != null) {
                update { it.copy(activeProfileId = profileId) }
                open("estimate", exam.id)
            }
            activity.estimateRequest.value = null
        }
    }
    AppearanceTheme(activity) {
        val d = data
        val p = d?.active()
        BackHandler(page != "home") { page = "home" }
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopAppBar(
                    title = { Text("StreberAlarm", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        if (page != "home")
                            IconButton({ page = "home" }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück")
                            }
                    },
                    actions = {
                        if (p != null)
                            IconButton({ open("settings") }) {
                                Icon(Icons.Outlined.Settings, "Einstellungen")
                            }
                    },
                )
            },
            bottomBar = {
                if (p != null && page == "home") {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val icons =
                            listOf(
                                Icons.Outlined.WbSunny,
                                Icons.AutoMirrored.Outlined.MenuBook,
                                Icons.AutoMirrored.Outlined.EventNote,
                                Icons.Outlined.CalendarViewWeek,
                            )
                        listOf(0 to "Heute", 2 to "Lernen", 1 to "Meine Noten").forEach { (i, label)
                            ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav-$label"),
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(icons[i], null) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (d == null)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                else if (p == null || page == "profile")
                    ProfileForm { profile ->
                        update { old ->
                            old.copy(
                                profiles = old.profiles + profile,
                                activeProfileId = profile.id,
                                subjects = old.subjects + Defaults.subjects(profile),
                                daysOff = old.daysOff + Defaults.holidays(profile),
                            )
                        }
                        page = "home"
                    }
                else
                    when (page) {
                        "settings" ->
                            SettingsPage(
                                activity,
                                d,
                                { open("profile") },
                                { id -> update { it.copy(activeProfileId = id) } },
                                { open("rules") },
                                { open("timetable") },
                            )
                        "subject" ->
                            d.subjects
                                .find { it.id == selection }
                                ?.let {
                                    SubjectPage(
                                        p,
                                        it,
                                        d,
                                        { open("subjectEdit", it.id) },
                                        { open("exam", it) },
                                        { open("examEdit", "new:${it.id}") },
                                        { open("official", it.id) },
                                    )
                                }
                        "subjectEdit" ->
                            SubjectForm(
                                p,
                                d.subjects.find { it.id == selection },
                                {
                                    activity.action { activity.repo.deleteSubject(selection) }
                                    page = "home"
                                },
                            ) { s ->
                                update {
                                    it.copy(
                                        subjects = it.subjects.filterNot { a -> a.id == s.id } + s
                                    )
                                }
                                open("subject", s.id)
                            }
                        "exam" ->
                            d.assessments
                                .find { it.id == selection }
                                ?.let { e ->
                                    ExamPage(
                                        activity,
                                        d,
                                        e,
                                        { open("examEdit", e.id) },
                                        { open("examGrade", e.id) },
                                        { open("estimate", e.id) },
                                        { open("scanner", e.id) },
                                        { open("document", it) },
                                        {
                                            activity.action { activity.repo.deleteExam(e.id) }
                                            page = "home"
                                        },
                                    )
                                }
                        "estimate" ->
                            d.assessments
                                .find { it.id == selection }
                                ?.let { e ->
                                    EstimatePage(p, e) { grade ->
                                        update { current ->
                                            current.copy(
                                                assessments =
                                                    current.assessments.map {
                                                        if (it.id == e.id) it.copy(estimate = grade)
                                                        else it
                                                    },
                                                delivered =
                                                    current.delivered +
                                                        if (grade == null)
                                                            setOf(EstimatePrompts.skipKey(e))
                                                        else emptySet(),
                                            )
                                        }
                                        open("exam", e.id)
                                    }
                                }
                        "plan" ->
                            QuickPlanForm(p, d.subjects.filter { it.profileId == p.id }) { e ->
                                update { it.copy(assessments = it.assessments + e) }
                                open("exam", e.id)
                            }
                        "examEdit",
                        "examGrade" ->
                            ExamForm(
                                p,
                                d.subjects.filter { it.profileId == p.id }.alphabetical(),
                                d.assessments.find { it.id == selection },
                                selection.removePrefix("new:"),
                                grading = page == "examGrade",
                                attachments = {
                                    d.assessments
                                        .find { it.id == selection }
                                        ?.let { e -> GradeAttachments(activity, d, e) }
                                },
                            ) { e ->
                                update {
                                    it.copy(
                                        assessments =
                                            it.assessments.filterNot { a -> a.id == e.id } + e
                                    )
                                }
                                open("exam", e.id)
                            }
                        "transfer" ->
                            TransferForm(p, d) { transform ->
                                update(transform)
                                page = "home"
                            }
                        "official" ->
                            d.subjects
                                .find { it.id == selection }
                                ?.let { s ->
                                    OfficialForm(p, s, d) { o ->
                                        update {
                                            it.copy(
                                                officials =
                                                    it.officials.filterNot { a ->
                                                        a.subjectId == o.subjectId &&
                                                            a.period == o.period
                                                    } + o
                                            )
                                        }
                                        open("subject", s.id)
                                    }
                                }
                        "timetable" ->
                            TimetablePage(
                                p,
                                d,
                                { day -> open("timetableEdit", day.toString()) },
                                { open("dayOff") },
                                { open("exception") },
                                { id ->
                                    update {
                                        it.copy(
                                            daysOff = it.daysOff.filterNot { a -> a.id == id },
                                            exceptions = it.exceptions.filterNot { a -> a.id == id },
                                        )
                                    }
                                },
                            )
                        "timetableEdit" ->
                            TimetableForm(
                                p,
                                d,
                                initialDay = selection.toIntOrNull() ?: 1,
                                addSubject = { s -> update { it.copy(subjects = it.subjects + s) } },
                            ) { t ->
                                update {
                                    it.copy(
                                        timetables =
                                            it.timetables.filterNot { a ->
                                                a.profileId == t.profileId &&
                                                    a.validFrom == t.validFrom
                                            } + t
                                    )
                                }
                                page = "timetable"
                            }
                        "dayOff" ->
                            DayOffForm(p) { o ->
                                update { it.copy(daysOff = it.daysOff + o) }
                                page = "timetable"
                            }
                        "exception" ->
                            ExceptionForm(p, d) { e ->
                                update { it.copy(exceptions = it.exceptions + e) }
                                page = "timetable"
                            }
                        "rules" ->
                            RulesPage(p, d) { i ->
                                update {
                                    it.copy(
                                        graduation = it.graduation + ("${p.id}:${i.procedure}" to i)
                                    )
                                }
                            }
                        "scanner" -> ScannerPage(activity, selection) { open("exam", selection) }
                        "document" ->
                            d.documents
                                .find { it.id == selection }
                                ?.let { DocumentPage(activity, it) }
                        else ->
                            when (tab) {
                                0 ->
                                    TodayPage(
                                        activity,
                                        p,
                                        d,
                                        { open("exam", it) },
                                        { open("plan") },
                                        { open("timetable") },
                                        { open("estimate", it) },
                                    )
                                1 ->
                                    SubjectsPage(
                                        p,
                                        d,
                                        { open("subject", it) },
                                        { open("subjectEdit") },
                                        { open("transfer") },
                                    )
                                2 ->
                                    LearningPage(
                                        activity,
                                        p,
                                        d,
                                        { open("exam", it) },
                                        { open("plan") },
                                        { open("examEdit", "new:") },
                                        { open("examGrade", it) },
                                        { open("estimate", it) },
                                    )
                            }
                    }
            }
        }
    }
}

@Composable
fun TodayPage(
    a: MainActivity,
    p: Profile,
    d: SchoolData,
    exam: (String) -> Unit,
    add: () -> Unit,
    timetable: () -> Unit,
    estimate: (String) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val subjects = d.subjects.filter { it.profileId == p.id }
    val upcoming =
        d.assessments
            .filter { e ->
                subjects.any { it.id == e.subjectId } &&
                    e.stage == Stage.PLANNED &&
                    e.date >= today.toString()
            }
            .sortedBy { it.date + it.time.orEmpty() }
    Page("Dein Tag", today.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN))) {
        val overview = remember(p, d) { GradeOverviewCalculator.calculate(p, d) }
        OverallAveragePanel(p, overview)
        val running = d.studies.firstOrNull { it.end == null }
        if (running != null)
            Panel("Lerntimer läuft") {
                Text(
                    d.assessments.find { it.id == running.assessmentId }?.title.orEmpty(),
                    fontWeight = FontWeight.Bold,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StudyClock(running.seconds(now), true) { a.action { a.repo.stop() } }
                }
                if (running.boot != a.repo.boot())
                    Text("Gerät neu gestartet: Bitte die Lernzeit nach dem Stoppen prüfen.")
                TextButton({ exam(running.assessmentId) }) { Text("Zur Lernkarte") }
            }
        EstimatePrompts.due(d, SchoolZonedTime.fromEpochMillis(now, ZoneId.systemDefault().id))
            .firstOrNull()
            ?.let { e ->
                Panel("Wie lief deine Probe?") {
                    Text(e.title)
                    Action("Gefühlte Note eintragen", { estimate(e.id) })
                }
            }
        upcoming.firstOrNull()?.let { e ->
            Text(
                "Deine nächste Probe",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            ExamCard(d, e) { exam(e.id) }
            if (running == null) Action("Jetzt lernen", { exam(e.id) })
        } ?: Panel { Text("Gerade ist keine Probe geplant. Genieße deine freie Zeit!") }
        OutlinedButton(
            add,
            Modifier.fillMaxWidth(),
            shape =
                if (LocalLook.current == AppLook.HACKER || LocalLook.current == AppLook.PIXEL)
                    lookShape(0.dp)
                else ButtonDefaults.outlinedShape,
        ) {
            Icon(Icons.Outlined.Add, null)
            Text("Probe planen")
        }
        val lessons =
            remember(p, d.timetables, d.daysOff, d.exceptions, d.subjects, today) {
                dailyLessons(d, p, today)
            }
        Panel("Heute im Unterricht") {
            if (lessons.isEmpty())
                Text(
                    if (d.timetables.none { it.profileId == p.id })
                        "Dein Stundenplan fehlt noch. Trage ihn einmal ein."
                    else "Heute ist kein Unterricht eingetragen."
                )
            lessons.forEach { lesson ->
                val subject = subjects.find { it.id == lesson.subjectId }
                val average = subject?.let { Grades.calculate(p, it, d.assessments).value }
                Surface(
                    color = if (lesson.isBreak) BreakTint else themedSubjectTint(subject),
                    shape = lookShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("today-lesson-${lesson.id}"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (lesson.isBreak) "Pause" else subject?.name.orEmpty(),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${lesson.start}–${lesson.end}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!lesson.isBreak && average != null)
                            Text("Ø ${number(average)}", fontWeight = FontWeight.Bold)
                    }
                }
            }
            TextButton(timetable) { Text("Stundenplan") }
        }
        if (!ReminderScheduler.allowed(a))
            TextButton({ a.requestNotifications() }) { Text("Lernerinnerungen einschalten") }
    }
}

@Composable
fun ExamCard(d: SchoolData, e: Assessment, click: () -> Unit) {
    val s = d.subjects.find { it.id == e.subjectId }
    Card(
        onClick = click,
        modifier = Modifier.fillMaxWidth().monitor().testTag("exam-${e.id}"),
        border = lookBorder(),
        colors = CardDefaults.cardColors(containerColor = themedSubjectTint(s), contentColor = Ink),
        shape = monitorShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    s?.name.orEmpty(),
                    Modifier.weight(1f),
                    color = Ink,
                    fontWeight = FontWeight.Bold,
                )
                Text(dateLabel(e.date), style = MaterialTheme.typography.labelLarge)
            }
            if (e.title != "${e.kind.label} · ${s?.name}")
                Text(e.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${e.kind.label} · ${e.stage.label}${e.actual?.let{" · $it"}.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (e.stage == Stage.PLANNED && s != null) {
                if (d.timetables.none { it.profileId == s.profileId })
                    Text(
                        "Fülle deinen Stundenplan aus. Dann zählen wir die Stunden bis zum Test.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                else
                    Text(
                        "Noch ${Lessons.remaining(d,e,LocalDateTime.now()).size}× ${s.name}",
                        fontWeight = FontWeight.Medium,
                    )
            }
        }
    }
}

@Composable
fun SubjectsPage(
    p: Profile,
    d: SchoolData,
    open: (String) -> Unit,
    add: () -> Unit,
    transfer: () -> Unit,
) {
    Page("Meine Noten") {
        if (p.school == School.GRUNDSCHULE) TransferPanel(p, d, transfer)
        else GradeOverviewPanel(p, d)
        d.subjects
            .filter { it.profileId == p.id }
            .alphabetical()
            .forEach { s ->
                val calc = Grades.calculate(p, s, d.assessments)
                Card(
                    onClick = { open(s.id) },
                    modifier = Modifier.fillMaxWidth().monitor().testTag("subject-${s.id}"),
                    border = lookBorder(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = themedSubjectTint(s),
                            contentColor = Ink,
                        ),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoStories, null, Modifier.size(32.dp), tint = Ink)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            Text(
                                if (p.school == School.GRUNDSCHULE)
                                    if (Transfer.subjectIndex(s.name) != null) "Übertrittsfach"
                                    else "Weiteres Fach"
                                else subjectCategory(s),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                if (
                                    calc.value == null &&
                                        d.assessments.any {
                                            it.subjectId == s.id &&
                                                it.actual != null &&
                                                it.stage != Stage.CANCELLED
                                        }
                                )
                                    "Für deinen Schnitt fehlen Angaben"
                                else learningHint(p.points, calc.value),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            number(calc.value),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Green,
                        )
                    }
                }
            }
        Text(
            "Die Hinweise helfen dir beim Lernen. Sie sind keine Versetzungsentscheidung.",
            style = MaterialTheme.typography.bodySmall,
        )
        Action("Fach hinzufügen", add)
        HelpPanel("Was bedeuten die Farben?") {
            Text(
                if (p.school == School.GRUNDSCHULE)
                    "Violett: Übertrittsfach (Deutsch, Mathematik, HSU)"
                else "Violett: Haupt-/Kernfach"
            )
            if (p.school != School.GRUNDSCHULE)
                Text(
                    "Blau: weiteres Vorrückungsfach – zählt für das Vorrücken in die nächste Klasse."
                )
            Text("Grau: weiteres Fach – kein Vorrückungsfach.")
            Text(
                "Ist ein Fach beides, bleibt es violett. Die Farben folgen der Einordnung im Fach und bewerten keine Noten."
            )
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
) {
    var scenario by rememberSaveable { mutableStateOf(false) }
    val calc = Grades.calculate(p, s, d.assessments, scenario)
    Page(s.name, subjectCategory(s)) {
        Panel(if (scenario) "Mit deinen geschätzten Noten" else "Dein Notenschnitt") {
            Text(
                number(calc.value) + (if (p.points) " Punkte" else ""),
                style = MaterialTheme.typography.displayMedium,
                color = Green,
            )
            Check("Mit meinen geschätzten Noten rechnen", scenario) { scenario = it }
            Text("Das ist dein berechneter Schnitt, keine Zeugnisnote.")
            if (calc.warnings.isNotEmpty())
                Text("Für den Schnitt fehlen noch Angaben. Schau unter „So wird gerechnet“ nach.")
            HelpPanel("So wird gerechnet") {
                Text(calc.explanation)
                calc.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                calc.source?.let { Source(it) }
            }
        }
        Action("Test oder Note hinzufügen", add)
        OutlinedButton(edit, Modifier.fillMaxWidth()) { Text("Fach und Gewichtungen bearbeiten") }
        OutlinedButton(official, Modifier.fillMaxWidth()) { Text("Offizielle Note eintragen") }
        d.officials
            .filter { it.subjectId == s.id }
            .forEach { Text("${it.period}: ${it.value ?: "unbekannt"}") }
        Text("Notenverlauf", style = MaterialTheme.typography.titleLarge)
        d.assessments
            .filter { it.subjectId == s.id }
            .sortedByDescending { it.date }
            .forEach { e -> ExamCard(d, e) { exam(e.id) } }
    }
}

@Composable
fun ExamsPage(p: Profile, d: SchoolData, open: (String) -> Unit, add: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("Alle") }
    Page("Deine Tests", "Planen, lernen, Note eintragen.") {
        Action("Test hinzufügen", add, d.subjects.any { it.profileId == p.id })
        Pick("Anzeigen", filter, listOf("Alle") + Stage.entries.map { it.label }) { filter = it }
        val rows =
            d.assessments
                .filter { e ->
                    d.subjects.any { it.id == e.subjectId && it.profileId == p.id } &&
                        (filter == "Alle" || e.stage.label == filter)
                }
                .sortedByDescending { it.date }
        if (rows.isEmpty())
            Text("Hier erscheinen deine geplanten und nachträglich eingetragenen Leistungen.")
        rows.forEach { e -> ExamCard(d, e) { open(e.id) } }
    }
}

@Composable
fun ExamPage(
    a: MainActivity,
    d: SchoolData,
    e: Assessment,
    edit: () -> Unit,
    grade: () -> Unit,
    estimate: () -> Unit,
    scan: () -> Unit,
    document: (String) -> Unit,
    delete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var correction by remember { mutableStateOf<Study?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val sessions = d.studies.filter { it.assessmentId == e.id }
    var editMaterial by rememberSaveable(e.id) { mutableStateOf(false) }
    var section by rememberSaveable(e.id) { mutableStateOf("Lernstoff") }
    Page("Deine Probe") {
        ProbeCard(a, d, e, grade, estimate)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(edit) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Test bearbeiten")
            }
            TextButton({ confirm = true }) {
                Text("Löschen", color = MaterialTheme.colorScheme.error)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Lernstoff", "Unterlagen", "Lernzeiten").forEach { name ->
                FilterChip(
                    selected = section == name,
                    onClick = { section = name },
                    label = { Text(name) },
                )
            }
        }
        if (section == "Lernstoff")
            Panel {
                Text(e.learningNotes.ifBlank { "Was kommt dran? Ergänze deinen Lernstoff." })
                TextButton({ editMaterial = true }) {
                    Text(
                        if (e.learningNotes.isBlank()) "Lernstoff ergänzen" else "Lernstoff ändern"
                    )
                }
            }
        if (section == "Lernzeiten")
            Panel {
                if (sessions.isEmpty())
                    Text("Deine Lernzeiten erscheinen hier, sobald du die Uhr startest.")
                sessions
                    .sortedByDescending { it.start }
                    .forEach { s ->
                        Text(
                            "${Instant.ofEpochMilli(s.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))} · ${s.seconds(now)/60} Min.${if(s.end==null)" · läuft" else ""}"
                        )
                        if (s.correction.isNotBlank())
                            Text(s.correction, style = MaterialTheme.typography.bodySmall)
                        if (s.end != null)
                            TextButton({ correction = s }) { Text("Lernzeit ändern") }
                    }
            }
        if (section == "Unterlagen")
            Panel {
                AssessmentScannerButton(a, e.id, "Arbeit scannen", scan)
                OutlinedButton({ a.import(e.id) }, Modifier.fillMaxWidth()) {
                    Text("Bild oder PDF hinzufügen")
                }
                d.documents
                    .filter { it.assessmentId == e.id }
                    .forEach { doc ->
                        TextButton({ document(doc.id) }) {
                            Icon(Icons.Outlined.Description, null)
                            Spacer(Modifier.width(8.dp))
                            Text(doc.name)
                        }
                    }
            }
    }
    if (editMaterial) {
        var text by rememberSaveable(e.id) { mutableStateOf(e.learningNotes) }
        AlertDialog(
            onDismissRequest = { editMaterial = false },
            title = { Text("Dein Lernstoff") },
            text = { Field("Was kommt / kam dran?", text, 3) { text = it } },
            confirmButton = {
                TextButton({
                    a.action {
                        a.repo.update { current ->
                            current.copy(
                                assessments =
                                    current.assessments.map { old ->
                                        if (old.id == e.id) old.copy(material = text, notes = "")
                                        else old
                                    }
                            )
                        }
                    }
                    editMaterial = false
                }) {
                    Text("Lernstoff speichern")
                }
            },
            dismissButton = { TextButton({ editMaterial = false }) { Text("Abbrechen") } },
        )
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Prüfung löschen?") },
            text = {
                Text("Noten, Lernphasen und zugehörige Dokumente werden aus der App entfernt.")
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    delete()
                }) {
                    Text("Löschen")
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Abbrechen") } },
        )
    correction?.let { s ->
        var minutes by remember { mutableStateOf((s.seconds(now) / 60).toString()) }
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { correction = null },
            title = { Text("Lernzeit berichtigen") },
            text = {
                Column {
                    Field("Dauer in Minuten", minutes) { minutes = it }
                    Field("Warum änderst du die Zeit?", reason) { reason = it }
                }
            },
            confirmButton = {
                TextButton(
                    {
                        a.action {
                            a.repo.update {
                                it.copy(
                                    studies =
                                        it.studies.map { old ->
                                            if (old.id == s.id)
                                                old.copy(
                                                    correctedMinutes = minutes.toInt(),
                                                    correction = reason,
                                                )
                                            else old
                                        }
                                )
                            }
                        }
                        correction = null
                    },
                    enabled = minutes.toIntOrNull() in 0..10080 && reason.isNotBlank(),
                ) {
                    Text("Speichern")
                }
            },
            dismissButton = { TextButton({ correction = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
fun TimetablePage(
    p: Profile,
    d: SchoolData,
    edit: (Int) -> Unit,
    dayOff: () -> Unit,
    exception: () -> Unit,
    delete: (String) -> Unit,
) {
    var day by rememberSaveable { mutableIntStateOf(1) }
    var week by rememberSaveable { mutableIntStateOf(1) }
    val tables = d.timetables.filter { it.profileId == p.id }.sortedByDescending { it.validFrom }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val t =
        tables.find { it.id == chosen }
            ?: tables.firstOrNull { it.validFrom <= LocalDate.now().toString() }
            ?: tables.lastOrNull()
    Page("Dein Stundenplan", "Tippe auf eine Stunde, um deinen Plan zu ändern.") {
        DayTabs(day, t?.blocks?.any { it.day > 5 } == true) { day = it }
        if (t?.blocks?.any { it.week != 0 } == true)
            Pick("Woche", if (week == 1) "A-Woche" else "B-Woche", listOf("A-Woche", "B-Woche")) {
                week = if (it == "A-Woche") 1 else 2
            }
        val averages =
            remember(p, d.subjects, d.assessments) {
                d.subjects
                    .filter { it.profileId == p.id }
                    .associate { subject ->
                        subject.id to Grades.calculate(p, subject, d.assessments).value
                    }
            }
        val rows = remember(t) { timetableRows(t) }
        val positioned = rows.filter { it.day == day && (it.week == 0 || it.week == week) }
        val gaps = timetableGaps(positioned)
        (1..maxOf(6, positioned.maxOfOrNull { it.slot } ?: 0)).forEach { slot ->
            val b = positioned.firstOrNull { it.slot == slot }
            gaps[b?.id]?.let { TimetablePause(it) }
            Card(
                onClick = { edit(day) },
                modifier = Modifier.fillMaxWidth().testTag("lesson-$day-$slot"),
                shape = lookShape(22.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (b?.isBreak == true) BreakTint
                            else
                                d.subjects
                                    .find { it.id == b?.subjectId }
                                    ?.let { themedSubjectTint(it) }
                                    ?: MaterialTheme.colorScheme.surface
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            Text(slot.toString(), fontWeight = FontWeight.Bold, color = Ink)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (b?.isBreak == true) "Pause"
                            else d.subjects.find { it.id == b?.subjectId }?.name ?: "Noch frei",
                            fontWeight = FontWeight.Bold,
                        )
                        if (b?.isBreak == false)
                            averages[b.subjectId]?.let { average ->
                                Text(
                                    "Schnitt ${number(average)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        if (b != null)
                            Text("${b.start}–${b.end}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Outlined.Edit, null, tint = Green)
                }
            }
        }
        Action("Stundenplan bearbeiten", { edit(day) })
        HelpPanel("Ferien, Ausfälle und ältere Pläne") {
            OutlinedButton(dayOff, Modifier.fillMaxWidth()) {
                Text("Ferien / freien Tag hinzufügen")
            }
            OutlinedButton(exception, Modifier.fillMaxWidth()) {
                Text("Ausfall / Zusatzunterricht")
            }
            if (tables.isNotEmpty())
                Pick("Plan", "Ab ${t?.validFrom}", tables.map { "Ab ${it.validFrom}" }) { label ->
                    chosen = tables.first { "Ab ${it.validFrom}" == label }.id
                }
            Text(
                "Ferien in Bayern sind für 2026/27 schon eingetragen. Freie Tage an deiner Schule kannst du ergänzen."
            )
            d.daysOff
                .filter { it.profileId == p.id }
                .sortedBy { it.from }
                .forEach { o ->
                    Text("${o.reason}: ${dateLabel(o.from)} bis ${dateLabel(o.to)}")
                    TextButton({ delete(o.id) }) { Text("Entfernen") }
                }
            d.exceptions
                .filter { e -> d.subjects.any { it.id == e.subjectId && it.profileId == p.id } }
                .forEach { e ->
                    Text(
                        "${if (e.cancelled) "Ausfall" else "Zusatzstunde"}: ${dateLabel(e.date)} · ${e.start}–${e.end}"
                    )
                    TextButton({ delete(e.id) }) { Text("Entfernen") }
                }
            Source("https://www.km.bayern.de/termine/ferien-und-feiertage")
        }
    }
}

@Composable
fun SettingsPage(
    a: MainActivity,
    d: SchoolData,
    newProfile: () -> Unit,
    switch: (String) -> Unit,
    rules: () -> Unit,
    timetable: () -> Unit,
) {
    var mode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    Page("Einstellungen", "Deine Daten bleiben bei dir.") {
        AppearanceSettings(a.appearance)
        Panel("Schulprofil") {
            val basicLabels =
                d.profiles.map {
                    "${it.school.label} · ${it.grade}. Klasse · ${it.year} · ${it.track} · ${it.term}"
                }
            val labels =
                basicLabels.mapIndexed { index, label ->
                    if (basicLabels.count { it == label } > 1) "$label · Profil ${index + 1}"
                    else label
                }
            Pick(
                "Dein Schuljahr",
                labels.getOrNull(d.profiles.indexOfFirst { it.id == d.activeProfileId }).orEmpty(),
                labels,
            ) { label ->
                d.profiles.getOrNull(labels.indexOf(label))?.let { switch(it.id) }
            }
            d.active()?.let { Text("Dein Schulzweig: ${Defaults.trackLabel(it.school, it.track)}") }
            Action("Neue Klasse / neues Schuljahr", newProfile)
            Text("Deine alten Schuljahre bleiben gespeichert.")
        }
        Panel("Erinnerungen") {
            Text(
                if (ReminderScheduler.allowed(a)) "Benachrichtigungen erlaubt"
                else "Benachrichtigungen ausgeschaltet"
            )
            Text(
                if (ReminderScheduler.exact(a)) "Pünktliche Erinnerungen erlaubt"
                else "Ungefähre Zustellung – Android kann Hinweise verzögern."
            )
            Action("Benachrichtigungen einstellen") { a.requestNotifications() }
            OutlinedButton({ a.requestExact() }, Modifier.fillMaxWidth()) {
                Text("Pünktliche Erinnerungen erlauben")
            }
            Text(
                "21 und 14 Tage vorher; täglich vom 7. bis zum letzten Tag vor der Prüfung. Gebündelt um 16 Uhr in der lokalen Gerätezeit."
            )
        }
        Panel("Datensicherung") {
            Text(
                "Ein Backup ist eine Sicherheitskopie deiner App. Es enthält auch deine Bilder. Merke dir das Passwort gut: Ohne Passwort kannst du die Kopie nicht öffnen."
            )
            Action("Backup exportieren") {
                mode = "export"
                password = ""
            }
            OutlinedButton(
                {
                    mode = "restore"
                    password = ""
                    confirmed = false
                },
                Modifier.fillMaxWidth(),
            ) {
                Text("Backup wiederherstellen")
            }
        }
        OutlinedButton(timetable, Modifier.fillMaxWidth()) { Text("Stundenplan bearbeiten") }
        OutlinedButton(rules, Modifier.fillMaxWidth()) { Text("Rechtsgrundlagen und Regelprüfung") }
        HelpPanel("Wer kann meine Daten sehen?") {
            Text(
                "StreberAlarm speichert Profile, Noten, Lernzeiten und Dokumente lokal im privaten App-Speicher. Kein Konto, keine Werbung, keine eigene App-Analyse, keine Cloud-KI. Automatische Android-Cloud-Sicherungen und Geräteübertragung sind deaktiviert."
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
    if (mode.isNotEmpty())
        AlertDialog(
            onDismissRequest = {
                mode = ""
                password = ""
            },
            title = {
                Text(if (mode == "export") "Verschlüsseltes Backup" else "Backup wiederherstellen")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Backup-Passwort") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (mode == "restore")
                        Check("Vorhandene Daten vollständig durch das Backup ersetzen", confirmed) {
                            confirmed = it
                        }
                    else Text("Mindestens 8 Zeichen. Bewahre das Passwort sicher auf.")
                }
            },
            confirmButton = {
                TextButton(
                    {
                        val value = password
                        password = ""
                        if (mode == "export") a.export(value) else a.restore(value)
                        mode = ""
                    },
                    enabled = password.length >= 8 && (mode == "export" || confirmed),
                ) {
                    Text("Datei auswählen")
                }
            },
            dismissButton = {
                TextButton({
                    mode = ""
                    password = ""
                }) {
                    Text("Abbrechen")
                }
            },
        )
}
