package de.streberalarm.core

import kotlin.test.*
import kotlin.test.Test

class GradeSpecialTest {
    private fun assessment(s: Subject, k: Kind, n: Int) =
        Assessment(
            subjectId = s.id,
            title = k.label,
            date = "2026-11-01",
            kind = k,
            actual = n,
            stage = Stage.GRADED,
        )

    @Test
    fun upperBasicLastTermUsesOnlySmall() {
        val p = Profile(grade = 13, term = "13/2")
        val s = Subject(profileId = p.id, name = "Deutsch", weightsConfirmed = true)
        assertEquals(
            6.0,
            Grades.calculate(
                    p,
                    s,
                    listOf(assessment(s, Kind.SCHOOLWORK, 15), assessment(s, Kind.ORAL, 6)),
                )
                .value!!,
            0.0,
        )
    }

    @Test
    fun advancedArtUsesThreeComponents() {
        val p = Profile(grade = 12, term = "12/1")
        val s = Subject(profileId = p.id, name = "Kunst", advanced = true, weightsConfirmed = true)
        assertEquals(
            10.0,
            Grades.calculate(
                    p,
                    s,
                    listOf(
                        assessment(s, Kind.SCHOOLWORK, 12),
                        assessment(s, Kind.ART_PROJECT, 9),
                        assessment(s, Kind.ORAL, 9),
                    ),
                )
                .value!!,
            0.0,
        )
    }

    @Test
    fun basicSportPracticalDouble() {
        val p = Profile(grade = 12, term = "12/1")
        val s = Subject(profileId = p.id, name = "Sport", weightsConfirmed = true)
        assertEquals(
            10.0,
            Grades.calculate(
                    p,
                    s,
                    listOf(assessment(s, Kind.PRACTICAL, 12), assessment(s, Kind.ORAL, 6)),
                )
                .value!!,
            0.0,
        )
    }

    @Test
    fun noRoundingUpToOne() {
        assertEquals(0, Grades.semesterPoints(0.9))
        assertEquals(1, Grades.semesterPoints(1.0))
        assertEquals(2, Grades.semesterPoints(1.5))
    }

    @Test
    fun musischesMusicInstrumentAndClassEqual() {
        val p = Profile(track = "MuG")
        val s =
            Subject(
                profileId = p.id,
                name = "Musik",
                annualSchoolworks = 2,
                weightsConfirmed = true,
            )
        assertEquals(
            3.0,
            Grades.calculate(
                    p,
                    s,
                    listOf(
                        assessment(s, Kind.SCHOOLWORK, 2),
                        assessment(s, Kind.ORAL, 2),
                        assessment(s, Kind.INSTRUMENT, 4),
                    ),
                )
                .value!!,
            0.0,
        )
    }
}
