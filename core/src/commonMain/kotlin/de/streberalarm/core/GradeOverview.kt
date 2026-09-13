package de.streberalarm.core

import kotlin.math.ceil
import kotlin.math.floor

/** The overview is a read-only forecast. It never creates or changes an Official record. */
enum class TrafficLight {
    GREEN,
    AMBER,
    RED,
    UNKNOWN,
}

data class SubjectStanding(
    val subject: Subject,
    val average: Double?,
    val grade: Int?,
    val annual: Boolean,
    val omitted: Boolean,
)

data class GradeGoal(val subjectId: String, val name: String, val grade: Int)

data class GradePlan(val goals: List<GradeGoal>, val alreadyMet: Boolean = false)

data class GradeOverview(
    val average: Double?,
    val averageCount: Int,
    val subjectCount: Int,
    val light: TrafficLight,
    val title: String,
    val basis: String,
    val reason: String,
    val advice: String,
    val improvements: List<GradePlan>,
    val compensation: List<GradePlan>,
    val notes: List<String>,
    val standings: List<SubjectStanding>,
    val sources: List<String>,
)

/** Verified exclusions, not a guess at whether an arbitrary custom subject is compulsory. */
object PromotionSubjects {
    fun correct(p: Profile, s: Subject): Subject {
        if (!Rules.supported(p)) return s
        val name = s.name.trim().lowercase()
        val excluded =
            when (p.school) {
                School.GRUNDSCHULE ->
                    return s.copy(
                        core = Transfer.subjectIndex(s.name) != null,
                        promotion = Transfer.subjectIndex(s.name) != null,
                    )
                School.REALSCHULE -> {
                    val optional =
                        name in setOf("textiles gestalten", "kunst", "werken", "musik", "sport")
                    val selected =
                        p.track.startsWith("III") &&
                            p.track.substringAfter(" · ", "").trim().equals(s.name.trim(), true)
                    optional && !selected
                }
                School.GYMNASIUM ->
                    !p.points &&
                        (name in setOf("sport", "modul zur beruflichen orientierung") ||
                            name == "musik" && p.grade < 7 && p.track != "MuG")
                else -> name == "sport"
            }
        return if (excluded) s.copy(core = false, promotion = false) else s
    }
}

