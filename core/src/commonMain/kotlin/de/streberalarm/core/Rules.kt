package de.streberalarm.core

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class GraduationInput(
    val procedure: String = "",
    val numbers: Map<String, Int?> = emptyMap(),
    val confirmations: Map<String, Boolean?> = emptyMap(),
    val courses: List<AbiturCourse> = emptyList(),
    val exams: List<AbiturExam> = emptyList(),
)

@Serializable
data class AbiturCourse(
    val name: String,
    val area: Int,
    val group: String,
    val points: List<Int?>,
    val included: List<Boolean> = listOf(true, true, true, true),
    val requiredTerms: Int = 4,
    val advanced: Boolean = false,
)

@Serializable
data class AbiturExam(
    val name: String,
    val written: Boolean = true,
    val points: Int? = null,
    val oralAddition: Int? = null,
    val practical: Int? = null,
)

enum class Verdict {
    SATISFIED,
    RISK,
    INCOMPLETE,
    SCHOOL_DECISION,
    UNSUPPORTED,
}

data class RuleResult(
    val verdict: Verdict,
    val title: String,
    val details: List<String>,
    val sources: List<String>,
    val value: String? = null,
)

fun law(code: String) = "https://www.gesetze-bayern.de/Content/Document/$code"

object Rules {
    const val VERSION = "BY-2026.08-v1"
    const val REVIEWED = "2026-09-12"

    fun supported(p: Profile) = RulePackages.resolve(p) != null

    fun unsupported() =
        RuleResult(
            Verdict.UNSUPPORTED,
            "Kein passendes Regelpaket",
            listOf(
                "Unterstützte Fassung: Bayern, Schuljahr 2026/27; allgemeine Schülerlaufbahnen. Historische Schuljahre werden nicht umgerechnet."
            ),
            emptyList(),
        )

    internal fun promotionGradesFail(school: School, grades: List<Int>): Boolean =
        if (school == School.MITTELSCHULE)
            grades.sum() > 4 * grades.size ||
                grades.sumOf { if (it == 6) 2 else if (it == 5) 1 else 0 } > 3
        else grades.any { it == 6 } || grades.count { it == 5 } >= 2

