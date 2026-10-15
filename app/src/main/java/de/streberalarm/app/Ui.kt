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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        if (LocalLook.current.isYouth) {
            YouthPageHeading(title, subtitle)
        } else if (LocalLook.current == AppLook.BAYERN) {
            VividPageHeading(title, subtitle)
        } else {
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
        if (LocalLook.current.isYouth) YouthRibbon(LocalLook.current)
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
fun Action(text: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val shape = lookShape(20.dp)
    val gradient =
        when (look) {
            AppLook.SOCIAL -> listOf(Color(0xFF993066), Color(0xFFAC3F36))
            AppLook.STREAMER -> listOf(Color(0xFFDFB6FF), Color(0xFF79E7FA))
            AppLook.HACKER -> listOf(Color(0xFF71FF48), Color(0xFFA1FF70))
            AppLook.ORBIT -> listOf(Color(0xFFA1D9FF), Color(0xFFCCC2FF))
            AppLook.BAYERN -> listOf(Color(0xFFAD0025), Color(0xFFD50935))
            else -> listOf(Green, Green)
        }
    Button(
        onClick,
        modifier.fillMaxWidth()
            .heightIn(min = 58.dp)
            .then(
                if (enabled && look != AppLook.CLASSIC)
                    Modifier.clip(shape).background(Brush.horizontalGradient(gradient))
                else Modifier
            ),
        enabled = enabled,
        shape = shape,
        colors =
            actionButtonColors(look, ButtonDefaults.buttonColors(
                containerColor = if (look == AppLook.CLASSIC) Green else Color.Transparent,
                contentColor =
                    if (look == AppLook.BAYERN) Color.White else MaterialTheme.colorScheme.onPrimary,
            )),
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
    TextButton({ uri.openUri(url) }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) { Text("Quelle: ${url.substringAfterLast('/')} ↗") }
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
    var fromSettings by rememberSaveable { mutableStateOf(false) }
    val settingsState = rememberSaveableStateHolder()
    val vocabularyState = rememberSaveableStateHolder()
    val learningState = rememberSaveableStateHolder()
    val vocabularyGeneration by activity.repo.vocabularyGeneration.collectAsState()
    val demo by activity.repo.isDemo.collectAsState()
    var vocabularyBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var gradeBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var settingsPracticeParent by rememberSaveable { mutableStateOf("") }
    var documentParent by rememberSaveable { mutableStateOf("home") }
    var documentParentSelection by rememberSaveable { mutableStateOf("") }
    var lastVocabularyKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(data?.activeProfileId, vocabularyGeneration) {
        val current = "vocabulary:${data?.activeProfileId}:$vocabularyGeneration"
        lastVocabularyKey?.takeIf { it != current }?.let { vocabularyState.removeState(it) }
        lastVocabularyKey = current
    }
    LaunchedEffect(tab) { if (tab !in 0..2) tab = 0 }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snack.showSnackbar(it)
            activity.message.value = null
        }
    }
    fun open(where: String, id: String = "") {
        if (page in listOf("scanner", "examGrade") && where != page && demo)
            activity.repo.closeDemoScansForExam(selection)
        if (where == "settings") settingsPracticeParent = page.takeIf { it in listOf("vocabulary", "flashcards") }.orEmpty()
        if (where == "document") {
            documentParent = page
            documentParentSelection = selection
        }
        if (where == "settings" || page == "home") fromSettings = false
        selection = id
        page = where
    }
    fun openSettingsChild(where: String) {
        open(where)
        fromSettings = true
    }
    fun back() {
        if (page == "examGrade" && gradeBack != null) {
            gradeBack?.invoke()
            return
        }
        if (page in listOf("vocabulary", "flashcards") && vocabularyBack != null) {
            vocabularyBack?.invoke()
            return
        }
        if (page in listOf("scanner", "examGrade") && demo)
            activity.repo.closeDemoScansForExam(selection)
        page = when {
            page == "settings" && settingsPracticeParent.isNotEmpty() -> settingsPracticeParent
            page == "document" -> documentParent.also { selection = documentParentSelection }
            fromSettings && page in listOf("timetableEdit", "dayOff", "exception") -> "timetable"
            fromSettings && page in listOf("appearance", "privacyRules", "timetable", "profile", "subjectEdit", "official", "transfer") -> "settings"
            else -> "home"
        }
        if (page == "settings" || page == "home") fromSettings = false
    }
    fun update(block: (SchoolData) -> SchoolData) {
        val generation = activity.repo.vocabularyGeneration.value
        activity.action { activity.repo.update(generation, block) }
    }
    // Old notification intents safely land on the ordinary start page.
    LaunchedEffect(estimateRequest) { activity.estimateRequest.value = null }
    AppearanceTheme(activity) {
        val d = data
        val p = d?.active()
        BackHandler(page != "home") { back() }
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                        TopAppBar(
                    title = { Text(if (demo) "NotenManager · DEMO" else "NotenManager", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        if (page != "home")
                            IconButton({ back() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück")
                            }
                    },
                    actions = {
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
                        listOf(0 to "Heute", 1 to "Meine Noten", 2 to "Lernen").forEach { (i, label)
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
                else if (p == null && page == "settings")
                    SettingsPage(
                        activity, d, demo,
                        { activity.action { activity.repo.loadDemo(); activity.message.value = "Fiktive Testdaten sind geladen." }; page = "home" },
                        { activity.action { activity.repo.returnToReal(); activity.message.value = "Deine echten Daten sind wieder sichtbar." }; page = "home" },
                        { activity.action { activity.repo.deleteDemo(); activity.message.value = "Testdaten gelöscht. Deine echten Daten sind unverändert." }; page = "home" },
                        { openSettingsChild("profile") },
                        { id -> update { it.copy(activeProfileId = id) } },
                        { openSettingsChild("privacyRules") },
                        { openSettingsChild("timetable") },
                        { openSettingsChild("appearance") },
                        { id -> open("subjectEdit", id); fromSettings = true },
                        { id -> open("official", id); fromSettings = true },
                        { open("transfer"); fromSettings = true },
                    )
                else if (p == null && page == "privacyRules")
                    PrivacyRulesPage(null)
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
                        "flashcards" -> key(p.id, vocabularyGeneration) {
                            FlashcardsPage(activity, p, d, vocabularyGeneration,
                                exit = { page = "home" }, onBackChanged = { vocabularyBack = it })
                        }
                        "vocabulary" -> vocabularyState.SaveableStateProvider("vocabulary:${p.id}:$vocabularyGeneration") {
                            key(p.id, vocabularyGeneration) {
                                VocabularyPage(activity, p, d, vocabularyGeneration,
                                    exit = { page = "home" }, onBackChanged = { vocabularyBack = it })
                            }
                        }
                        "settings" -> settingsState.SaveableStateProvider("settings") {
                            SettingsPage(
                                activity,
                                d,
                                demo,
                                { activity.action { activity.repo.loadDemo(); activity.message.value = "Fiktive Testdaten sind geladen." }; page = "home" },
                                { activity.action { activity.repo.returnToReal(); activity.message.value = "Deine echten Daten sind wieder sichtbar." }; page = "home" },
                                { activity.action { activity.repo.deleteDemo(); activity.message.value = "Testdaten gelöscht. Deine echten Daten sind unverändert." }; page = "home" },
                                { openSettingsChild("profile") },
                                { id -> update { it.copy(activeProfileId = id) } },
                                { openSettingsChild("privacyRules") },
                                { openSettingsChild("timetable") },
                                { openSettingsChild("appearance") },
                                { id -> open("subjectEdit", id); fromSettings = true },
                                { id -> open("official", id); fromSettings = true },
                                { open("transfer"); fromSettings = true },
                            )
                        }
                        "appearance" -> Page("Dein Look") { AppearanceSettings(activity.appearance) }
                        "privacyRules" -> PrivacyRulesPage(p)
                        "subject" ->
                            d.subjects
                                .find { it.id == selection }
                                ?.let {
                                    SubjectPage(
                                        p,
                                        it,
                                        d,
                                        { open("subjectEdit", it.id) },
                                        { open("examGrade", it) },
                                        { open("examGrade", "new:${it.id}") },
                                        { open("official", it.id) },
                                        activity,
                                        { open("document", it) },
                                        { id -> activity.action { activity.repo.deleteExam(id, vocabularyGeneration) } },
                                    )
                                }
                        "subjectEdit" ->
                            SubjectForm(
                                p,
                                d.subjects.find { it.id == selection },
                                {
                                    val generation = activity.repo.vocabularyGeneration.value
                                    val id = selection
                                    activity.action { activity.repo.deleteSubject(id, generation) }
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
                                        { open("document", it) },
                                        {
                                            val generation = activity.repo.vocabularyGeneration.value
                                            activity.action { activity.repo.deleteExam(e.id, generation) }
                                            page = "home"
                                        },
                                    )
                                }
                        "plan" ->
                            QuickPlanForm(p, d.subjects.filter { it.profileId == p.id }, saving = busy) { e ->
                                activity.action {
                                    activity.repo.update(vocabularyGeneration) { it.copy(assessments = it.assessments + e) }
                                    withContext(Dispatchers.Main) { page = "home" }
                                }
                            }
                        "examGrade" -> key(p.id, selection) {
                            CompactGradePage(activity, p, d, selection,
                                onSaved = { id ->
                                    selection = d.assessments.find { it.id == id }?.subjectId
                                        ?: activity.repo.state.value?.assessments?.find { it.id == id }?.subjectId.orEmpty()
                                    page = "subject"
                                }, onCancel = { page = "home" }, onBackChanged = { gradeBack = it })
                        }
                        "examEdit" -> key(p.id, selection) {
                            QuickPlanForm(p, d.subjects.filter { it.profileId == p.id },
                                d.assessments.find { it.id == selection }, saving = busy) { e ->
                                activity.action {
                                    activity.repo.update(vocabularyGeneration) { old -> old.copy(assessments = old.assessments.filterNot { it.id == e.id } + e) }
                                    withContext(Dispatchers.Main) { page = "home" }
                                }
                            }
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
                                        { open("examGrade", "new:") },
                                        { open("timetable") },
                                    )
                                1 ->
                                    SubjectsPage(
                                        p,
                                        d,
                                        { open("subject", it) },
                                        { open("subjectEdit") },
                                        { open("examGrade", "new:") },
                                        { id ->
                                            val generation = activity.repo.vocabularyGeneration.value
                                            activity.action { activity.repo.deleteSubject(id, generation) }
                                        },
                                    )
                                2 -> learningState.SaveableStateProvider("learning:${p.id}") {
                                    LearningPage(
                                        activity,
                                        p,
                                        d,
                                        { open("exam", it) },
                                        { open("plan") },
                                        { open("examGrade", it) },
                                        { open("vocabulary") },
                                        { open("flashcards") },
                                    )
                                }
                            }
                    }
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
    grade: () -> Unit,
    delete: (String) -> Unit,
) {
    var subjectMenu by remember { mutableStateOf<Subject?>(null) }
    var pendingDelete by remember { mutableStateOf<Subject?>(null) }
    Page("Meine Noten") {
        Action("Note eintragen", grade, d.subjects.any { it.profileId == p.id })
        d.subjects
            .filter { it.profileId == p.id }
            .alphabetical()
            .forEach { s ->
                val calc = Grades.calculate(p, s, d.assessments)
                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                            .monitor()
                            .combinedClickable(
                                onClick = { open(s.id) },
                                onLongClick = { subjectMenu = s },
                            )
                            .testTag("subject-${s.id}"),
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
                            Text("${d.assessments.count { it.subjectId == s.id && it.actual != null && it.stage != Stage.CANCELLED }} Noten", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            number(calc.value),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Green,
                        )
                    }
                }
            }
        Action("Fach hinzufügen", add)
    }
    subjectMenu?.let { subject ->
        AlertDialog(
            onDismissRequest = { subjectMenu = null },
            title = { Text(subject.name) },
            confirmButton = {
                TextButton({
                    subjectMenu = null
                    pendingDelete = subject
                }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                    Text("Löschen", color = if (LocalLook.current == AppLook.BAYERN) Color.White else MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ subjectMenu = null }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) { Text("Abbrechen") } },
        )
    }
    pendingDelete?.let { subject ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Fach vollständig löschen?") },
            text = {
                Text(
                    "Alle zugehörigen Leistungen, offiziellen Noten, Lernphasen, Dokumente und Stundenplaneinträge werden entfernt."
                )
            },
            confirmButton = {
                TextButton({
                    pendingDelete = null
                    delete(subject.id)
                }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                    Text("Fach löschen")
                }
            },
            dismissButton = { TextButton({ pendingDelete = null }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) { Text("Abbrechen") } },
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
            OutlinedButton(dayOff, Modifier.fillMaxWidth(), colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors())) {
                Text("Ferien / freien Tag hinzufügen")
            }
            OutlinedButton(exception, Modifier.fillMaxWidth(), colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors())) {
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
                    TextButton({ delete(o.id) }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) { Text("Entfernen") }
                }
            d.exceptions
                .filter { e -> d.subjects.any { it.id == e.subjectId && it.profileId == p.id } }
                .forEach { e ->
                    Text(
                        "${if (e.cancelled) "Ausfall" else "Zusatzstunde"}: ${dateLabel(e.date)} · ${e.start}–${e.end}"
                    )
                    TextButton({ delete(e.id) }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) { Text("Entfernen") }
                }
            Source("https://www.km.bayern.de/termine/ferien-und-feiertage")
        }
    }
}

