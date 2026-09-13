package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import de.streberalarm.core.*

@Composable
fun RuleOutput(result: RuleResult) {
    Panel(result.title) {
        Text(
            when (result.verdict) {
                Verdict.SATISFIED -> "Erfasste Bedingungen erfüllt"
                Verdict.RISK -> "Bedingungen nicht erfüllt / Risiko"
                Verdict.INCOMPLETE -> "Weitere Angaben erforderlich"
                Verdict.SCHOOL_DECISION -> "Entscheidung der Schule erforderlich"
                Verdict.UNSUPPORTED -> "Nicht unterstützt"
            },
            fontWeight = FontWeight.Bold,
        )
        result.value?.let { Text(it, style = MaterialTheme.typography.headlineLarge) }
        result.details.forEach { Text(it) }
        result.sources.forEach { Source(it) }
    }
}

@Composable
fun RulesPage(p: Profile, d: SchoolData, save: (GraduationInput) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("Vorrücken") }
    var complete by rememberSaveable { mutableStateOf(false) }
    var previous by rememberSaveable { mutableStateOf("Unbekannt") }
    var social by rememberSaveable { mutableStateOf(false) }
    var input by remember { mutableStateOf(d.graduation[p.id] ?: GraduationInput()) }
    var result by remember { mutableStateOf<RuleResult?>(null) }
    var addCourse by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AbiturCourse?>(null) }
    fun number(key: String, value: String) {
        input = input.copy(numbers = input.numbers + (key to value.toIntOrNull()))
        result = null
    }
    fun check(key: String, value: Boolean) {
        input = input.copy(confirmations = input.confirmations + (key to value))
        result = null
    }
    Page("Zeugnis & Abschluss", "Bayern · Fassung ab 01.08.2026 · Regelpaket ${p.ruleVersion}") {
        Text(
            "Regelprüfungen verwenden offizielle Ergebnisse. Rechnerische Schnitte und Einschätzungen werden nicht als Zeugnisnoten übernommen."
        )
        if (!Rules.supported(p)) RuleOutput(Rules.unsupported())
        Pick(
            "Verfahren",
            mode,
            listOf("Vorrücken") +
                when (p.school) {
                    School.GRUNDSCHULE -> emptyList()
                    School.GYMNASIUM -> listOf("Abitur")
                    School.REALSCHULE -> listOf("Mittlerer Abschluss")
                    School.MITTELSCHULE -> listOf("Quali")
                    School.M_ZUG -> listOf("Quali", "Mittlerer Abschluss")
                },
        ) {
            mode = it
            result = null
            input = d.graduation["${p.id}:$it"] ?: GraduationInput(procedure = it)
        }
        if (mode == "Vorrücken") {
            Text(
                "Die Jahreszeugnisnoten werden bei den einzelnen Fächern unter „Offizielle Note eintragen“ erfasst."
            )
            d.subjects
                .filter { it.profileId == p.id && it.promotion }
                .forEach { s ->
                    Text(
                        "${s.name}: ${d.officials.find{it.subjectId==s.id && it.period=="Jahreszeugnis"}?.value ?: "fehlt"}"
                    )
                }
            Check("Alle tatsächlichen Vorrückungsfächer sind vollständig angelegt", complete) {
                complete = it
                result = null
            }
            if (p.school == School.GYMNASIUM && p.grade in 10..11)
                Pick(
                    "Vorjahr nur durch Notenausgleich bestanden",
                    previous,
                    listOf("Unbekannt", "Ja", "Nein"),
                ) {
                    previous = it
                    result = null
                }
            if (p.school == School.GYMNASIUM && p.grade == 11 && p.track == "SWG")
                Check("Sozialpraktikum: 15 Tage erfolgreich abgeschlossen", social) {
                    social = it
                    result = null
                }
            Action("Vorrückungsbedingungen prüfen") {
                result =
                    Rules.promotion(
                        p,
                        d.subjects,
                        d.officials,
                        complete,
                        when (previous) {
                            "Ja" -> true
                            "Nein" -> false
                            else -> null
                        },
                        social,
                    )
            }
        } else {
            when (mode) {
                "Quali" -> {
                    Text(
                        "Wahlfach 1: Englisch, Natur und Technik oder Geschichte/Politik/Geographie. Wahlfach 2: das zulässige besuchte Wahlfach. M-Zug verwendet Zwischenzeugnisnoten und ein zusätzliches Wahlfach."
                    )
                    Check(
                        "Wahlfach 1 ist Englisch (schriftlich + mündlich)",
                        input.confirmations["languageOral"] == true,
                    ) {
                        check("languageOral", it)
                    }
                    Check(
                        "Deutsch durch genehmigtes Deutsch als Zweitsprache ersetzt",
                        input.confirmations["germanOral"] == true,
                    ) {
                        check("germanOral", it)
                        if (it) check("germanExtra", false)
                    }
                    if (p.school == School.M_ZUG) {
                        Check(
                            "Zusätzliches Wahlfach durch genehmigte Projektprüfung ersetzt",
                            input.confirmations["projectInsteadOfSecondChoice"] == true,
                        ) {
                            check("projectInsteadOfSecondChoice", it)
                        }
                        if (input.confirmations["projectInsteadOfSecondChoice"] != true)
                            Check(
                                "Zusätzliches Wahlfach ist Englisch (schriftlich + mündlich)",
                                input.confirmations["secondLanguageOral"] == true,
                            ) {
                                check("secondLanguageOral", it)
                            }
                    }
                    if (input.confirmations["germanOral"] != true)
                        Check(
                            "Zusätzliche mündliche Prüfung in Deutsch",
                            input.confirmations["germanExtra"] == true,
                        ) {
                            check("germanExtra", it)
                        }
                    Check(
                        "Zusätzliche mündliche Prüfung in Mathematik",
                        input.confirmations["mathExtra"] == true,
                    ) {
                        check("mathExtra", it)
                    }
                    Rules.qualiFields(p, input).forEach { (key, weight) ->
                        Field(
                            if (weight == 0) key else "$key (Gewicht $weight)",
                            input.numbers[key]?.toString().orEmpty(),
                        ) {
                            number(key, it)
                        }
                    }
                    Check(
                        "Teilnahme und Fachwahl von der Schule bestätigt",
                        input.confirmations["eligibility"] == true,
                    ) {
                        check("eligibility", it)
                    }
                }
                "Mittlerer Abschluss" -> {
                    Text(
                        "Offizielle Gesamtnoten aus dem Abschlusszeugnis übernehmen. Fachliche Prüfungsnoten nicht durch automatisch gerundete Mittelwerte ersetzen."
                    )
                    val names =
                        d.subjects
                            .filter { it.profileId == p.id && it.promotion }
                            .map { it.name }
                            .filterNot {
                                p.school == School.M_ZUG &&
                                    it in listOf("Wirtschaft und Beruf", p.track)
                            } +
                            if (p.school == School.M_ZUG) listOf("Projekt", "Projektprüfung")
                            else emptyList()
                    names.distinct().forEach { key ->
                        Field(
                            "$key · offizielle Gesamtnote",
                            input.numbers[key]?.toString().orEmpty(),
                        ) {
                            number(key, it)
                        }
                    }
                    Check(
                        "Alle Abschluss-/Vorrückungsfächer vollständig und offiziell festgestellt",
                        input.confirmations["allOfficial"] == true,
                    ) {
                        check("allOfficial", it)
                    }
                }
                "Abitur" -> {
                    Text(
                        "Erfasse Kurse mit vier offiziellen Halbjahresleistungen. Einbringung auswählen; die Seminararbeit zählt zusätzlich wie zwei Halbjahresleistungen. Keine automatische Kursoptimierung."
                    )
                    input.courses.forEach { course ->
                        Panel(course.name) {
                            Text(
                                "${course.points.joinToString(" / "){it?.toString()?:"–"}} · ${course.included.count{it}} eingebracht"
                            )
                            TextButton({
                                editing = course
                                addCourse = true
                            }) {
                                Text("Kurs bearbeiten")
                            }
                            TextButton({
                                input =
                                    input.copy(
                                        courses =
                                            input.courses.filterNot { it.name == course.name },
                                        exams = input.exams.filterNot { it.name == course.name },
                                    )
                                result = null
                            }) {
                                Text("Kurs entfernen")
                            }
                        }
                    }
                    Action("Kurs hinzufügen") {
                        editing = null
                        addCourse = true
                    }
                    Panel("Fünf Abiturprüfungen") {
                        input.courses.forEach { course ->
                            val exam = input.exams.find { it.name == course.name }
                            Check("${course.name} als Prüfungsfach", exam != null) { selected ->
                                input =
                                    input.copy(
                                        exams =
                                            if (selected) input.exams + AbiturExam(course.name)
                                            else input.exams.filterNot { it.name == course.name }
                                    )
                                result = null
                            }
                            if (exam != null) {
                                fun change(e: AbiturExam) {
                                    input =
                                        input.copy(
                                            exams =
                                                input.exams.map { if (it.name == e.name) e else it }
                                        )
                                    result = null
                                }
                                Check("${course.name}: schriftliche Prüfung", exam.written) {
                                    change(
                                        exam.copy(
                                            written = it,
                                            oralAddition = if (it) exam.oralAddition else null,
                                            practical =
                                                if (!it && course.name == "Musik") null
                                                else exam.practical,
                                        )
                                    )
                                }
                                Field(
                                    "${course.name}: Prüfungspunkte (0–15)",
                                    exam.points?.toString().orEmpty(),
                                ) {
                                    change(exam.copy(points = it.toIntOrNull()))
                                }
                                if (exam.written)
                                    Field(
                                        "Zusatzprüfung mündlich (optional)",
                                        exam.oralAddition?.toString().orEmpty(),
                                    ) {
                                        change(exam.copy(oralAddition = it.toIntOrNull()))
                                    }
                                if (
                                    course.name == "Sport" || course.name == "Musik" && exam.written
                                )
                                    Field(
                                        "Praktische Fachprüfung (0–15)",
                                        exam.practical?.toString().orEmpty(),
                                    ) {
                                        change(exam.copy(practical = it.toIntOrNull()))
                                    }
                            }
                        }
                    }
                    Field(
                        "Seminararbeit (0–15)",
                        input.numbers["Seminararbeit"]?.toString().orEmpty(),
                    ) {
                        number("Seminararbeit", it)
                    }
                    Field(
                        "Seminargespräch (0–15)",
                        input.numbers["Seminargespräch"]?.toString().orEmpty(),
                    ) {
                        number("Seminargespräch", it)
                    }
                    Check(
                        "Fachwahl und Einbringung von der Schule bestätigt",
                        input.confirmations["subjectsApproved"] == true,
                    ) {
                        check("subjectsApproved", it)
                    }
                    Check(
                        "Belegung, 124/126 Stunden und zweite Fremdsprache nachgewiesen",
                        input.confirmations["hoursAndSecondLanguage"] == true,
                    ) {
                        check("hoursAndSecondLanguage", it)
                    }
                    Check(
                        "Seminararbeit abgegeben; zwei Seminarhalbjahre im Kursbestand eingebracht",
                        input.confirmations["seminarSubmitted"] == true,
                    ) {
                        check("seminarSubmitted", it)
                    }
                    Check(
                        "Alle vorgeschriebenen Prüfungen abgelegt",
                        input.confirmations["allExamsTaken"] == true,
                    ) {
                        check("allExamsTaken", it)
                    }
                    Text(
                        "Zulassung, Einbringung und Prüfungsmindestbedingungen werden getrennt geprüft. Nicht abgebildete Genehmigungen, Sonderlaufbahnen und Ersatzregelungen benötigen eine Prüfung durch die Schule.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Action("Angaben speichern und prüfen") {
                val i = input.copy(procedure = mode)
                save(i)
                result =
                    runCatching { Rules.certificate(p, i) }
                        .getOrElse {
                            RuleResult(
                                Verdict.INCOMPLETE,
                                "Ungültige Eingaben",
                                listOf("Bitte Noten- und Punktebereiche sowie Kursangaben prüfen."),
                                emptyList(),
                            )
                        }
            }
        }
        result?.let { RuleOutput(it) }
        Panel("Geltungsbereich") {
            Text(
                "Reguläre Schülerlaufbahnen in Bayern. Andere Bundesländer, Externenprüfungen, Abendgymnasium und Kolleg sind nicht unterstützt. Regel- und Ferienupdates kommen mit App-Updates. Unvollständige Eingaben führen zu keiner positiven Freigabe."
            )
            Text(
                "Die vollständige rechtliche Abdeckung aller Sonderkonstellationen ist vor einer öffentlichen Version noch zu verifizieren.",
                color = MaterialTheme.colorScheme.error,
            )
            Source(law("BayEUG-52"))
            Source(law("BayEUG-53"))
        }
    }
    if (addCourse)
        CourseDialog(editing, { addCourse = false }) { course ->
            input =
                input.copy(
                    courses =
                        input.courses.filterNot { it.name == (editing?.name ?: course.name) } +
                            course
                )
            result = null
            addCourse = false
        }
}

@Composable
fun CourseDialog(existing: AbiturCourse?, dismiss: () -> Unit, save: (AbiturCourse) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var area by remember { mutableStateOf(existing?.area ?: 1) }
    var group by remember { mutableStateOf(existing?.group ?: "Sonstiges") }
    var points by remember { mutableStateOf(existing?.points ?: List(4) { null }) }
    var included by remember { mutableStateOf(existing?.included ?: List(4) { true }) }
    var advanced by remember { mutableStateOf(existing?.advanced ?: false) }
    var terms by remember { mutableStateOf((existing?.requiredTerms ?: 4).toString()) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Page("Abiturkurs") {
                Field("Fachname (z. B. Deutsch)", name) { name = it }
                Pick("Aufgabenfeld", area.toString(), listOf("0", "1", "2", "3")) {
                    area = it.toInt()
                }
                Text(
                    "1: sprachlich-künstlerisch · 2: gesellschaftswissenschaftlich · 3: mathematisch-naturwissenschaftlich · 0: Sport / Seminar"
                )
                Pick(
                    "Fachgruppe",
                    group,
                    listOf("Sonstiges", "Sprache", "Naturwissenschaft", "Sport", "Seminar"),
                ) {
                    group = it
                }
                Pick("Verpflichtende Halbjahre", terms, listOf("0", "2", "4")) { terms = it }
                Check("Leistungsfach", advanced) { advanced = it }
                listOf("12/1", "12/2", "13/1", "13/2").forEachIndexed { index, label ->
                    Field("$label · offizielle Punkte", points[index]?.toString().orEmpty()) { v ->
                        points =
                            points.mapIndexed { i, n -> if (i == index) v.toIntOrNull() else n }
                    }
                    Check("$label einbringen", included[index]) { v ->
                        included = included.mapIndexed { i, b -> if (i == index) v else b }
                    }
                }
                Text("Nicht belegte Halbjahre: 0 eintragen und nicht einbringen.")
                Action(
                    "Kurs übernehmen",
                    {
                        save(
                            AbiturCourse(
                                name.trim(),
                                area,
                                group,
                                points,
                                included,
                                terms.toInt(),
                                advanced,
                            )
                        )
                    },
                    name.isNotBlank() && points.all { it in 0..15 },
                )
                OutlinedButton(dismiss, Modifier.fillMaxWidth()) { Text("Abbrechen") }
            }
        }
    }
}