    fun promotion(
        p: Profile,
        subjects: List<Subject>,
        official: List<Official>,
        rosterComplete: Boolean,
        previousCompensation: Boolean? = null,
        socialInternship: Boolean? = null,
    ): RuleResult {
        if (!supported(p)) return unsupported()
        if (p.school == School.GRUNDSCHULE)
            return RuleResult(
                Verdict.UNSUPPORTED,
                "Übertrittsansicht verwenden",
                listOf("Die Vorrückungsregeln weiterführender Schulen gelten hier nicht."),
                listOf(law("BayVSO-6")),
            )
        if (
            p.points ||
                p.grade >= 10 && p.school != School.GYMNASIUM ||
                p.grade == 9 && p.school == School.MITTELSCHULE
        )
            return RuleResult(
                Verdict.UNSUPPORTED,
                "Abschlussverfahren verwenden",
                listOf(
                    "Für diesen Jahrgang ist die Abschluss- beziehungsweise Oberstufenprüfung erforderlich."
                ),
                emptyList(),
            )
        val code =
            when (p.school) {
                School.GYMNASIUM -> "BayGSO-30"
                School.REALSCHULE -> "BayRSO-24"
                else -> "BayMSO-15"
            }
        val ss =
            subjects
                .filter { it.profileId == p.id }
                .map { PromotionSubjects.correct(p, it) }
                .filter { it.promotion }
        val missing =
            ss.filter { s ->
                official.none {
                    it.subjectId == s.id &&
                        it.period == "Jahreszeugnis" &&
                        (it.value != null || it.omitted)
                }
            }
        if (!rosterComplete || ss.isEmpty() || missing.isNotEmpty())
            return RuleResult(
                Verdict.INCOMPLETE,
                "Zeugnisangaben unvollständig",
                listOf("Vollständige Liste der Vorrückungsfächer bestätigen.") +
                    missing.map { "${it.name}: offizielle Jahresnote fehlt" },
                listOf(law(code)),
            )
        val pairs =
            ss.map { s ->
                s to
                    official
                        .first { it.subjectId == s.id && it.period == "Jahreszeugnis" }
                        .let { if (it.omitted) 6 else it.value!! }
            }
        val bad = pairs.filter { it.second >= 5 }
        val six = bad.count { it.second == 6 }
        val five = bad.count { it.second == 5 }
        val details = bad.map { "${it.first.name}: Note ${it.second}" }.toMutableList()
        if (p.school == School.MITTELSCHULE) {
            val risk = promotionGradesFail(p.school, pairs.map { it.second })
            details.add(
                "Regelklasse: Schnitt schlechter als 4,00 oder mehr als drei Fünfer-Einheiten (6 zählt doppelt) sind Regelindikatoren; Entwicklungsstand und Erfolgsprognose entscheidet die Schule."
            )
            return RuleResult(
                if (risk) Verdict.RISK else Verdict.SCHOOL_DECISION,
                if (risk) "Vorrücken gefährdet" else "Keine rechnerischen Regelindikatoren",
                details,
                listOf(law(code)),
            )
        }
        if (!promotionGradesFail(p.school, pairs.map { it.second })) {
            if (
                p.school == School.GYMNASIUM &&
                    p.grade == 11 &&
                    p.track == "SWG" &&
                    socialInternship != true
            )
                return RuleResult(
                    if (socialInternship == false) Verdict.RISK else Verdict.INCOMPLETE,
                    "Sozialpraktikum prüfen",
                    listOf("Mindestens 15 erfolgreich abgeleistete Arbeitstage erforderlich."),
                    listOf(law(code)),
                )
            return RuleResult(
                Verdict.SATISFIED,
                "Notenbedingung für das Vorrücken erfüllt",
                details +
                    "Dies ersetzt die Entscheidung der Schule nicht. Besondere Deutsch-/Förderregelungen sind mit der Schule zu klären.",
                listOf(law(code)),
            )
        }
        if (p.school == School.REALSCHULE)
            return RuleResult(
                Verdict.RISK,
                "Vorrücken gefährdet",
                details +
                    "Vorrücken auf Probe (§ 26) oder Nachprüfung (§ 27) mit der Schule prüfen; kein automatischer Notenausgleich aus guten Noten.",
                listOf(law(code), law("BayRSO-26"), law("BayRSO-27")),
            )
        val pattern = six == 1 && five == 0 || six == 0 && five == 2
        if (p.school == School.M_ZUG) {
            val compensation =
                pattern &&
                    pairs.none { it.first.name == "Deutsch" && it.second == 6 } &&
                    (pairs.any { it.second == 1 } ||
                        pairs.count { it.second == 2 } >= 2 ||
                        pairs.count { it.second <= 3 } >= 3)
            return RuleResult(
                if (compensation) Verdict.SCHOOL_DECISION else Verdict.RISK,
                if (compensation) "Notenausgleich kommt in Betracht" else "Vorrücken gefährdet",
                details +
                    if (compensation)
                        "Die Lehrerkonferenz entscheidet; bei ungenügender Mitarbeit ist Ausgleich ausgeschlossen."
                    else
                        "Vorrücken auf Probe und persönliche Voraussetzungen mit der Schule prüfen.",
                listOf(law(code), law("BayMSO-16")),
            )
        }
        val coreBad = bad.any { it.first.core }
        val eligible = pairs.filter { !coreBad || it.first.core }
        val compensation =
            p.grade in 10..11 &&
                pattern &&
                (eligible.any { it.second == 1 } ||
                    eligible.count { it.second == 2 } >= 2 ||
                    pairs.count { it.first.core && it.second <= 3 } >= 3) &&
                previousCompensation != true
        if (compensation)
            details.add(
                "Notenbedingung des Ausgleichs erfüllt. Vorheriger Ausgleich: ${if(previousCompensation==null) "unbekannt" else "nein"}. Positive schulische Erfolgsprognose und Entscheidung erforderlich."
            )
        else
            details.add(
                "Notenausgleich nach § 32 nicht nachgewiesen. Vorrücken auf Probe (§ 31) oder Nachprüfung (§ 33) mit der Schule prüfen."
            )
        return RuleResult(
            if (compensation) Verdict.SCHOOL_DECISION else Verdict.RISK,
            if (compensation) "Notenausgleich kommt in Betracht" else "Vorrücken gefährdet",
            details,
            listOf(law(code), law("BayGSO-32"), law("BayGSO-31"), law("BayGSO-33")),
        )
    }

