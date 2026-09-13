package de.streberalarm.core

import java.time.*

/** Android/JVM adapters keep java.time out of the shared iOS-compatible calculation module. */
data class JavaOccurrence(val date: LocalDate, val start: LocalTime, val end: LocalTime)

data class JavaReminderDay(val at: ZonedDateTime, val notices: List<Notice>)

fun Lessons.remaining(
    data: SchoolData,
    exam: Assessment,
    now: LocalDateTime,
): List<JavaOccurrence> =
    remaining(data, exam, SchoolDateTime.parse(now.toString())).map {
        JavaOccurrence(
            LocalDate.parse(it.date.toString()),
            LocalTime.parse(it.start.toString()),
            LocalTime.parse(it.end.toString()),
        )
    }

fun Reminders.plan(data: SchoolData, now: ZonedDateTime): List<JavaReminderDay> =
    plan(data, SchoolZonedTime.fromEpochMillis(now.toInstant().toEpochMilli(), now.zone.id)).map {
        JavaReminderDay(
            Instant.ofEpochMilli(it.at.epochMillis).atZone(ZoneId.of(it.at.zone.id)),
            it.notices,
        )
    }