object GradeOverviewCalculator {
    fun calculate(p: Profile, d: SchoolData): GradeOverview {
        val subjects =
            d.subjects.filter { it.profileId == p.id }.map { PromotionSubjects.correct(p, it) }
        val standings =
            subjects.map { s ->
                val average = Grades.calculate(p, s, d.assessments).value
                val official =
                    d.officials.firstOrNull {
                        it.subjectId == s.id &&
                            it.period == "Jahreszeugnis" &&
                            (it.omitted || it.value != null)
                    }
                SubjectStanding(
                    s,
                    average,
                    if (p.points) null
                    else
                        official?.let { if (it.omitted) 6 else it.value }
                            ?: average?.let { floor(it + 0.5).toInt().coerceIn(1, 6) },
                    official != null,
                    official?.omitted == true,
                )
            }
        val averages = standings.mapNotNull { it.average }
        val relevant = standings.filter { it.subject.promotion }
        val known = relevant.filter { it.grade != null }
        val missing = relevant.filter { it.grade == null }
        val forecast = known.any { !it.annual }
        val basis =
            when {
                known.isEmpty() -> "Noch keine Grundlage für die Ampel."
                forecast ->
                    "Prognose: Fachschnitte für die Ampel gerundet; vorhandene Jahreszeugnisnoten haben Vorrang."
                else -> "Ampel aus eingetragenen Jahreszeugnisnoten."
            }
        fun result(
            light: TrafficLight,
            title: String,
            reason: String,
            advice: String,
            improvements: List<GradePlan> = emptyList(),
            compensation: List<GradePlan> = emptyList(),
            notes: List<String> = emptyList(),
            sources: List<String> = emptyList(),
        ) =
            GradeOverview(
                averages.takeIf { it.isNotEmpty() }?.average(),
                averages.size,
                subjects.size,
                light,
                title,
                basis,
                reason,
                advice,
                improvements,
                compensation,
                notes,
                relevant,
                sources,
            )
        if (p.school == School.GRUNDSCHULE)
            return result(
                TrafficLight.UNKNOWN,
                "Übertritt",
                "Hier geht es um deinen nächsten Schulweg.",
                "Die eigene Übertrittsansicht berücksichtigt Deutsch, Mathematik und HSU.",
            )
        if (!Rules.supported(p))
            return result(
                TrafficLight.UNKNOWN,
                "Noch keine Ampel",
                "Für dieses Bundesland oder Schuljahr fehlt das passende Regelpaket.",
                "Derzeit sind nur die hinterlegten bayerischen Regeln für 2026/27 verfügbar.",
            )
        if (
            p.points ||
                p.grade >= 10 && p.school != School.GYMNASIUM ||
                p.grade == 9 && p.school == School.MITTELSCHULE
        )
            return result(
                TrafficLight.UNKNOWN,
                if (p.points) "Oberstufe" else "Abschlussklasse",
                "Hier gelten eigene Abschlussbedingungen statt der normalen Vorrückungsregel.",
                "Ein guter Gesamtschnitt allein reicht für den Abschluss nicht aus.",
            )
        val sources =
            when (p.school) {
                School.REALSCHULE -> listOf("BayRSO-24", "BayRSO-25", "BayRSO-26", "BayRSO-27")
                School.GYMNASIUM ->
                    listOf("BayGSO-16", "BayGSO-30", "BayGSO-32", "BayGSO-31", "BayGSO-33")
                else -> listOf("BayMSO-15", "BayMSO-16")
            }.map(::law)
        if (known.isEmpty())
            return result(
                TrafficLight.UNKNOWN,
                "Noch fehlen Noten",
                "Für die angelegten Vorrückungsfächer ist noch keine Auswertung möglich.",
                "Trage deine Noten ein. Dann erscheint hier dein aktueller Stand.",
                sources = sources,
            )
        val grades = known.map { it.grade!! }
        val bad = known.filter { it.grade!! >= 5 }
        val numericRisk = Rules.promotionGradesFail(p.school, grades)
        val fixedRisk =
            if (p.school == School.MITTELSCHULE)
                grades.sumOf { if (it == 6) 2 else if (it == 5) 1 else 0 } > 3
            else numericRisk
        val missingNote =
            missing
                .takeIf { it.isNotEmpty() }
                ?.let { "Noch ohne Grundlage: ${it.joinToString { row -> row.subject.name }}." }
        val notes =
            mutableListOf(
                "Die Ampel berücksichtigt die angelegten Vorrückungsfächer. Eine vollständige Fächerliste ist Voraussetzung; sie ersetzt keine Entscheidung der Schule.",
                "Der Gesamtschnitt ist der ungerundete Mittelwert der bekannten Fachschnitte. Jedes Fach zählt einmal, auch Nicht-Vorrückungsfächer; Einschätzungen zählen nicht mit.",
                "Gerundet wird nur für diese Prognose (ab x,5 zur nächsthöheren Note). Daraus wird keine Zeugnisnote gespeichert. Besondere Deutsch-/Förderregelungen und persönliche Voraussetzungen klärt die Schule.",
            )
        missingNote?.let(notes::add)
        if (missing.isNotEmpty() && !fixedRisk)
            return result(
                TrafficLight.UNKNOWN,
                "Noch fehlen Noten",
                missingNote!!,
                "Eine grüne Ampel wäre mit diesen Lücken noch nicht aussagekräftig.",
                notes = notes,
                sources = sources,
            )
        val badText =
            bad.joinToString(" · ") {
                "${it.subject.name}: ${it.grade}${if (it.omitted) " (fehlende Bewertung)" else ""}"
            }
        if (!numericRisk) {
            val worstGrades =
                known.map {
                    if (it.annual) it.grade!! else ceil(it.average!!).toInt().coerceIn(1, 6)
                }
            val boundary = forecast && Rules.promotionGradesFail(p.school, worstGrades)
            val social = p.school == School.GYMNASIUM && p.grade == 11 && p.track == "SWG"
            val caution = bad.isNotEmpty() || boundary || social
            val reason =
                when {
                    social ->
                        "Die Notenbedingung passt. Das erforderliche Sozialpraktikum muss zusätzlich geklärt sein."
                    bad.isNotEmpty() -> "$badText. Die rechnerische Notenbedingung passt noch."
                    boundary ->
                        "Die Prognose passt noch. Wenn die Schule an den Notengrenzen die schlechtere Note festlegt, kann es kippen."
                    else -> "Die aktuelle Notenbedingung ist erfüllt."
                }
            return result(
                if (caution) TrafficLight.AMBER else TrafficLight.GREEN,
                if (caution) "Im Blick behalten" else "Aktuell im grünen Bereich",
                reason,
                if (social)
                    "Für den Wechsel in Klasse 12 sind 15 erfolgreich absolvierte Praktikumstage nötig."
                else if (caution)
                    "Sichere die schwächeren Vorrückungsfächer möglichst mit einer 4 oder besser ab."
                else "Aktuell ist kein Notenausgleich nötig.",
                notes = notes,
                sources = sources,
            )
        }
        val improvements = improvementPlans(p, known)
        val compensation = compensationPlans(p, known)
        val matches = compensation.any { it.alreadyMet }
        val advice =
            when (p.school) {
                School.REALSCHULE ->
                    "An der Realschule gleichen gute Noten in anderen Fächern diese Noten nicht automatisch aus. Ziel: keine 6 und höchstens eine 5 in Vorrückungsfächern."
                School.GYMNASIUM ->
                    if (p.grade < 10)
                        "In Klasse 5 bis 9 gibt es keinen Notenausgleich durch gute Noten. Ziel: keine 6 und höchstens eine 5."
                    else if (compensation.isNotEmpty())
                        "Ein Notenausgleich kann infrage kommen. Kernfächer lassen sich nur durch Kernfächer ausgleichen. Die Schule entscheidet."
                    else
                        "Für einen Notenausgleich darf höchstens eine 6 oder zweimal eine 5 vorliegen. Verbessere zuerst die schwachen Fächer."
                School.M_ZUG ->
                    if (known.any { it.subject.name.equals("Deutsch", true) && it.grade == 6 })
                        "Mit einer 6 in Deutsch ist der Notenausgleich ausgeschlossen. Deutsch muss sich zuerst verbessern."
                    else
                        "Ein Notenausgleich kann bei höchstens einer 6 oder zwei 5en möglich sein: eine 1, zwei 2en oder drei 3en in Vorrückungsfächern. Die Schule entscheidet."
                School.GRUNDSCHULE -> "Übertrittsansicht verwenden."
                School.MITTELSCHULE ->
                    "In der Regelklasse zählen der Schnitt der Vorrückungsfächer (höchstens 4,00) und höchstens drei Fünfer-Einheiten. Eine 6 zählt doppelt. Die Schule beurteilt auch deine Entwicklung."
            }
        notes.add(
            "Die genannten Ziele sind Fachnoten im Jahreszeugnis, keine einzelnen Testnoten. Verbesserungsziele rechnen mit dem bisherigen Stand der anderen Fächer. Für einen Notenausgleich dürfen neben der auszugleichenden 6 oder den beiden 5en keine weiteren 5en oder 6en vorliegen. Schulische Voraussetzungen sind zusätzliche Bedingungen."
        )
        if (p.school == School.GYMNASIUM && p.grade in 10..11)
            notes.add(
                "Notenausgleich: eine 1 oder zwei 2en in geeigneten Vorrückungsfächern; bei schwachen Kernfächern nur Kernfächer. Alternativ mindestens drei Kernfächer mit 3 oder besser. Kein erneuter Ausgleich, wenn schon der Eintritt in diese Klasse nur durch Ausgleich möglich war. Positive Erfolgsprognose und Schulentscheidung nötig."
            )
        if (p.school == School.M_ZUG)
            notes.add(
                "Notenausgleich ist bei einer 6 in Deutsch oder ungenügender Mitarbeit ausgeschlossen. Auch bei passenden guten Noten entscheidet die Lehrerkonferenz."
            )
        notes.addAll(otherRoutes(p, known))
        return result(
            if (matches) TrafficLight.AMBER else TrafficLight.RED,
            if (matches) "Ausgleich möglich – Schule entscheidet" else "Vorrücken gefährdet",
            (if (badText.isNotBlank()) badText
            else "Der Schnitt der Vorrückungsfächer ist zu schwach.") +
                if (missing.isNotEmpty()) " Weitere Noten fehlen noch." else "",
            advice,
            improvements,
            compensation,
            notes,
            sources,
        )
    }