    fun certificate(p: Profile, input: GraduationInput): RuleResult {
        if (!supported(p)) return unsupported()
        return when (input.procedure) {
            "Quali" -> quali(p, input)
            "Abitur" -> abitur(p, input)
            "Mittlerer Abschluss" -> middle(p, input)
            else -> RuleResult(Verdict.INCOMPLETE, "Verfahren auswählen", emptyList(), emptyList())
        }
    }

    fun quali(p: Profile, i: GraduationInput): RuleResult {
        val src = listOf(law("BayMSO-23"), law("BayMSO-25"))
        if (p.school !in listOf(School.MITTELSCHULE, School.M_ZUG) || p.grade != 9)
            return RuleResult(
                Verdict.UNSUPPORTED,
                "Quali erfordert Jahrgang 9 der Mittelschule",
                emptyList(),
                src,
            )
        // Each value is an officially determined component, not an inferred report grade.
        val fields = qualiFields(p, i)
        val missing = fields.keys.filter { i.numbers[it] == null }
        if (missing.isNotEmpty())
            return RuleResult(Verdict.INCOMPLETE, "Quali-Angaben fehlen", missing, src)
        if (fields.keys.any { i.numbers[it] !in 1..6 })
            return RuleResult(
                Verdict.INCOMPLETE,
                "Noten müssen zwischen 1 und 6 liegen",
                emptyList(),
                src,
            )
        if (i.confirmations["eligibility"] != true)
            return RuleResult(
                Verdict.INCOMPLETE,
                "Teilnahmevoraussetzungen bestätigen",
                listOf(
                    "Reguläre Teilnahme, besuchte Wahlfächer und zulässige Fachwahl von der Schule bestätigt. Beim M-Zug Zwischenzeugnisnoten verwenden; Deutsch als Zweitsprache/Muttersprache nur mit genehmigtem Ersatz."
                ),
                src,
            )
        val denominator = fields.values.sum()
        var scaledSum = fields.entries.sumOf { (k, w) -> i.numbers.getValue(k)!! * w * 3 }
        // Keep the thirds from a supplementary oral examination exact until the final truncation.
        if (i.confirmations["germanExtra"] == true) {
            if (i.confirmations["germanOral"] == true)
                return RuleResult(
                    Verdict.INCOMPLETE,
                    "Zusätzliche DaZ-Prüfung gesondert prüfen",
                    listOf(
                        "Die Gewichtung der zusätzlichen mündlichen Prüfung in DaZ muss von der Schule festgestellt werden."
                    ),
                    src,
                )
            scaledSum +=
                2 *
                    (i.numbers.getValue("Deutsch zusätzliche mündliche Prüfung")!! -
                        i.numbers.getValue("Deutsch Prüfungsnote")!!)
        }
        if (i.confirmations["mathExtra"] == true)
            scaledSum +=
                2 *
                    (i.numbers.getValue("Mathematik zusätzliche mündliche Prüfung")!! -
                        i.numbers.getValue("Mathematik Prüfungsnote")!!)
        val tenths = scaledSum * 10 / (denominator * 3)
        return RuleResult(
            if (tenths <= 30) Verdict.SATISFIED else Verdict.RISK,
            if (tenths <= 30) "Quali-Notenbedingung erfüllt"
            else "Quali-Notenbedingung nicht erfüllt",
            listOf(
                "Gewichtete Summe ${scaledSum}/3 ÷ $denominator; auf eine Dezimalstelle abgeschnitten.",
                "Zusätzliche mündliche Prüfungen in Deutsch/Mathematik: schriftlich zu mündlich 2:1. Keine Zwischenrundung.",
            ),
            src,
            "${tenths/10},${tenths%10}",
        )
    }

