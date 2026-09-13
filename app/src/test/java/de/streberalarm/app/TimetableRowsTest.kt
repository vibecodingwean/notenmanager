package de.streberalarm.app

import de.streberalarm.core.*
import org.junit.Assert.*
import org.junit.Test

class TimetableRowsTest {
    @Test
    fun afternoonDefaultsAndCustomGapsDoNotCreateGradedSubjects() {
        val starts =
            listOf("08:00", "08:45", "09:50", "10:35", "11:40", "12:25", "13:30", "14:30", "15:15")
        val ends =
            listOf("08:45", "09:30", "10:35", "11:20", "12:25", "13:10", "14:15", "15:15", "16:00")
        var cursor = java.time.LocalTime.of(8, 0)
        val rows =
            (1..9).map { slot ->
                val times = periodTimes(slot, cursor)
                assertEquals(starts[slot - 1] to ends[slot - 1], times)
                cursor = java.time.LocalTime.parse(times.second)
                Lesson(
                    subjectId = "math",
                    day = 1,
                    slot = slot,
                    start = times.first,
                    end = times.second,
                )
            }
        assertEquals(listOf(20L, 20L, 20L, 15L), timetableGaps(rows).values.map { it.minutes })
        val changed = rows.map { if (it.slot == 1) it.copy(end = "08:30") else it }
        assertEquals(TimetableGap("08:30", "08:45"), timetableGaps(changed)[rows[1].id])
        assertEquals(9, rows.size)
        assertEquals("16:00" to "16:45", periodTimes(10, cursor))
        assertEquals("16:15" to "17:00", periodTimes(7, java.time.LocalTime.of(16, 15)))
    }

    @Test
    fun contiguousOverlappingAndExplicitBreakPeriodsProduceNoFalseGaps() {
        val a = Lesson(subjectId = "math", day = 1, start = "08:00", end = "09:30")
        val b = a.copy(id = "b", start = "08:45", end = "09:00")
        val pause =
            a.copy(id = "pause", subjectId = "", isBreak = true, start = "09:30", end = "09:50")
        val c = a.copy(id = "c", start = "09:50", end = "10:35")
        assertTrue(timetableGaps(listOf(c, pause, b, a)).isEmpty())
        assertTrue(timetableGaps(emptyList()).isEmpty())
    }

    @Test
    fun emptyWeekHasSixSlotsOnEverySchoolDay() {
        val rows = timetableRows(null)
        assertEquals(30, rows.size)
        for (day in 1..5) assertEquals(
            (1..6).toList(),
            rows.filter { it.day == day }.map { it.slot },
        )
        assertTrue(rows.all { it.subjectId.isEmpty() && !it.isBreak })
        assertEquals(
            listOf(
                "08:00–08:45",
                "08:45–09:30",
                "09:50–10:35",
                "10:35–11:20",
                "11:40–12:25",
                "12:25–13:10",
            ),
            rows.filter { it.day == 1 }.map { "${it.start}–${it.end}" },
        )
    }

    @Test
    fun oldDoublePeriodIsShownAsTwoAdjacentHoursWithoutChangingTime() {
        val t =
            Timetable(
                profileId = "p",
                validFrom = "2026-09-14",
                anchorMonday = "2026-09-14",
                blocks = listOf(Lesson(subjectId = "math", day = 1, start = "08:00", end = "09:30")),
            )
        val rows = timetableRows(t).filter { it.day == 1 }
        assertEquals(listOf("math", "math", "", "", "", ""), rows.map { it.subjectId })
        assertEquals(listOf("08:00", "08:45", "09:50"), rows.take(3).map { it.start })
        assertEquals("09:30", rows[1].end)
    }

    @Test
    fun positionedBreakAndAfternoonSlotsKeepTheirPlaces() {
        val t =
            Timetable(
                profileId = "p",
                validFrom = "2026-09-14",
                anchorMonday = "2026-09-14",
                blocks =
                    listOf(
                        Lesson(
                            subjectId = "",
                            isBreak = true,
                            slot = 2,
                            day = 1,
                            start = "08:45",
                            end = "09:00",
                        ),
                        Lesson(subjectId = "art", slot = 8, day = 1, start = "14:00", end = "14:45"),
                    ),
            )
        val rows = timetableRows(t).filter { it.day == 1 }
        assertEquals(8, rows.size)
        assertTrue(rows[1].isBreak)
        assertEquals("09:50", rows[2].start)
        assertEquals("14:00", rows[7].start)
        assertEquals("art", rows[7].subjectId)
    }
}