    private fun improvementPlans(p: Profile, rows: List<SubjectStanding>): List<GradePlan> {
        val bad = rows.filter { it.grade!! >= 5 }
        val variants =
            if (p.school != School.MITTELSCHULE)
                bad.map { survivor ->
                    bad.mapNotNull { r ->
                        val goal = if (r == survivor) 5 else 4
                        if (r.grade!! > goal) GradeGoal(r.subject.id, r.subject.name, goal)
                        else null
                    }
                }
            else {
                val sorted = bad.sortedByDescending { it.grade }
                sorted.indices.map { offset ->
                    val goals = mutableListOf<GradeGoal>()
                    for (r in sorted.drop(offset) + sorted.take(offset)) {
                        if (
                            !Rules.promotionGradesFail(
                                p.school,
                                rows.map { row ->
                                    goals.find { it.subjectId == row.subject.id }?.grade
                                        ?: row.grade!!
                                },
                            )
                        )
                            break
                        goals.add(GradeGoal(r.subject.id, r.subject.name, 4))
                    }
                    goals
                }
            }
        return variants
            .filter { goals ->
                goals.isNotEmpty() &&
                    !Rules.promotionGradesFail(
                        p.school,
                        rows.map { row ->
                            goals.find { it.subjectId == row.subject.id }?.grade ?: row.grade!!
                        },
                    )
            }
            .distinctBy {
                it.map { goal -> goal.subjectId to goal.grade }.sortedBy { goal -> goal.first }
            }
            .sortedWith(
                compareBy<List<GradeGoal>> { goals ->
                        goals.sumOf { goal ->
                            rows.first { it.subject.id == goal.subjectId }.grade!! - goal.grade
                        }
                    }
                    .thenBy { it.size }
            )
            .take(3)
            .map { GradePlan(it) }
    }

