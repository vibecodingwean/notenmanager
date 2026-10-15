package de.streberalarm.app

import de.streberalarm.core.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TodayLessonsTest {
    @Test
    fun dailyScheduleUsesEffectivePlanWeekHolidaysAndExceptions() {
        val p = Profile()
        val s = Subject(profileId = p.id, name = "Mathematik")
        val date = LocalDate.parse("2026-09-14")
        val a =
            Lesson(subjectId = s.id, day = 1, slot = 1, week = 1, start = "08:00", end = "08:45")
        val b = a.copy(id = "b", week = 2, start = "09:00", end = "09:45")
        val table =
            Timetable(
                profileId = p.id,
                validFrom = "2026-09-07",
                anchorMonday = "2026-09-07",
                blocks = listOf(a, b),
            )
        val d =
            SchoolData(
                profiles = listOf(p),
                activeProfileId = p.id,
                subjects = listOf(s),
                timetables = listOf(table, table.copy(id = "future", validFrom = "2026-10-01")),
            )
        assertEquals(listOf("09:00"), dailyLessons(d, p, date).map { it.start })
        assertEquals(listOf("08:00"), dailyLessons(d, p, date.minusWeeks(1)).map { it.start })
        assertTrue(dailyLessons(d, p, date.minusWeeks(2)).isEmpty())
        val extra =
            ExceptionLesson(
                subjectId = s.id,
                date = date.toString(),
                start = "14:00",
                end = "14:45",
                cancelled = false,
            )
        val cancelled = extra.copy(id = "cancel", start = "09:00", end = "09:45", cancelled = true)
        assertEquals(
            listOf("14:00"),
            dailyLessons(d.copy(exceptions = listOf(cancelled, extra)), p, date).map { it.start },
        )
        val free =
            DayOff(profileId = p.id, from = date.toString(), to = date.toString(), reason = "Frei")
        assertTrue(dailyLessons(d.copy(daysOff = listOf(free)), p, date).isEmpty())
        assertEquals(
            listOf("14:00"),
            dailyLessons(d.copy(daysOff = listOf(free), exceptions = listOf(extra)), p, date).map {
                it.start
            },
        )
    }

    @Test
    fun supportiveHintsHaveDifferentGradeAndPointDirectionsAndKeepUnknownUnknown() {
        assertEquals("Noch keine Noten", learningHint(false, null))
        assertEquals("Läuft gut", learningHint(false, 2.5))
        assertEquals("Passt gerade – im Blick behalten", learningHint(false, 4.0))
        assertEquals("Hier brauchst du Unterstützung", learningHint(false, 4.01))
        assertEquals("Hier brauchst du Unterstützung", learningHint(true, 4.99))
        assertEquals("Passt gerade – im Blick behalten", learningHint(true, 5.0))
        assertEquals("Läuft gut", learningHint(true, 10.0))
    }
    @Test
    fun calendarNavigationIncludesWeekendsAndCrossesYearBoundaries() {
        assertEquals("2027-01-01", adjacentDay("2026-12-31", 1))
        assertEquals("2026-12-31", adjacentDay("2027-01-01", -1))
        assertEquals("2026-09-19", adjacentDay("2026-09-18", 1))
        assertEquals("2026-09-20", adjacentDay("2026-09-19", 1))
    }
    @Test
    fun examCountNeedsMatchingEffectiveSubjectPlanAndKeepsKnownZero() {
        val p = Profile()
        val subject = Subject(profileId = p.id, name = "Mathematik")
        val other = Subject(profileId = p.id, name = "Deutsch")
        val now = java.time.LocalDateTime.parse("2026-10-12T07:00")
        val exam = Assessment(subjectId = subject.id, title = "Prüfung", date = "2026-10-13")
        val lesson = Lesson(subjectId = subject.id, day = 1, start = "08:00", end = "08:45")
        val table = Timetable(profileId = p.id, validFrom = "2026-09-01", anchorMonday = "2026-09-07", blocks = listOf(lesson))
        val data = SchoolData(profiles = listOf(p), activeProfileId = p.id, subjects = listOf(subject, other))
        assertNull(remainingLessonCount(data, exam, now))
        assertNull(remainingLessonCount(data.copy(timetables = listOf(table.copy(blocks = listOf(lesson.copy(subjectId = other.id))))), exam, now))
        assertNull(remainingLessonCount(data.copy(timetables = listOf(table.copy(validFrom = "2026-10-14"))), exam, now))
        assertEquals(1, remainingLessonCount(data.copy(timetables = listOf(table)), exam, now))
        assertEquals(0, remainingLessonCount(data.copy(timetables = listOf(table.copy(blocks = listOf(lesson.copy(day = 5))))), exam, now))
        val replacement = table.copy(id = "new", validFrom = "2026-10-01", blocks = listOf(lesson.copy(subjectId = other.id)))
        assertNull(remainingLessonCount(data.copy(timetables = listOf(table, replacement)), exam, now))
    }
}
