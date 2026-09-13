package de.streberalarm.core

import de.streberalarm.core.SchoolDate as LocalDate
import de.streberalarm.core.SchoolDateTime as LocalDateTime
import de.streberalarm.core.SchoolTime as LocalTime
import de.streberalarm.core.SchoolZonedTime as ZonedDateTime
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

data class Occurrence(val date: LocalDate, val start: LocalTime, val end: LocalTime)

object Lessons {
    fun remaining(data: SchoolData, exam: Assessment, now: LocalDateTime): List<Occurrence> {
        if (exam.stage != Stage.PLANNED) return emptyList()
        val subject = data.subjects.find { it.id == exam.subjectId } ?: return emptyList()
        val examDate = LocalDate.parse(exam.date)
        if (examDate < now.toLocalDate()) return emptyList()
        require(examDate.epochDay - now.toLocalDate().epochDay <= 3700)
        val result = mutableListOf<Occurrence>()
        var day = now.toLocalDate()
        while (day <= examDate) {
            val date = day
            val free =
                data.daysOff.any {
                    it.profileId == subject.profileId &&
                        date >= LocalDate.parse(it.from) &&
                        date <= LocalDate.parse(it.to)
                }
            val timetable =
                data.timetables
                    .filter {
                        it.profileId == subject.profileId && LocalDate.parse(it.validFrom) <= date
                    }
                    .maxByOrNull { it.validFrom }
            val regular =
                if (free || timetable == null) emptyList()
                else
                    timetable.blocks
                        .filter { b ->
                            val week =
                                (((date.with(DayOfWeek.MONDAY).epochDay -
                                        LocalDate.parse(timetable.anchorMonday).epochDay) / 7)
                                    .mod(2)) + 1
                            !b.isBreak &&
                                b.subjectId == subject.id &&
                                b.day == date.dayOfWeek.isoDayNumber &&
                                (b.week == 0 || b.week == week)
                        }
                        .map {
                            Occurrence(date, LocalTime.parse(it.start), LocalTime.parse(it.end))
                        }
            val changes =
                data.exceptions.filter { it.subjectId == subject.id && it.date == date.toString() }
            val slots =
                (regular.filterNot { r ->
                        changes.any {
                            it.cancelled &&
                                LocalTime.parse(it.start) < r.end &&
                                LocalTime.parse(it.end) > r.start
                        }
                    } +
                        changes
                            .filterNot { it.cancelled }
                            .map {
                                Occurrence(date, LocalTime.parse(it.start), LocalTime.parse(it.end))
                            })
                    .sortedBy { it.start }
            // Adjacent or overlapping periods of the same subject constitute one teaching block.
            val merged = mutableListOf<Occurrence>()
            slots.forEach { s ->
                val last = merged.lastOrNull()
                if (last != null && s.start <= last.end)
                    merged[merged.lastIndex] = last.copy(end = maxOf(last.end, s.end))
                else merged.add(s)
            }
            result +=
                merged.filter { s ->
                    s.start.atDate(date) >= now &&
                        (date < examDate || exam.time != null && s.end < LocalTime.parse(exam.time))
                }
            day = day.plusDays(1)
        }
        return result
    }
}

data class Notice(val key: String, val examId: String, val text: String)

data class ReminderDay(val at: ZonedDateTime, val notices: List<Notice>)

object Reminders {
    val offsets = listOf(21, 14, 7, 6, 5, 4, 3, 2, 1)

    fun plan(data: SchoolData, now: ZonedDateTime): List<ReminderDay> {
        val days = mutableMapOf<LocalDate, MutableList<Notice>>()
        data.assessments
            .filter {
                it.stage == Stage.PLANNED &&
                    LocalDate.parse(it.date) <= now.toLocalDate().plusYears(2)
            }
            .forEach { e ->
                val subject = data.subjects.find { it.id == e.subjectId } ?: return@forEach
                if (subject.profileId != data.activeProfileId) return@forEach
                offsets.forEach { offset ->
                    val date = LocalDate.parse(e.date).minusDays(offset.toLong())
                    val at = date.atTime(16, 0).atZone(now.zone)
                    val key = "date:${e.id}:${e.date}:$offset"
                    if (at > now && key !in data.delivered)
                        days
                            .getOrPut(date) { mutableListOf() }
                            .add(
                                Notice(
                                    key,
                                    e.id,
                                    "${subject.name}: ${e.title} in $offset ${if(offset==1) "Tag" else "Tagen"}",
                                )
                            )
                }
                if (data.timetables.none { it.profileId == subject.profileId }) return@forEach
                val key = "lessons:${e.id}:${e.date}"
                if (key in data.delivered) return@forEach
                var day = now.toLocalDate()
                val last = LocalDate.parse(e.date).minusDays(1)
                // Daily 16:00 checks also run on days without the regular day-offset reminder.
                while (day <= last) {
                    val at = day.atTime(16, 0).atZone(now.zone)
                    if (at > now) {
                        val count = Lessons.remaining(data, e, at.toLocalDateTime()).size
                        if (count <= 2) {
                            days
                                .getOrPut(day) { mutableListOf() }
                                .add(
                                    Notice(
                                        key,
                                        e.id,
                                        "Nur noch $count× ${subject.name} bis ${e.title}",
                                    )
                                )
                            break
                        }
                    }
                    day = day.plusDays(1)
                }
            }
        return days.entries
            .sortedBy { it.key }
            .map { (date, n) -> ReminderDay(date.atTime(16, 0).atZone(now.zone), n) }
    }
}

object Timers {
    fun start(data: SchoolData, examId: String, wall: Long, elapsed: Long, boot: Int): SchoolData {
        require(data.studies.none { it.end == null }) { "Es läuft bereits ein Lerntimer." }
        require(data.assessments.any { it.id == examId && it.stage != Stage.CANCELLED })
        return data.copy(
            studies =
                data.studies +
                    Study(assessmentId = examId, start = wall, elapsedStart = elapsed, boot = boot)
        )
    }

    fun stop(data: SchoolData, wall: Long, elapsed: Long, boot: Int): SchoolData =
        data.copy(
            studies =
                data.studies.map { s ->
                    if (s.end != null) s
                    else {
                        val sameBoot = boot == s.boot && elapsed >= s.elapsedStart
                        s.copy(
                            end =
                                if (sameBoot) s.start + elapsed - s.elapsedStart
                                else maxOf(wall, s.start),
                            correction =
                                if (sameBoot) s.correction
                                else
                                    "Gerät neu gestartet: Dauer bitte prüfen und bei Bedarf berichtigen.",
                        )
                    }
                }
        )
}
