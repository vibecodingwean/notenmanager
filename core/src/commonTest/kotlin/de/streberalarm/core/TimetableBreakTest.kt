package de.streberalarm.core

import kotlin.test.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class TimetableBreakTest {
    @Test
    fun breaksStayOutOfGradesAndSeparateTeachingBlocks() {
        val p = Profile(school = School.REALSCHULE, track = "II")
        val s = Subject(profileId = p.id, name = "Mathematik")
        val e = Assessment(subjectId = s.id, title = "Brüche", date = "2026-09-15")
        val rows =
            listOf(
                Lesson(subjectId = s.id, day = 1, start = "08:00", end = "08:45", slot = 1),
                Lesson(
                    subjectId = "",
                    day = 1,
                    start = "08:45",
                    end = "09:00",
                    slot = 2,
                    isBreak = true,
                ),
                Lesson(subjectId = s.id, day = 1, start = "09:00", end = "09:45", slot = 3),
                Lesson(subjectId = s.id, day = 1, start = "09:45", end = "10:30", slot = 4),
            )
        val d =
            SchoolData(
                profiles = listOf(p),
                activeProfileId = p.id,
                subjects = listOf(s),
                assessments = listOf(e),
                timetables =
                    listOf(
                        Timetable(
                            profileId = p.id,
                            validFrom = "2026-09-14",
                            anchorMonday = "2026-09-14",
                            blocks = rows,
                        )
                    ),
            )
        d.validate()
        val restored = dataJson.decodeFromString<SchoolData>(dataJson.encodeToString(d))
        assertEquals(d, restored)
        assertEquals(
            2,
            Lessons.remaining(restored, e, SchoolDateTime.parse("2026-09-14T07:00")).size,
        )
        assertEquals(1, restored.subjects.size)
        assertNull(Grades.calculate(p, s, emptyList()).value)
    }

    @Test
    fun oldLessonsStillDecodeAndAmbiguousSlotsFail() {
        val old =
            dataJson.decodeFromString<Lesson>(
                """{"id":"lesson","subjectId":"math","day":1,"start":"08:00","end":"08:45"}"""
            )
        assertFalse(old.isBreak)
        assertEquals(0, old.slot)
        val p = Profile()
        val s = Subject(id = "math", profileId = p.id, name = "Mathematik")
        val d =
            SchoolData(
                profiles = listOf(p),
                activeProfileId = p.id,
                subjects = listOf(s),
                timetables =
                    listOf(
                        Timetable(
                            profileId = p.id,
                            validFrom = "2026-09-14",
                            anchorMonday = "2026-09-14",
                            blocks = listOf(old.copy(slot = 2), old.copy(id = "second", slot = 2)),
                        )
                    ),
            )
        assertFailsWith<IllegalArgumentException> { d.validate() }
    }

    @Test
    fun descriptiveTrackLabelsDoNotChangeStoredKeysOrProfileSubjects() {
        for ((track, subject) in
            listOf(
                "I" to "Physik",
                "II" to "Betriebswirtschaftslehre/Rechnungswesen",
                "IIIa" to "Französisch",
                "IIIb · Sozialwesen" to "Sozialwesen",
            )) {
            val p = Profile(school = School.REALSCHULE, track = track)
            val saved = dataJson.decodeFromString<Profile>(dataJson.encodeToString(p))
            assertEquals(track, saved.track)
            assertTrue(Defaults.trackLabel(p.school, saved.track).length > 4)
            assertTrue(Defaults.subjects(saved).single { it.name == subject }.core)
        }
    }

    @Test
    fun offeredClassesMatchSchoolValidationAtBothEnds() {
        for (school in School.entries) {
            val range = Defaults.grades(school)
            for (grade in range) Profile(
                    school = school,
                    grade = grade,
                    term =
                        if (school == School.GYMNASIUM && grade >= 12) "$grade/1"
                        else "Ganzes Schuljahr",
                )
                .validate()
            for (grade in listOf(range.first - 1, range.last + 1)) assertFailsWith<
                IllegalArgumentException
            > {
                Profile(school = school, grade = grade).validate()
            }
        }
    }
}