    private fun compensationPlans(p: Profile, rows: List<SubjectStanding>): List<GradePlan> {
        val bad = rows.filter { it.grade!! >= 5 }
        val pattern =
            bad.size == 1 && bad.single().grade == 6 || bad.size == 2 && bad.all { it.grade == 5 }
        if (!pattern || p.school !in listOf(School.GYMNASIUM, School.M_ZUG)) return emptyList()
        if (p.school == School.GYMNASIUM && p.grade !in 10..11) return emptyList()
        if (
            p.school == School.M_ZUG &&
                bad.any { it.subject.name.equals("Deutsch", true) && it.grade == 6 }
        )
            return emptyList()
        return listOf(1 to 1, 2 to 2, 3 to 3)
            .mapNotNull { (count, target) ->
                val coreOnly =
                    p.school == School.GYMNASIUM && (target == 3 || bad.any { it.subject.core })
                val candidates =
                    rows
                        .filter { it.grade!! <= 4 && (!coreOnly || it.subject.core) }
                        .sortedWith(
                            compareBy<SubjectStanding> { (it.grade!! - target).coerceAtLeast(0) }
                                .thenBy { it.subject.name }
                        )
                if (candidates.size < count) null
                else {
                    val chosen = candidates.take(count)
                    GradePlan(
                        chosen.map {
                            GradeGoal(it.subject.id, it.subject.name, minOf(it.grade!!, target))
                        },
                        chosen.all { it.grade!! <= target },
                    )
                }
            }
            .sortedBy { !it.alreadyMet }
    }

    private fun otherRoutes(p: Profile, rows: List<SubjectStanding>): List<String> {
        val bad = rows.filter { it.grade!! >= 5 }
        val pattern =
            bad.size == 1 && bad.single().grade == 6 || bad.size == 2 && bad.all { it.grade == 5 }
        val germanSix = bad.any { it.subject.name.equals("Deutsch", true) && it.grade == 6 }
        val coreBad = bad.filter { it.subject.core }
        val routes = mutableListOf<String>()
        when (p.school) {
            School.REALSCHULE -> {
                if (pattern && coreBad.size <= 1 && coreBad.none { it.grade == 6 })
                    routes.add(
                        "Vorrücken auf Probe: Das Notenmuster kann passen. Dafür muss das Klassenziel erstmals verfehlt sein; Eltern und Lehrerkonferenz müssen zustimmen, eine positive Erfolgsprognose ist nötig (§ 26 RSO)."
                    )
                if (p.grade in 7..9 && pattern && !germanSix)
                    routes.add(
                        "Nachprüfung: Das Notenmuster kann passen. Keine Wiederholung dieser Klasse; Eltern müssen die Nachprüfung fristgerecht beantragen. Zulassung und Erfolg stellt die Schule fest (§ 27 RSO)."
                    )
            }
            School.GYMNASIUM -> {
                if (
                    p.grade in 5..9 ||
                        pattern && coreBad.size <= 1 && coreBad.none { it.grade == 6 }
                )
                    routes.add(
                        "Vorrücken auf Probe mit der Schule besprechen. Erstmaliges Verfehlen beziehungsweise die besondere Wiederholungsregel, Einverständnis der Eltern und positive Erfolgsprognose sind zusätzlich erforderlich (§ 31 GSO)."
                    )
                val corePattern =
                    coreBad.isEmpty() ||
                        coreBad.size == 1 ||
                        coreBad.size == 2 && coreBad.all { it.grade == 5 }
                if (p.grade in 6..9 && bad.size <= 3 && corePattern && !germanSix)
                    routes.add(
                        "Nachprüfung: Das Notenmuster kann passen. Keine Wiederholung dieser Klasse; fristgerechter Antrag und erfolgreiche Prüfung erforderlich (§ 33 GSO)."
                    )
            }
            School.M_ZUG ->
                routes.add(
                    "Vorrücken auf Probe kann bei erstmaligem Verfehlen des Klassenziels mit Einverständnis der Eltern und positiver Erfolgsprognose von der Schule erlaubt werden (§ 16 MSO)."
                )
            else -> Unit
        }
        return routes
    }
}