    fun qualiFields(p: Profile, oral: Boolean): LinkedHashMap<String, Int> =
        qualiFields(p, GraduationInput(confirmations = mapOf("languageOral" to oral)))

    fun qualiFields(p: Profile, i: GraduationInput): LinkedHashMap<String, Int> {
        val m = linkedMapOf("Deutsch Jahres-/Zwischenzeugnis" to 2)
        if (i.confirmations["germanOral"] == true) {
            m["Deutsch als Zweitsprache schriftlich"] = 1
            m["Deutsch als Zweitsprache mündlich"] = 1
        } else m["Deutsch Prüfungsnote"] = 2
        m["Mathematik Jahres-/Zwischenzeugnis"] = 2
        m["Mathematik Prüfungsnote"] = 2
        m["Wahlfach 1 Jahres-/Zwischenzeugnis"] = 2
        if (i.confirmations["languageOral"] == true) {
            m["Wahlfach 1 schriftlich"] = 1
            m["Wahlfach 1 mündlich"] = 1
        } else m["Wahlfach 1 Prüfungsnote"] = 2
        m["Wahlfach 2 Jahres-/Zwischenzeugnis"] = 1
        m["Wahlfach 2 Prüfungsnote"] = 1
        if (p.school == School.M_ZUG && i.confirmations["projectInsteadOfSecondChoice"] != true) {
            m["Zusätzliches Wahlfach Jahres-/Zwischenzeugnis"] = 2
            if (i.confirmations["secondLanguageOral"] == true) {
                m["Zusätzliches Wahlfach schriftlich"] = 1
                m["Zusätzliches Wahlfach mündlich"] = 1
            } else m["Zusätzliches Wahlfach Prüfungsnote"] = 2
        } else {
            m["Wirtschaft und Beruf Jahresnote"] = 1
            m["Berufsorientierendes Wahlpflichtfach Jahresnote"] = 1
            m["Projektprüfung"] = 2
        }
        if (i.confirmations["germanExtra"] == true) m["Deutsch zusätzliche mündliche Prüfung"] = 0
        if (i.confirmations["mathExtra"] == true) m["Mathematik zusätzliche mündliche Prüfung"] = 0
        return m
    }

    fun middle(p: Profile, i: GraduationInput): RuleResult {
        val ms = p.school == School.M_ZUG
        val src =
            if (ms) listOf(law("BayMSO-29"), law("BayMSO-31"))
            else listOf(law("BayRSO-39"), law("BayRSO-40"))
        if (p.grade != 10 || p.school !in listOf(School.REALSCHULE, School.M_ZUG))
            return RuleResult(
                Verdict.UNSUPPORTED,
                "Mittlerer Abschluss erfordert Realschule 10 oder M10",
                emptyList(),
                src,
            )
        if (
            i.confirmations["allOfficial"] != true ||
                i.numbers.isEmpty() ||
                i.numbers.values.any { it == null || it !in 1..6 } ||
                i.numbers["Deutsch"] == null ||
                ms && (i.numbers["Projektprüfung"] == null || i.numbers["Projekt"] == null)
        )
            return RuleResult(
                Verdict.INCOMPLETE,
                "Offizielle Abschlussnoten fehlen",
                listOf(
                    "Alle von der Schule festgestellten Gesamtnoten der Abschluss-/Vorrückungsfächer vollständig eintragen und bestätigen. Sport auslassen. M10: Gesamtnote Projekt sowie gesonderte Note Projektprüfung erforderlich."
                ),
                src,
            )
        val values =
            i.numbers
                .filterKeys { it != "Projektprüfung" }
                .flatMap { (name, n) ->
                    List(if (ms && name == "Projekt") 2 else 1) { name to n!! }
                }
        val six = values.count { it.second == 6 }
        val five = values.count { it.second == 5 }
        val hard =
            i.numbers["Deutsch"] == 6 ||
                ms &&
                    (i.numbers["Projektprüfung"] == 6 ||
                        five >= 2 && i.numbers["Projektprüfung"] == 5)
        val fail = hard || six > 0 || five >= 2
        val possible =
            !hard &&
                (if (ms) true else (six == 1 && five == 0 || six == 0 && five == 2)) &&
                (values.any { it.second == 1 } ||
                    values.count { it.second == 2 } >= 2 ||
                    values.count { it.second <= 3 } >= if (ms) 3 else 4)
        val outcome =
            when {
                !fail -> Verdict.SATISFIED
                possible && !ms -> Verdict.SATISFIED
                possible -> Verdict.SCHOOL_DECISION
                else -> Verdict.RISK
            }
        return RuleResult(
            outcome,
            when {
                !fail -> "Notenbedingungen für den Abschluss erfüllt"
                possible ->
                    if (ms) "Notenausgleich kann gewährt werden"
                    else "Notenbedingungen für Ausgleich erfüllt"
                else -> "Abschlussbedingungen nicht erfüllt"
            },
            values.filter { it.second >= 5 }.distinct().map { "${it.first}: ${it.second}" } +
                listOf(
                    "Grundlage sind offizielle Gesamtnoten; ein guter Durchschnitt hebt Mindestbedingungen nicht auf.",
                    if (ms)
                        "Projekt zählt bei der Ausgleichswertung wie zwei Abschlussfächer. Über den Ausgleich entscheidet der Prüfungsausschuss."
                    else "Deutsch 6 und weitere mangelhafte Fächer schließen den Ausgleich aus.",
                ),
            src,
        )
    }

