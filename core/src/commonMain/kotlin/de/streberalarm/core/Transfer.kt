package de.streberalarm.core

enum class TransferRoute {
    DIRECT,
    FORECAST,
    PROBE_NEEDED,
    PROBE_PASSED,
    PARENTS,
    PROBE_FAILED,
    UNKNOWN,
}

data class TransferOverview(
    val average: Double?,
    val values: List<Double?>,
    val official: Boolean,
    val gymnasium: TransferRoute,
    val realschule: TransferRoute,
    val supported: Boolean,
    val duplicateSubjects: Boolean,
)

/**
 * Ordinary grade4 transfer from public/state-recognized Bavarian primary schools. Admission,
 * school-issued eligibility and individual exceptions remain school decisions.
 */
object Transfer {
    const val PERIOD = "Übertrittszeugnis"
    val NAMES = listOf("Deutsch", "Mathematik", "Heimat- und Sachunterricht")
    val sources =
        listOf("BayVSO-6", "BayVSO-15", "BayGSO-2", "BayGSO-3", "BayRSO-2", "BayRSO-3").map(::law)

    fun subjectIndex(name: String): Int? =
        when (name.trim().lowercase()) {
            "deutsch" -> 0
            "mathematik",
            "mathe" -> 1
            "heimat- und sachunterricht",
            "hsu" -> 2
            else -> null
        }

    fun subjects(p: Profile, d: SchoolData): List<Subject?> =
        (0..2).map { index ->
            d.subjects
                .filter { it.profileId == p.id && subjectIndex(it.name) == index }
                .singleOrNull()
        }

    fun probe(german: Int?, math: Int?): TransferRoute =
        when {
            german == null || math == null || german !in 1..6 || math !in 1..6 ->
                TransferRoute.UNKNOWN
            german <= 3 && math <= 4 || math <= 3 && german <= 4 -> TransferRoute.PROBE_PASSED
            german == 4 && math == 4 -> TransferRoute.PARENTS
            else -> TransferRoute.PROBE_FAILED
        }

    fun calculate(p: Profile, d: SchoolData): TransferOverview {
        val supported = Rules.supported(p) && p.school == School.GRUNDSCHULE && p.grade in 3..4
        val subjects = subjects(p, d)
        val duplicates =
            (0..2).any { i ->
                d.subjects.count { it.profileId == p.id && subjectIndex(it.name) == i } > 1
            }
        val official =
            supported &&
                p.grade == 4 &&
                subjects.any { s ->
                    s != null && d.officials.any { it.subjectId == s.id && it.period == PERIOD }
                }
        val values =
            subjects.map { s ->
                if (!supported || s == null) null
                else if (official)
                    d.officials
                        .singleOrNull { it.subjectId == s.id && it.period == PERIOD && !it.omitted }
                        ?.value
                        ?.toDouble()
                else Grades.calculate(p, s, d.assessments).value
            }
        val average = if (values.all { it != null }) values.filterNotNull().average() else null
        val input = d.graduation["${p.id}:uebertritt"]
        fun route(sumLimit: Int, key: String): TransferRoute {
            if (!supported) return TransferRoute.UNKNOWN
            // Use exact integer totals for official grades: 7/3 and 8/3 must not fail
            // because their recurring decimal exceeds the printed 2.33/2.66 threshold.
            val fits = average != null && values.filterNotNull().sum() <= sumLimit.toDouble()
            if (official && fits) return TransferRoute.DIRECT
            if (p.grade == 4) {
                val german = input?.numbers?.get(key + "Deutsch")
                val math = input?.numbers?.get(key + "Mathematik")
                if (german != null || math != null) return probe(german, math)
            }
            if (p.grade == 4 && key == "rs") {
                val gym = probe(input?.numbers?.get("gymDeutsch"), input?.numbers?.get("gymMathematik"))
                if (gym == TransferRoute.PROBE_PASSED || gym == TransferRoute.PARENTS) return gym
            }
            if (average == null) return TransferRoute.UNKNOWN
            return if (!official && fits) TransferRoute.FORECAST else TransferRoute.PROBE_NEEDED
        }
        return TransferOverview(
            average,
            values,
            official,
            route(7, "gym"),
            route(8, "rs"),
            supported,
            duplicates,
        )
    }
}
