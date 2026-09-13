package de.streberalarm.core

import de.streberalarm.core.SchoolDate as LocalDate

data class RuleDefinition(
    val id: String,
    val source: String,
    val requiredInputs: List<String>,
    val explanation: String,
)

data class RulePackage(
    val id: String,
    val state: String,
    val schoolYears: Set<Int>,
    val validFrom: LocalDate,
    val validUntil: LocalDate?,
    val schools: Set<School>,
    val definitions: List<RuleDefinition>,
) {
    fun matches(profile: Profile) =
        id == profile.ruleVersion &&
            state == profile.state &&
            profile.year in schoolYears &&
            profile.school in schools
}

/** Jurisdiction is resolved before any calculation; never fall back across states or years. */
object RulePackages {
    val bayern2026 =
        RulePackage(
            "BY-2026.08-v1",
            "Bayern",
            setOf(2026),
            LocalDate.of(2026, 8, 1),
            null,
            School.entries.toSet(),
            listOf(
                RuleDefinition(
                    "grundschule-grades",
                    law("BayVSO-15"),
                    listOf("actualGrades", "schoolWeights"),
                    "Pädagogische Zeugnisbildung; Englisch ohne Note.",
                ),
                RuleDefinition(
                    "grundschule-transfer",
                    law("BayVSO-6"),
                    listOf("officialTransferGrades", "schoolEligibility", "entryRequirements"),
                    "D/M/HSU: Summe bis 7 für Gymnasium, bis 8 für Realschule; Probeunterricht und schulische Entscheidungen getrennt.",
                ),
                RuleDefinition(
                    "gymnasium-grades",
                    law("BayGSO-28"),
                    listOf("annualSchoolworks", "actualGrades", "schoolWeights"),
                    "Große und kleine Leistungen getrennt; Jahresregelung bestimmt das Verhältnis.",
                ),
                RuleDefinition(
                    "gymnasium-upper-grades",
                    law("BayGSO-29"),
                    listOf("term", "courseLevel", "actualPoints", "schoolWeights"),
                    "Halbjahresleistungen und besondere Fachbewertungen nach § 29.",
                ),
                RuleDefinition(
                    "realschule-grades",
                    law("BayRSO-23"),
                    listOf("actualGrades", "assessmentKind", "schoolWeights"),
                    "Große Leistungen grundsätzlich doppelt; schulische Einzelgewichtung berücksichtigen.",
                ),
                RuleDefinition(
                    "mittelschule-grades",
                    law("BayMSO-12"),
                    listOf("actualGrades", "schoolWeights"),
                    "Schulische Bewertungsgrundlage erforderlich.",
                ),
                RuleDefinition(
                    "promotion-gymnasium",
                    law("BayGSO-30"),
                    listOf("completeSubjectRoster", "officialAnnualGrades", "track"),
                    "Notenbedingung und Voraussetzungen für schulische Ausgleichsentscheidung getrennt.",
                ),
                RuleDefinition(
                    "promotion-realschule",
                    law("BayRSO-24"),
                    listOf("completeSubjectRoster", "officialAnnualGrades"),
                    "Vorrücken auf Probe und Nachprüfung sind gesonderte schulische Verfahren.",
                ),
                RuleDefinition(
                    "promotion-mittelschule",
                    law("BayMSO-15"),
                    listOf("pathway", "completeSubjectRoster", "officialAnnualGrades"),
                    "Regelklasse und M-Zug unterscheiden sich.",
                ),
                RuleDefinition(
                    "quali",
                    law("BayMSO-25"),
                    listOf("eligibility", "chosenSubjects", "officialComponents"),
                    "Gewichtete Summe, Teiler 18; eine Dezimalstelle ohne Rundung.",
                ),
                RuleDefinition(
                    "realschule-certificate",
                    law("BayRSO-39"),
                    listOf("completeOfficialFinalGrades"),
                    "Prüfungsausschuss stellt Gesamtnoten fest; Ausgleich nach § 40.",
                ),
                RuleDefinition(
                    "m10-certificate",
                    law("BayMSO-31"),
                    listOf("completeOfficialFinalGrades", "projectExam"),
                    "Projektprüfung und Deutsch haben eigenständige Mindestbedingungen.",
                ),
                RuleDefinition(
                    "abitur-admission",
                    law("BayGSO-44"),
                    listOf("courses", "inclusion", "seminar", "hours", "secondLanguage"),
                    "Punktegrenzen und Belegungsnachweise getrennt prüfen.",
                ),
                RuleDefinition(
                    "abitur-inclusion",
                    law("BayGSO-53"),
                    listOf("courses", "inclusion", "approvedSubjectChoice"),
                    "40 Halbjahresleistungen einschließlich zweifacher Seminararbeit.",
                ),
                RuleDefinition(
                    "abitur-result",
                    law("BayGSO-54"),
                    listOf("admission", "fiveExams", "allExamsTaken"),
                    "Mindestbedingungen gehen der Durchschnittsberechnung voraus.",
                ),
            ),
        )
    private val packages = listOf(bayern2026)

    fun resolve(profile: Profile): RulePackage? = packages.singleOrNull { it.matches(profile) }
}