    fun examPoints(e: AbiturExam): Int? {
        val s = e.points ?: return null
        require(
            s in 0..15 &&
                (e.oralAddition == null || e.oralAddition in 0..15) &&
                (e.practical == null || e.practical in 0..15)
        )
        return if (e.practical != null) {
            if (e.oralAddition == null) (s + e.practical) * 2
            else ((s + e.practical + e.oralAddition) * 4.0 / 3).roundToInt()
        } else if (e.oralAddition != null) ((2 * s + e.oralAddition) * 4.0 / 3).roundToInt()
        else s * 4
    }

    fun abitur(p: Profile, i: GraduationInput): RuleResult {
        val src =
            listOf(
                law("BayGSO-44"),
                law("BayGSO-53"),
                law("BayGSO-54"),
                law("BayGSO-ANL_17"),
                law("BayGSO-ANL_21"),
                law("BayGSO-ANL_23"),
            )
        if (p.school != School.GYMNASIUM || !p.points)
            return RuleResult(
                Verdict.UNSUPPORTED,
                "Abitur erfordert Qualifikationsphase",
                emptyList(),
                src,
            )
        val missing = mutableListOf<String>()
        val failures = mutableListOf<String>()
        if (i.courses.isEmpty()) missing.add("Kurse und offizielle Halbjahresleistungen fehlen.")
        if (i.exams.size != 5 || i.exams.map { it.name }.distinct().size != 5)
            missing.add("Fünf unterschiedliche Abiturprüfungsfächer erforderlich.")
        val courses = i.courses.associateBy { it.name }
        if (courses.size != i.courses.size) missing.add("Kursnamen müssen eindeutig sein.")
        i.courses.forEach { c ->
            if (
                c.points.size != 4 ||
                    c.included.size != 4 ||
                    c.points.any { it == null || it !in 0..15 }
            )
                missing.add(
                    "${c.name}: vier vollständige Halbjahresangaben (nicht belegte Halbjahre mit 0, nicht einbringen)."
                )
        }
        if (i.exams.any { it.points == null || !courses.containsKey(it.name) })
            missing.add("Prüfungspunkte und zugehörige Kurse fehlen.")
        i.exams.forEach { e ->
            if (
                e.points != null && e.points !in 0..15 ||
                    e.oralAddition != null && e.oralAddition !in 0..15 ||
                    e.practical != null && e.practical !in 0..15
            )
                missing.add("${e.name}: gültige Prüfungspunkte 0–15 erforderlich.")
            if (!e.written && e.oralAddition != null)
                missing.add("${e.name}: Zusatzprüfung nur zum schriftlichen Prüfungsfach.")
            val needsPractical = e.name == "Sport" || e.name == "Musik" && e.written
            if (needsPractical && e.practical == null)
                missing.add("${e.name}: praktische Fachprüfung fehlt.")
            if (!needsPractical && e.practical != null)
                missing.add("${e.name}: praktische Fachprüfung gehört nicht zu diesem Verfahren.")
        }
        val seminar = i.numbers["Seminararbeit"]
        val talk = i.numbers["Seminargespräch"]
        if (seminar !in 0..15 || talk !in 0..15)
            missing.add("Seminararbeit und Prüfungsgespräch: 0–15 Punkte erforderlich.")
        listOf("subjectsApproved", "hoursAndSecondLanguage", "seminarSubmitted", "allExamsTaken")
            .forEach {
                if (i.confirmations[it] != true)
                    missing.add(
                        when (it) {
                            "subjectsApproved" ->
                                "Fachwahl und Einbringung von der Schule bestätigt (einschließlich genehmigter Ersatz-/Vertiefungsregelungen)."
                            "hoursAndSecondLanguage" ->
                                "Belegung, 124/126 Halbjahreswochenstunden und zweite Fremdsprache nachweisen."
                            "seminarSubmitted" ->
                                "Seminararbeit abgegeben und beide Seminarhalbjahre eingebracht."
                            else -> "Alle vorgeschriebenen Prüfungen abgelegt."
                        }
                    )
            }
        if (missing.isNotEmpty())
            return RuleResult(Verdict.INCOMPLETE, "Abiturangaben unvollständig", missing, src)
        val selected =
            i.courses.flatMap { c ->
                c.points.filterIndexed { index, _ -> c.included[index] }.map { it!! }
            }
        val seminarScore = ((seminar!! * 2 + talk!!) * 2.0 / 3).roundToInt()
        val block1 = selected.sum() + seminarScore
        if (selected.size != 38)
            failures.add(
                "Einbringung: ${selected.size+2} statt 40 Halbjahresleistungen (Seminararbeit zählt zweifach)."
            )
        val seminarCourse = i.courses.singleOrNull { it.group == "Seminar" }
        if (seminarCourse == null || !seminarCourse.included[0] || !seminarCourse.included[1])
            failures.add("W-Seminar: Halbjahresleistungen 12/1 und 12/2 fehlen in der Einbringung.")
        if (i.courses.any { it.area !in 0..3 || it.requiredTerms !in listOf(0, 2, 4) })
            failures.add("Unzulässiges Aufgabenfeld oder Belegungsintervall.")
        if (selected.any { it == 0 })
            failures.add("Mindestens eine eingebrachte Halbjahresleistung hat 0 Punkte.")
        if (selected.count { it >= 5 } + (if (seminarScore >= 9) 2 else 0) < 32)
            failures.add("Weniger als 32 ausreichende Halbjahresleistungen.")
        if (block1 < 200) failures.add("Block I: $block1 < 200 Punkte.")
        if (seminar == 0 || talk == 0) failures.add("Seminararbeit oder Gespräch mit 0 Punkten.")
        val advanced = i.courses.filter { it.advanced }
        if (advanced.size != 1) failures.add("Genau ein Leistungsfach erforderlich.")
        val trio =
            (listOfNotNull(courses["Deutsch"], courses["Mathematik"]) + advanced).distinctBy {
                it.name
            }
        if (trio.size != 3 || trio.sumOf { it.points.sumOf { n -> n!! } } < 48)
            failures.add(
                "Deutsch, Mathematik und Leistungsfach: mindestens 48 Punkte erforderlich."
            )
        if (i.exams.sumOf { courses.getValue(it.name).points.sumOf { n -> n!! } } < 100)
            failures.add("Fünf Prüfungsfächer: mindestens 100 Halbjahrespunkte erforderlich.")
        val obligatory = (i.exams.map { it.name } + listOf("Deutsch", "Mathematik")).toSet()
        obligatory.forEach { name ->
            if (courses[name]?.included?.all { it } != true)
                failures.add("$name: alle vier Halbjahresleistungen einbringen.")
        }
        listOf("Sprache", "Naturwissenschaft").forEach { group ->
            if (i.courses.filter { it.group == group }.sumOf { it.included.count { b -> b } } < 4)
                failures.add(
                    "$group: mindestens vier verpflichtende Halbjahresleistungen einbringen."
                )
        }
        i.courses
            .filter { it.name !in obligatory && it.requiredTerms > 0 && it.group != "Sport" }
            .forEach { c ->
                val need = if (c.requiredTerms == 4) 3 else 1
                if (c.included.count { it } < need)
                    failures.add(
                        "${c.name}: mindestens $need Halbjahresleistungen oder genehmigte Ersetzung erforderlich."
                    )
            }
        if (
            i.courses
                .filter { it.group == "Sport" && it.name !in obligatory }
                .sumOf { it.included.count { b -> b } } > 3
        )
            failures.add("Sport ohne Abiturprüfung: höchstens drei Einbringungen.")
        if (i.exams.count { it.written } != 3)
            failures.add("Drei schriftliche und zwei mündliche Prüfungsfächer erforderlich.")
        if (
            i.exams
                .map { courses.getValue(it.name).area }
                .toSet()
                .containsAll(listOf(1, 2, 3))
                .not()
        )
            failures.add("Die drei Aufgabenfelder sind nicht abgedeckt.")
        val scored = i.exams.associate { it.name to examPoints(it)!! }
        val block2 = scored.values.sum()
        if (scored.values.any { it < 4 })
            failures.add("Mindestens eine Prüfung unter 4 Punkten in vierfacher Wertung.")
        if (block2 < 100) failures.add("Block II: $block2 < 100 Punkte.")
        if (scored.values.count { it >= 20 } < 3 || trio.none { (scored[it.name] ?: -1) >= 20 })
            failures.add(
                "Drei Prüfungen mit mindestens 20 Punkten, darunter Deutsch, Mathematik oder Leistungsfach, erforderlich."
            )
        val trioNames = trio.map { it.name }.toSet()
        val base =
            when {
                "Deutsch" !in scored -> listOf("Mathematik", advanced.singleOrNull()?.name)
                "Mathematik" !in scored -> listOf("Deutsch", advanced.singleOrNull()?.name)
                else -> listOf("Deutsch", "Mathematik")
            }.filterNotNull()
        val candidates =
            i.exams.filter { e ->
                e.name !in base &&
                    courses.getValue(e.name).group in listOf("Sprache", "Naturwissenschaft") &&
                    ("Deutsch" in scored && "Mathematik" in scored || e.name !in trioNames)
            }
        if (
            candidates.none { c ->
                val points = (base + c.name).map { scored[it] ?: 0 }
                points.sum() >= 40 && points.count { it < 16 } <= 1
            }
        )
            failures.add(
                "Kernkombination nach § 54 Abs. 1 Nr. 6: 40 Punkte, höchstens einmal unter 16, nicht erreicht."
            )
        i.exams
            .groupBy { courses.getValue(it.name).area }
            .filterKeys { it in 1..3 }
            .forEach { (area, exams) ->
                if (exams.count { scored.getValue(it.name) < 16 } > 1)
                    failures.add("Aufgabenfeld $area: mehr als eine Prüfung unter 16 Punkten.")
            }
        val total = block1 + block2
        if (total < 300) failures.add("Gesamtpunktzahl unter 300.")
        val grade = if (failures.isEmpty()) abiturGrade(total) else null
        return RuleResult(
            if (failures.isEmpty()) Verdict.SATISFIED else Verdict.RISK,
            if (failures.isEmpty()) "Erfasste Abiturbedingungen erfüllt"
            else "Abiturbedingungen nicht erfüllt",
            listOf("Block I: $block1 / 600 · Block II: $block2 / 300 · Gesamt: $total / 900") +
                failures +
                "Fachwahl-/Belegungsbestätigung der Schule ist Teil der Eingaben; keine automatische Kursoptimierung oder Zulassungsentscheidung.",
            src,
            grade?.let { "${(it*10).roundToInt()/10},${(it*10).roundToInt()%10}" },
        )
    }

    fun abiturGrade(points: Int): Double {
        require(points in 300..900)
        return maxOf(10, (1020 - points) / 18) / 10.0
    }
}