@Composable
fun SettingsPage(
    a: MainActivity,
    d: SchoolData,
    demo: Boolean,
    loadDemo: () -> Unit,
    returnReal: () -> Unit,
    deleteDemo: () -> Unit,
    newProfile: () -> Unit,
    switch: (String) -> Unit,
    rules: () -> Unit,
    timetable: () -> Unit,
    appearance: () -> Unit,
    editSubject: (String) -> Unit = {},
    official: (String) -> Unit = {},
    transfer: () -> Unit = {},
) {
    var mode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var confirmDemoDelete by remember { mutableStateOf(false) }
    val busy by a.busy.collectAsState()
    Page("Einstellungen", "Mach die App zu deiner.") {
        Panel("Testdaten") {
            Text(if (demo) "Demo aktiv: Alle Änderungen bleiben im fiktiven Beispiel. Deine echten Daten sind separat gespeichert."
                else "Fiktiver Stundenplan, Noten, Lernstoff, Vokabeln und zwei Beispiel-Scans. Deine Daten bleiben separat gespeichert.")
            Action("Testdaten laden", loadDemo, !busy)
            if (demo) Action("Echte Daten anzeigen", returnReal, !busy)
            OutlinedButton({ confirmDemoDelete = true }, Modifier.fillMaxWidth(),
                enabled = !busy,
                colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors())) {
                Text("Testdaten löschen")
            }
        }
        if (d.active() != null) {
            SettingsHero(
                "Stundenplan", "Ansehen und bearbeiten", Icons.Outlined.CalendarMonth,
                "settings-timetable", timetable,
            )
            SettingsHero(
                "Dein Look", "Farben und Style auswählen", Icons.Outlined.Palette,
                "settings-look", appearance,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSection("Fächer & Gewichtungen") {
                d.subjects.filter { it.profileId == d.activeProfileId }.alphabetical().forEach { subject ->
                    SettingsLink(subject.name) { editSubject(subject.id) }
                }
            }
            SettingsSection("Offizielle Ergebnisse") {
                d.subjects.filter { it.profileId == d.activeProfileId }.alphabetical().forEach { subject ->
                    SettingsLink(subject.name) { official(subject.id) }
                }
                if (d.active()?.school == School.GRUNDSCHULE) SettingsLink("Übertrittsergebnis", transfer)
            }
            SettingsSection("Erinnerungen") {
                Text(
                    if (ReminderScheduler.allowed(a)) "Benachrichtigungen erlaubt"
                    else "Benachrichtigungen ausgeschaltet"
                )
                Text(
                    if (ReminderScheduler.exact(a)) "Pünktliche Erinnerungen erlaubt"
                    else "Ungefähre Zustellung – Android kann Hinweise verzögern."
                )
                Action("Benachrichtigungen einstellen") { a.requestNotifications() }
                OutlinedButton({ a.requestExact() }, Modifier.fillMaxWidth(), colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors())) {
                    Text("Pünktliche Erinnerungen erlauben")
                }
                Text(
                    "21 und 14 Tage vorher; täglich vom 7. bis zum letzten Tag vor der Prüfung. Gebündelt um 16 Uhr in der lokalen Gerätezeit."
                )
            }
            SettingsSection("Datensicherung") {
                if (demo) {
                    Text("Backup und Wiederherstellung sind in der Demo ausgeschaltet. Wechsle zuerst zu deinen echten Daten.")
                } else {
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
                    Modifier.fillMaxWidth(), colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors())) {
                    Text("Backup wiederherstellen")
                }
                }
            }
            SettingsLink("Datenschutz & Rechtsgrundlagen", rules)
            SettingsSection("Schuljahr") {
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
        }
    }
    if (confirmDemoDelete) AlertDialog(
        onDismissRequest = { confirmDemoDelete = false },
        title = { Text("Testdaten löschen?") },
        text = { Text("Nur die fiktive Demo und ihre Beispiel-Scans werden entfernt. Deine echten Schuljahre bleiben erhalten.") },
        confirmButton = { TextButton({ confirmDemoDelete = false; deleteDemo() }, enabled = !busy) { Text("Jetzt löschen") } },
        dismissButton = { TextButton({ confirmDemoDelete = false }) { Text("Abbrechen") } },
    )
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
                    if (mode == "restore") {
                        Check("Vorhandene Daten vollständig durch das Backup ersetzen", confirmed) {
                            confirmed = it
                        }
                    } else Text("Mindestens 8 Zeichen. Bewahre das Passwort sicher auf.")
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
                    enabled = password.length >= 8 && (mode == "export" || confirmed), colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                    Text("Datei auswählen")
                }
            },
            dismissButton = {
                TextButton({
                    mode = ""
                    password = ""
                }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                    Text("Abbrechen")
                }
            },
        )
}
