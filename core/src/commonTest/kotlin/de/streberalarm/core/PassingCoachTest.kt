package de.streberalarm.core

import kotlin.test.*

class PassingCoachTest {
    private val p = Profile(school = School.REALSCHULE, grade = 8, track = "I")

    private fun data(vararg grades: Int): SchoolData {
        val subjects =
            grades.mapIndexed { i, _ ->
                Subject(profileId = p.id, name = "Fach $i", promotion = true)
            }
        return SchoolData(
            profiles = listOf(p),
            activeProfileId = p.id,
            subjects = subjects,
            assessments =
                subjects.zip(grades.toList()).map { (s, n) ->
                    Assessment(
                        subjectId = s.id,
                        title = "Probe",
                        date = "2026-10-01",
                        actual = n,
                        stage = Stage.GRADED,
                        kind = Kind.TEST,
                    )
                },
        )
    }

    @Test
    fun aThreeAndFourAreEnoughWithoutTopGradePressure() {
        val advice = PassingCoach.advice(p, data(3, 4), false)
        assertTrue(advice.canRelax)
        assertEquals("Läuft bei dir", advice.title)
        assertTrue(advice.message.contains("Feierabend"))
    }

    @Test
    fun aggregateCannotHideRiskAndMissingGradesNeverGiveAllClear() {
        val risky = data(1, 1, 6, 6)
        assertEquals(3.5, GradeOverviewCalculator.calculate(p, risky).average)
        assertFalse(PassingCoach.advice(p, risky, false).canRelax)
        val empty = risky.copy(assessments = emptyList())
        assertFalse(PassingCoach.advice(p, empty, false).canRelax)
    }

    @Test
    fun imminentExamAndGraduationRequirementsKeepAdviceConditional() {
        assertFalse(PassingCoach.advice(p, data(3, 4), true).canRelax)
        assertFalse(PassingCoach.advice(p.copy(grade = 10), data(3, 4), false).canRelax)
    }
}
