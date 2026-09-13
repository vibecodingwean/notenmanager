package de.streberalarm.core

import kotlinx.datetime.*

/** Portable civil dates/times: calculations never consult the host clock implicitly. */
@ConsistentCopyVisibility
data class SchoolDate internal constructor(internal val raw: LocalDate) : Comparable<SchoolDate> {
    val dayOfWeek: DayOfWeek
        get() = raw.dayOfWeek

    val epochDay: Int
        get() = raw.toEpochDays()

    override fun compareTo(other: SchoolDate) = raw.compareTo(other.raw)

    override fun toString() = raw.toString()

    fun plusDays(n: Long) = SchoolDate(raw.plus(n.toInt(), DateTimeUnit.DAY))

    fun minusDays(n: Long) = plusDays(-n)

    fun plusYears(n: Int) = SchoolDate(raw.plus(n, DateTimeUnit.YEAR))

    fun with(day: DayOfWeek) = plusDays((day.isoDayNumber - dayOfWeek.isoDayNumber).toLong())

    fun atTime(hour: Int, minute: Int) = SchoolDateTime(raw.atTime(hour, minute))

    fun atStartOfDay(zone: SchoolZone) = SchoolZonedTime(raw.atStartOfDayIn(zone.raw), zone)

    companion object {
        fun parse(value: String) = SchoolDate(LocalDate.parse(value))

        fun of(year: Int, month: Int, day: Int) = SchoolDate(LocalDate(year, month, day))
    }
}

@ConsistentCopyVisibility
data class SchoolTime internal constructor(internal val raw: LocalTime) : Comparable<SchoolTime> {
    override fun compareTo(other: SchoolTime) = raw.compareTo(other.raw)

    override fun toString() = raw.toString()

    fun atDate(date: SchoolDate) = SchoolDateTime(LocalDateTime(date.raw, raw))

    companion object {
        fun parse(value: String) = SchoolTime(LocalTime.parse(value))
    }
}

@ConsistentCopyVisibility
data class SchoolDateTime internal constructor(internal val raw: LocalDateTime) :
    Comparable<SchoolDateTime> {
    override fun compareTo(other: SchoolDateTime) = raw.compareTo(other.raw)

    override fun toString() = raw.toString()

    fun toLocalDate() = SchoolDate(raw.date)

    fun atZone(zone: SchoolZone) = SchoolZonedTime(raw.toInstant(zone.raw), zone)

    companion object {
        fun parse(value: String) = SchoolDateTime(LocalDateTime.parse(value))
    }
}

data class SchoolZone(val id: String) {
    internal val raw: TimeZone
        get() = TimeZone.of(id)
}

@ConsistentCopyVisibility
data class SchoolZonedTime
internal constructor(internal val instant: Instant, val zone: SchoolZone) :
    Comparable<SchoolZonedTime> {
    val epochMillis: Long
        get() = instant.toEpochMilliseconds()

    override fun compareTo(other: SchoolZonedTime) = instant.compareTo(other.instant)

    fun toLocalDateTime() = SchoolDateTime(instant.toLocalDateTime(zone.raw))

    fun toLocalDate() = toLocalDateTime().toLocalDate()

    companion object {
        fun fromEpochMillis(millis: Long, zoneId: String) =
            SchoolZonedTime(Instant.fromEpochMilliseconds(millis), SchoolZone(zoneId))
    }
}
