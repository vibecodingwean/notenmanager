package de.streberalarm.core

import kotlin.test.*
import kotlin.test.Test

class AbiturTest {
    private val p = Profile(grade = 13, term = "13/2", graduationYear = 2027)

    private fun fixture(): GraduationInput {
        fun course(
            name: String,
            area: Int = 2,
            group: String = "Sonstiges",
            count: Int = 4,
            advanced: Boolean = false,
            required: Int = 4,
        ) =
            AbiturCourse(
                name,
                area,
                group,
                List(4) { 10 },
                List(4) { it < count },
                required,
                advanced,
            )
        val courses =
            listOf(
                course("Deutsch", 1),
                course("Mathematik", 3),
                course("Englisch", 1, "Sprache"),
                course("Physik", 3, "Naturwissenschaft", advanced = true),
                course("Geschichte"),
                course("Ethik", count = 3),
                course("Kunst", 1, count = 3),
                course("Politik und Gesellschaft", count = 3),
                course("Geographie", count = 1, required = 2),
                course("Biologie", 3, "Naturwissenschaft", count = 3),
                course("W-Seminar", 0, "Seminar", count = 2, required = 2),
                course("Sport", 0, "Sport", count = 3),
            )
        return GraduationInput(
            procedure = "Abitur",
            numbers = mapOf("Seminararbeit" to 10, "Seminargespräch" to 10),
            courses = courses,
            exams =
                listOf(
                    AbiturExam("Deutsch", points = 10),
                    AbiturExam("Mathematik", points = 10),
                    AbiturExam("Physik", points = 10),
                    AbiturExam("Englisch", written = false, points = 10),
                    AbiturExam("Geschichte", written = false, points = 10),
                ),
            confirmations =
                listOf(
                        "subjectsApproved",
                        "hoursAndSecondLanguage",
                        "seminarSubmitted",
                        "allExamsTaken",
                    )
                    .associateWith { true },
        )
    }

    @Test
    fun fullReferenceCase() {
        val i = fixture()
        assertEquals(38, i.courses.sumOf { it.included.count { b -> b } })
        val result = Rules.abitur(p, i)
        assertEquals(Verdict.SATISFIED, result.verdict, result.details.joinToString())
        assertEquals("2,3", result.value)
    }

    @Test
    fun zeroExamFailsDespiteExcellentOverallScore() {
        val i = fixture()
        val result =
            Rules.abitur(
                p,
                i.copy(
                    exams = i.exams.mapIndexed { n, e -> e.copy(points = if (n == 0) 0 else 15) }
                ),
            )
        assertEquals(Verdict.RISK, result.verdict)
        assertNull(result.value)
        assertTrue(result.details.any { it.contains("unter 4") })
    }

    @Test
    fun blockOneZeroCannotBeCompensatedByExams() {
        val i = fixture()
        val result =
            Rules.abitur(
                p,
                i.copy(
                    courses =
                        i.courses.map {
                            if (it.name == "Ethik") it.copy(points = listOf(0, 15, 15, 15)) else it
                        }
                ),
            )
        assertEquals(Verdict.RISK, result.verdict)
        assertTrue(result.details.any { it.contains("0 Punkte") })
    }

    @Test
    fun missingProofNotPassed() {
        val i = fixture()
        assertEquals(
            Verdict.INCOMPLETE,
            Rules.abitur(p, i.copy(confirmations = i.confirmations - ("hoursAndSecondLanguage")))
                .verdict,
        )
    }

    @Test
    fun threePassingExamsAndAreaMinimumIndependent() {
        val i = fixture()
        val result =
            Rules.abitur(
                p,
                i.copy(
                    exams =
                        i.exams.map {
                            if (it.name in listOf("Deutsch", "Englisch")) it.copy(points = 3)
                            else it.copy(points = 15)
                        }
                ),
            )
        assertEquals(Verdict.RISK, result.verdict)
        assertTrue(result.details.any { it.contains("Aufgabenfeld 1") })
    }

    @Test
    fun seminarComponentsCannotBeZero() {
        val i = fixture()
        assertEquals(
            Verdict.RISK,
            Rules.abitur(p, i.copy(numbers = i.numbers + ("Seminargespräch" to 0))).verdict,
        )
    }

    @Test
    fun lowCoreAggregateBlocksAdmission() {
        val i = fixture()
        val result =
            Rules.abitur(
                p,
                i.copy(
                    courses =
                        i.courses.map {
                            if (it.name in listOf("Deutsch", "Mathematik", "Physik"))
                                it.copy(points = List(4) { 3 })
                            else it.copy(points = List(4) { 15 })
                        }
                ),
            )
        assertEquals(Verdict.RISK, result.verdict)
        assertTrue(result.details.any { it.contains("48 Punkte") })
    }

    @Test
    fun tooManyDeficitsEvenWithEnoughTotalPoints() {
        val i = fixture()
        var deficitCount = 0
        val courses =
            i.courses.map { c ->
                c.copy(
                    points =
                        c.points.mapIndexed { index, n ->
                            if (c.included[index] && deficitCount++ < 9) 4 else 15
                        }
                )
            }
        val result = Rules.abitur(p, i.copy(courses = courses))
        assertEquals(Verdict.RISK, result.verdict)
        assertTrue(result.details.any { it.contains("32 ausreichende") })
    }

    @Test
    fun fullGradeConversionTableBoundaries() {
        val bounds =
            listOf(
                823,
                805,
                787,
                769,
                751,
                733,
                715,
                697,
                679,
                661,
                643,
                625,
                607,
                589,
                571,
                553,
                535,
                517,
                499,
                481,
                463,
                445,
                427,
                409,
                391,
                373,
                355,
                337,
                319,
                301,
                300,
            )
        bounds.forEachIndexed { index, n ->
            assertEquals((10 + index) / 10.0, Rules.abiturGrade(n), 0.0)
            if (n > 300) assertEquals((11 + index) / 10.0, Rules.abiturGrade(n - 1), 0.0)
        }
    }
}
