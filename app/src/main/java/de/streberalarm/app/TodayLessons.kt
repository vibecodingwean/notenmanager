package de.streberalarm.app

import de.streberalarm.core.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal fun dailyLessons(data: SchoolData, profile: Profile, date: LocalDate): List<Lesson> {
    val day = date.toString()
    val subjects = data.subjects.filter { it.profileId == profile.id }.map { it.id }.toSet()
    val table =
        data.timetables
            .filter { it.profileId == profile.id && it.validFrom <= day }
            .maxByOrNull { it.validFrom }
    val free = data.daysOff.any { it.profileId == profile.id && day >= it.from && day <= it.to }
    val week =
        table?.let {
            ChronoUnit.WEEKS.between(LocalDate.parse(it.anchorMonday), date.with(DayOfWeek.MONDAY))
                .mod(2)
                .toInt() + 1
        }
    val changes = data.exceptions.filter { it.date == day && it.subjectId in subjects }
    val regular =
        if (free || table == null) emptyList()
        else
            timetableRows(table).filter { b ->
                b.day == date.dayOfWeek.value &&
                    (b.week == 0 || b.week == week) &&
                    (b.isBreak || b.subjectId in subjects) &&
                    changes.none {
                        it.cancelled &&
                            it.subjectId == b.subjectId &&
                            it.start < b.end &&
                            it.end > b.start
                    }
            }
    val extra =
        changes
            .filterNot { it.cancelled }
            .map {
                Lesson(
                    id = it.id,
                    subjectId = it.subjectId,
                    day = date.dayOfWeek.value,
                    start = it.start,
                    end = it.end,
                )
            }
    return (regular + extra)
        .distinctBy { Triple(it.subjectId, it.start, it.end) }
        .sortedBy { it.start }
}

/** Learning guidance only, not a statutory promotion or report-grade rule. */
internal fun learningHint(points: Boolean, value: Double?): String =
    when {
        value == null -> "Noch keine Noten"
        if (points) value < 5 else value > 4 -> "Hier brauchst du Unterstützung"
        if (points) value >= 10 else value <= 2.5 -> "Läuft gut"
        else -> "Passt gerade – im Blick behalten"
    }
