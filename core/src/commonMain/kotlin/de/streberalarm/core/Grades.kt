package de.streberalarm.core

data class Calculation(
    val value: Double?,
    val explanation: String,
    val warnings: List<String> = emptyList(),
    val source: String? = null,
)

object Grades {
    fun semesterPoints(value: Double): Int {
        require(value in 0.0..15.0)
        return if (value < 1.0) 0 else kotlin.math.floor(value + 0.5).toInt()
    }

    private fun mean(values: List<Pair<Int, Double>>): Double? {
        if (values.isEmpty()) return null
        // IEEE 754 on every target; no display or statutory rounding at intermediate steps.
        var sum = 0.0
        var compensation = 0.0
        values.forEach { (n, w) ->
            val adjusted = n * w - compensation
            val next = sum + adjusted
            compensation = (next - sum) - adjusted
            sum = next
        }
        return sum / values.sumOf { it.second }
    }

    fun calculate(
        profile: Profile,
        subject: Subject,
        assessments: List<Assessment>,
        scenario: Boolean = false,
    ): Calculation {
        if (profile.school == School.GRUNDSCHULE && subject.name.trim().equals("Englisch", true))
            return Calculation(
                null,
                "In der Grundschule wird in Englisch keine Note erteilt.",
                source = law("BayVSO-15"),
            )
        val rows =
            assessments
                .filter { it.subjectId == subject.id && it.stage != Stage.CANCELLED }
                .mapNotNull { a ->
                    (a.actual ?: if (scenario) a.estimate else null)?.let { a to it }
                }
        if (rows.isEmpty())
            return Calculation(
                null,
                "Noch keine ${if(scenario) "Bewertungen oder Einschätzungen" else "tatsächlichen Bewertungen"}.",
            )
        if (RulePackages.resolve(profile) == null)
            return Calculation(
                null,
                "Für dieses Bundesland oder Schuljahr ist kein geprüftes Regelpaket vorhanden.",
            )
        val warnings =
            mutableListOf("Rechnerischer Leistungsstand, keine verbindliche Zeugnisnote.")
        if (!subject.weightsConfirmed)
            warnings.add(
                "Zusätzliche Gewichtungen der Lehrkraft sind nicht hinterlegt. Der Schnitt ist vorläufig."
            )
        val weighted =
            rows.map { (a, n) ->
                n to
                    a.weight *
                        if (a.kind.large) 1.0
                        else
                            when (a.kind) {
                                Kind.ORAL -> subject.oralWeight
                                Kind.PRACTICAL -> subject.practicalWeight
                                else -> subject.smallWrittenWeight
                            }
            }
        val source =
            "https://www.gesetze-bayern.de/Content/Document/" +
                when (profile.school) {
                    School.GYMNASIUM -> if (profile.points) "BayGSO-29" else "BayGSO-28"
                    School.REALSCHULE -> "BayRSO-23"
                    School.GRUNDSCHULE -> "BayVSO-11"
                    else -> "BayMSO-12"
                }
        if (profile.school == School.GYMNASIUM) {
            fun component(kinds: Set<Kind>) =
                mean(rows.filter { it.first.kind in kinds }.map { (a, n) -> n to a.weight })
            fun smallComponent(excluded: Set<Kind>) =
                mean(
                    rows
                        .filter { !it.first.kind.large && it.first.kind !in excluded }
                        .map { (a, n) ->
                            n to
                                a.weight *
                                    when (a.kind) {
                                        Kind.ORAL -> subject.oralWeight
                                        Kind.PRACTICAL -> subject.practicalWeight
                                        else -> subject.smallWrittenWeight
                                    }
                        }
                )
            if (profile.points && subject.name == "Sport") {
                val practical = component(setOf(Kind.PRACTICAL))
                val small =
                    smallComponent(setOf(Kind.PRACTICAL, Kind.THEORY_SMALL, Kind.THEORY_LARGE))
                if (practical == null || small == null)
                    return Calculation(
                        null,
                        "Sport: praktische und kleine Leistungen fehlen noch.",
                        warnings,
                        source,
                    )
                val sport = (2 * practical + small) / 3
                if (!subject.advanced)
                    return Calculation(
                        sport,
                        "Sport: (praktischer Durchschnitt × 2 + kleine Leistungen) ÷ 3.",
                        warnings,
                        source,
                    )
                val theoryLarge = component(setOf(Kind.THEORY_LARGE))
                val theorySmall = component(setOf(Kind.THEORY_SMALL))
                return if (theoryLarge == null || theorySmall == null)
                    Calculation(
                        null,
                        "Leistungsfach Sport: Schulaufgabe und kleine Leistungen der Sporttheorie fehlen.",
                        warnings,
                        source,
                    )
                else
                    Calculation(
                        (sport + (theoryLarge + theorySmall) / 2) / 2,
                        "Leistungsfach Sport: Praxisbereich und Sporttheorie je zur Hälfte; Sporttheorie aus Schulaufgabe und kleinen Leistungen 1:1.",
                        warnings,
                        source,
                    )
            }
            if (profile.points && subject.advanced && subject.name in listOf("Kunst", "Musik")) {
                val large = component(setOf(Kind.SCHOOLWORK))
                val special =
                    component(
                        setOf(
                            if (subject.name == "Kunst") Kind.ART_PROJECT else Kind.PRACTICAL_EXAM
                        )
                    )
                val small = smallComponent(setOf(Kind.ART_PROJECT, Kind.PRACTICAL_EXAM))
                return if (large == null || special == null || small == null)
                    Calculation(
                        null,
                        "Schulaufgabe, ${if(subject.name=="Kunst")"künstlerisches Projekt" else "praktische Fachprüfung"} und kleine Leistungen erforderlich.",
                        warnings,
                        source,
                    )
                else
                    Calculation(
                        (large + special + small) / 3,
                        "Schulaufgabe, besondere Fachleistung und Durchschnitt kleiner Leistungen je zu einem Drittel.",
                        warnings,
                        source,
                    )
            }
            if (!profile.points && subject.name == "Musik" && profile.track == "MuG") {
                val instrument = component(setOf(Kind.INSTRUMENT))
                val classRows = assessments.filter { it.kind != Kind.INSTRUMENT }
                val classResult =
                    calculate(profile.copy(track = "NTG"), subject, classRows, scenario)
                return if (instrument == null || classResult.value == null)
                    Calculation(
                        null,
                        "Instrumentalbereich und Klassenunterricht getrennt erfassen. ${classResult.explanation}",
                        warnings,
                        source,
                    )
                else
                    Calculation(
                        (instrument + classResult.value) / 2,
                        "Instrument/Gesang und Klassenunterricht je zur Hälfte. Klassenunterricht: ${classResult.explanation}",
                        warnings,
                        source,
                    )
            }
            val small =
                mean(
                    rows
                        .filterNot { it.first.kind.large }
                        .map { (a, n) ->
                            n to
                                a.weight *
                                    when (a.kind) {
                                        Kind.ORAL -> subject.oralWeight
                                        Kind.PRACTICAL -> subject.practicalWeight
                                        else -> subject.smallWrittenWeight
                                    }
                        }
                )
            val large = mean(rows.filter { it.first.kind.large }.map { (a, n) -> n to a.weight })
            val noLarge =
                if (profile.points)
                    profile.term == "13/2" && !subject.advanced || subject.name == "W-Seminar"
                else subject.annualSchoolworks == 0
            if (noLarge)
                return Calculation(
                    small,
                    "Durchschnitt der kleinen Leistungsnachweise; Schulaufgaben werden hier nicht berücksichtigt.",
                    warnings,
                    source,
                )
            val ratio =
                if (profile.points) 1
                else
                    when (subject.annualSchoolworks) {
                        2 -> 1
                        in 3..12 -> 2
                        else ->
                            return Calculation(
                                null,
                                "Die für das ganze Schuljahr geltende Schulaufgabenanzahl fehlt oder erfordert eine besondere schulische Regelung.",
                                warnings,
                                source,
                            )
                    }
            if (large == null || small == null)
                return Calculation(
                    null,
                    "Für das Verhältnis $ratio:1 fehlen ${if(large==null) "große" else "kleine"} Leistungsnachweise.",
                    warnings,
                    source,
                )
            return Calculation(
                (large * ratio + small) / (ratio + 1),
                "(Große Leistungen $large × $ratio + kleine Leistungen $small) ÷ ${ratio+1}. Zwischenwerte ungerundet.",
                warnings,
                source,
            )
        }
        val value =
            if (profile.school == School.REALSCHULE)
                mean(
                    rows.mapIndexed { i, (a, n) ->
                        n to weighted[i].second * if (a.kind.large) 2 else 1
                    }
                )
            else mean(weighted)
        return Calculation(
            value,
            if (profile.school == School.REALSCHULE)
                "Gewichteter Durchschnitt; Schulaufgaben und angesagte Tests nach § 18 Abs. 3 zählen doppelt. Schulische Einzelgewichte zusätzlich berücksichtigt."
            else if (profile.school == School.GRUNDSCHULE)
                "Gewichteter Durchschnitt der erfassten Einzelnoten als Orientierung. Die Lehrkraft bildet die Zeugnisnoten in pädagogischer Verantwortung (§ 15 GrSO); kein allgemeines Verhältnis schriftlich:mündlich vorgegeben."
            else
                "Gewichteter Durchschnitt nach erfassten schulischen Angaben. Die MSO legt hierfür kein allgemeines Verhältnis schriftlich:mündlich fest.",
            warnings,
            source,
        )
    }
}
